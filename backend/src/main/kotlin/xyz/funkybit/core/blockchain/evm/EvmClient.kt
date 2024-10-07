package xyz.funkybit.core.blockchain.evm

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.web3j.abi.DefaultFunctionEncoder
import org.web3j.crypto.Credentials
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.RemoteFunctionCall
import org.web3j.protocol.core.methods.request.EthFilter
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.core.methods.response.EthBlock
import org.web3j.protocol.core.methods.response.EthLog
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.protocol.http.HttpService
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.exceptions.ContractCallException
import org.web3j.tx.response.PollingTransactionReceiptProcessor
import org.web3j.utils.Async
import xyz.funkybit.contracts.generated.ERC1967Proxy
import xyz.funkybit.contracts.generated.ERC20
import xyz.funkybit.contracts.generated.Exchange
import xyz.funkybit.contracts.generated.MockERC20
import xyz.funkybit.contracts.generated.UUPSUpgradeable
import xyz.funkybit.core.blockchain.ContractType
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.EvmSignature
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.db.BlockchainNonceEntity
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.evm.EvmSettlement
import xyz.funkybit.core.model.toEvmSignature
import xyz.funkybit.core.utils.HttpClient
import xyz.funkybit.core.utils.fromFundamentalUnits
import xyz.funkybit.core.utils.toFundamentalUnits
import xyz.funkybit.core.utils.toHex
import xyz.funkybit.core.utils.toHexBytes
import xyz.funkybit.core.utils.withLogging
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Duration

data class EvmClientConfig(
    val name: String,
    val url: String,
    val blockExplorerNetName: String,
    val blockExplorerUrl: String,
    val privateKeyHex: String,
    val submitterPrivateKeyHex: String,
    val airdropperPrivateKeyHex: String,
    val faucetPrivateKeyHex: String,
    val feeAccountAddress: String,
    val pollingIntervalInMs: Long,
    val maxPollingAttempts: Long,
    val contractCreationLimit: BigInteger,
    val contractInvocationLimit: BigInteger,
    val defaultMaxPriorityFeePerGasInWei: BigInteger,
    val enableWeb3jLogging: Boolean,
    val maxRpcNodeEventualConsistencyTolerance: Duration,
    val sovereignWithdrawalDelaySeconds: BigInteger,
)

class EvmServerException(message: String) : Exception(message)
class EvmClientException(message: String) : Exception(message)

open class TransactionManagerWithNonceOverride(
    web3j: Web3j,
    val credentials: Credentials,
    private val nonceOverride: BigInteger,
) : RawTransactionManager(web3j, credentials) {
    override fun getNonce(): BigInteger =
        nonceOverride
}

fun Credentials.checksumAddress(): EvmAddress {
    return EvmAddress(Keys.toChecksumAddress(this.address))
}

sealed class DefaultBlockParam {
    data object Earliest : DefaultBlockParam()
    data object Latest : DefaultBlockParam()
    data object Safe : DefaultBlockParam()
    data object Finalized : DefaultBlockParam()
    data object Pending : DefaultBlockParam()
    data class BlockNumber(val value: BigInteger) : DefaultBlockParam()

    fun toWeb3j(): DefaultBlockParameter =
        when (this) {
            is Earliest -> DefaultBlockParameter.valueOf("earliest")
            is Latest -> DefaultBlockParameter.valueOf("latest")
            is Safe -> DefaultBlockParameter.valueOf("safe")
            is Finalized -> DefaultBlockParameter.valueOf("finalized")
            is Pending -> DefaultBlockParameter.valueOf("pending")
            is BlockNumber -> DefaultBlockParameter.valueOf(this.value)
        }

    override fun toString(): String {
        return when (this) {
            is Earliest -> "earliest"
            is Latest -> "latest"
            is Safe -> "safe"
            is Finalized -> "finalized"
            is Pending -> "pending"
            is BlockNumber -> this.value.toString()
        }
    }
}

open class EvmClient(val config: EvmClientConfig) {
    protected val logger = KotlinLogging.logger {}
    protected val web3jService: HttpService = HttpService(
        config.url,
        HttpClient.newBuilder().withLogging(logger).build(),
    )
    protected val web3j: Web3j = Web3j.build(
        web3jService,
        config.pollingIntervalInMs,
        Async.defaultExecutorService(),
    )
    protected val credentials = Credentials.create(config.privateKeyHex)
    val chainId = ChainId(web3j.ethChainId().send().chainId)
    protected val receiptProcessor = PollingTransactionReceiptProcessor(
        web3j,
        config.pollingIntervalInMs,
        config.maxPollingAttempts.toInt(),
    )
    protected val transactionManager = RawTransactionManager(
        web3j,
        credentials,
        chainId.value.toLong(),
        receiptProcessor,
    )

    protected val submitterCredentials = Credentials.create(config.submitterPrivateKeyHex)
    val submitterAddress: EvmAddress = submitterCredentials.checksumAddress()

    private val airdropperCredentials: Credentials = Credentials.create(config.airdropperPrivateKeyHex)
    val airdropperAddress: EvmAddress = airdropperCredentials.checksumAddress()

    val gasProvider = GasProvider(
        contractCreationLimit = config.contractCreationLimit,
        contractInvocationLimit = config.contractInvocationLimit,
        defaultMaxPriorityFeePerGas = config.defaultMaxPriorityFeePerGasInWei,
        chainId = chainId.toLong(),
        web3j = web3j,
    )
    private val contractMap = mutableMapOf<ContractType, Address>()

    data class DeployedContract(
        val proxyAddress: EvmAddress,
        val implementationAddress: EvmAddress,
        val version: Int,
    )

    fun deployOrUpgradeWithProxy(contractType: ContractType, existingProxyAddress: EvmAddress?): DeployedContract {
        logger.debug { "Starting deployment for $contractType" }

        val (implementationContractAddress, version) = when (contractType) {
            ContractType.Exchange -> {
                val implementationContract = Exchange.deploy(web3j, transactionManager, gasProvider).send()
                Pair(
                    EvmAddress(Keys.toChecksumAddress(implementationContract.contractAddress)),
                    implementationContract.version.send().toInt(),
                )
            }
        }

        val proxyAddress = if (existingProxyAddress != null) {
            // for now call upgradeTo here
            logger.debug { "Calling upgradeTo for $contractType" }
            UUPSUpgradeable.load(
                existingProxyAddress.value,
                web3j,
                transactionManager,
                gasProvider,
            ).upgradeToAndCall(implementationContractAddress.value, ByteArray(0), BigInteger.ZERO).send()
            existingProxyAddress
        } else {
            // deploy the proxy and call the initialize method in contract - this can only be called once
            logger.debug { "Deploying proxy for $contractType" }
            val proxyContract = ERC1967Proxy.deploy(
                web3j,
                transactionManager,
                gasProvider,
                BigInteger.ZERO,
                implementationContractAddress.value,
                ByteArray(0),
            ).send()
            logger.debug { "Deploying initialize for $contractType" }
            when (contractType) {
                ContractType.Exchange -> {
                    Exchange.load(
                        proxyContract.contractAddress,
                        web3j,
                        transactionManager,
                        gasProvider,
                    ).initialize(
                        submitterCredentials.address,
                        config.feeAccountAddress,
                        config.sovereignWithdrawalDelaySeconds,
                    ).send()
                }
            }
            EvmAddress(Keys.toChecksumAddress(proxyContract.contractAddress))
        }

        logger.debug { "Deployment complete for $contractType" }
        setContractAddress(contractType, proxyAddress)

        val contractSovereignWithdrawalDelay = getSovereignWithdrawalDelay(
            DefaultBlockParam.Latest,
        )
        if (contractSovereignWithdrawalDelay != config.sovereignWithdrawalDelaySeconds) {
            logger.debug { "Updating sovereign withdrawal delay value from $contractSovereignWithdrawalDelay to ${config.sovereignWithdrawalDelaySeconds}" }

            exchangeContractCall(DefaultBlockParam.Latest) {
                setSovereignWithdrawalDelay(config.sovereignWithdrawalDelaySeconds)
            }
        }

        return DeployedContract(
            proxyAddress = proxyAddress,
            implementationAddress = implementationContractAddress,
            version = version,
        )
    }

    private fun <A> exchangeContractCall(block: DefaultBlockParam, f: Exchange.() -> RemoteFunctionCall<A>): A {
        val contract = loadExchangeContract(exchangeContractAddress)
        contract.setDefaultBlockParameter(block.toWeb3j())
        val startTime = Clock.System.now()
        while (true) {
            try {
                return f(contract).send()
            } catch (e: ContractCallException) {
                val errorMessage = e.message ?: ""
                val badBlockNumberErrors = setOf(
                    // returned by Anvil
                    "BlockOutOfRangeError",
                    // returned by Bitlayer and Sepolia
                    "header not found",
                )
                if (
                    badBlockNumberErrors.none { errorMessage.contains(it) } ||
                    Clock.System.now() - startTime >= config.maxRpcNodeEventualConsistencyTolerance
                ) {
                    throw e
                }
            }
        }
    }

    fun getExchangeBalance(address: Address, tokenAddress: Address, block: DefaultBlockParam): BigInteger {
        return exchangeContractCall(block) {
            balances(address.toString(), tokenAddress.toString())
        }
    }

    private fun getExchangeBalances(walletAddress: Address, tokenAddresses: List<Address>, block: DefaultBlockParam): Map<Address, BigInteger> =
        tokenAddresses.associateWith { tokenAddress ->
            getExchangeBalance(walletAddress, tokenAddress, block)
        }

    fun getExchangeBalances(walletAddresses: List<Address>, tokenAddresses: List<Address>, block: DefaultBlockParam): Map<Address, Map<Address, BigInteger>> =
        walletAddresses.associateWith { walletAddress ->
            getExchangeBalances(walletAddress, tokenAddresses, block)
        }

    fun loadExchangeContract(address: Address): Exchange {
        return Exchange.load(address.toString(), web3j, transactionManager, gasProvider)
    }

    fun setContractAddress(contractType: ContractType, address: Address) {
        contractMap[contractType] = address
    }

    fun getContractAddress(contractType: ContractType): Address =
        contractMap.getValue(contractType)

    fun getTransactionReceipt(txHash: TxHash): TransactionReceipt? =
        getTransactionReceipt(txHash.value)

    fun getTransactionReceipt(txHash: String): TransactionReceipt? {
        val receipt = web3j.ethGetTransactionReceipt(txHash).send().transactionReceipt
        return if (receipt.isPresent) {
            return receipt.get()
        } else {
            null
        }
    }

    fun gasUsed(txHash: TxHash): BigInteger? {
        return getTransactionReceipt(txHash.value)?.let {
            it.gasUsed * BigInteger(it.effectiveGasPrice.substring(2), 16)
        }
    }

    fun getTransactionByHash(txHash: String): org.web3j.protocol.core.methods.response.Transaction? {
        return web3j.ethGetTransactionByHash(txHash).send().transaction.getOrNull()
    }

    open fun getBlock(blockNumber: BigInteger, withFullTxObjects: Boolean): EthBlock.Block =
        web3j.ethGetBlockByNumber(DefaultBlockParameter.valueOf(blockNumber), withFullTxObjects).send().block

    open fun getBlockNumber(): BigInteger {
        return web3j.ethBlockNumber().send().blockNumber
    }

    fun getLogs(block: DefaultBlockParam, address: Address): EthLog =
        web3j
            .ethGetLogs(EthFilter(block.toWeb3j(), block.toWeb3j(), address.toString()))
            .send()

    fun getExchangeContractLogs(block: BigInteger): EthLog =
        getLogs(
            DefaultBlockParam.BlockNumber(block),
            getContractAddress(
                ContractType.Exchange,
            ),
        )

    fun getTxManager(nonceOverride: BigInteger): TransactionManagerWithNonceOverride {
        return TransactionManagerWithNonceOverride(web3j, submitterCredentials, nonceOverride)
    }

    fun getNonce(address: String): BigInteger {
        return web3j.ethGetTransactionCount(address, DefaultBlockParameterName.PENDING).send().transactionCount
    }

    fun getConsistentNonce(address: String): BigInteger {
        // this logic handles the fact that all RPC nodes may not be in sync, so we try to get a consistent nonce
        // by making multiple calls until we get a consistent value. Subsequently, we keep track of it ourselves.
        var isConsistent = false
        var candidateNonce: BigInteger? = null
        while (!isConsistent) {
            candidateNonce = getNonce(address)
            isConsistent = (1..2).map { getNonce(address) }.all { it == candidateNonce }
            if (!isConsistent) {
                logger.error { "Got inconsistent nonces, retrying" }
                Thread.sleep(100)
            }
        }
        return candidateNonce!!
    }

    fun extractRevertReasonFromSimulation(data: String, blockNumber: BigInteger): String? {
        val response = web3j.ethCall(
            Transaction.createEthCallTransaction(
                submitterCredentials.address,
                contractMap[ContractType.Exchange]!!.toString(),
                data,
                BigInteger.ZERO,
            ),
            DefaultBlockParameter.valueOf(blockNumber),
        ).send()

        return if (response.isReverted) {
            response.revertReason ?: "Unknown Error"
        } else {
            null
        }
    }

    fun loadERC20(address: EvmAddress) = ERC20.load(address.value, web3j, transactionManager, gasProvider)

    fun signData(hash: ByteArray, linkedSignerEcKeyPair: ECKeyPair? = null): EvmSignature {
        val signature = Sign.signMessage(hash, linkedSignerEcKeyPair ?: credentials.ecKeyPair, false)
        return (signature.r + signature.s + signature.v).toHex().toEvmSignature()
    }

    fun getBalance(walletAddress: EvmAddress, symbol: SymbolEntity): BigDecimal =
        runBlocking { asyncGetBalance(walletAddress, symbol) }

    suspend fun asyncGetBalance(walletAddress: EvmAddress, symbol: SymbolEntity): BigDecimal =
        (
            symbol.contractAddress
                ?.let { contractAddress ->
                    when (contractAddress) {
                        is EvmAddress -> asyncGetERC20Balance(contractAddress, walletAddress)
                        is BitcoinAddress -> BigInteger.ZERO
                    }
                }
                ?: asyncGetNativeBalance(walletAddress)
            ).fromFundamentalUnits(symbol.decimals)

    fun getNativeBalance(address: EvmAddress): BigInteger =
        web3j.ethGetBalance(address.value, DefaultBlockParameter.valueOf("latest")).send().balance

    suspend fun asyncGetNativeBalance(address: EvmAddress): BigInteger =
        web3j.ethGetBalance(address.value, DefaultBlockParameter.valueOf("latest")).sendAsync().await().balance

    fun getERC20Balance(erc20Address: EvmAddress, walletAddress: EvmAddress): BigInteger =
        runBlocking { asyncGetERC20Balance(erc20Address, walletAddress) }

    suspend fun asyncGetERC20Balance(erc20Address: EvmAddress, walletAddress: EvmAddress): BigInteger =
        loadERC20(erc20Address).balanceOf(walletAddress.value).sendAsync().await()

    fun sendTransaction(address: Address, data: String, amount: BigInteger, nonceOverride: BigInteger? = null): TxHash {
        val txManager = nonceOverride
            ?.let { TransactionManagerWithNonceOverride(web3j, credentials, nonceOverride) }
            ?: transactionManager

        return txManager.sendTransaction(
            web3j.ethGasPrice().send().gasPrice,
            gasProvider.gasLimit,
            address.toString(),
            data,
            amount,
        ).transactionHash.let { TxHash(it) }
    }

    fun sendNativeDepositTx(address: Address, amount: BigInteger, nonceOverride: BigInteger? = null): TxHash =
        sendTransaction(
            address = address,
            data = "",
            amount = amount,
            nonceOverride = nonceOverride,
        )

    fun batchHash(block: DefaultBlockParam): String =
        exchangeContractCall(block, Exchange::batchHash).toHex(false)

    fun lastSettlementBatchHash(block: DefaultBlockParam): String =
        exchangeContractCall(block, Exchange::lastSettlementBatchHash).toHex(false)

    fun lastWithdrawalBatchHash(block: DefaultBlockParam): String =
        exchangeContractCall(block, Exchange::lastWithdrawalBatchHash).toHex(false)

    fun sendMintERC20Tx(
        tokenContractAddress: EvmAddress,
        receiver: EvmAddress,
        amount: BigInteger,
        nonceOverride: BigInteger? = null,
    ): TxHash =
        sendTransaction(
            address = tokenContractAddress,
            data = MockERC20
                .load(tokenContractAddress.value, web3j, transactionManager, gasProvider)
                .mint(receiver.value, amount)
                .encodeFunctionCall(),
            amount = BigInteger.ZERO,
            nonceOverride = nonceOverride,
        )

    fun sendTransferERC20Tx(
        tokenContractAddress: EvmAddress,
        receiver: EvmAddress,
        amount: BigInteger,
        nonceOverride: BigInteger? = null,
    ): TxHash =
        sendTransaction(
            address = tokenContractAddress,
            data = MockERC20
                .load(tokenContractAddress.value, web3j, transactionManager, gasProvider)
                .transfer(receiver.value, amount)
                .encodeFunctionCall(),
            amount = BigInteger.ZERO,
            nonceOverride = nonceOverride,
        )

    fun getFeeAccountAddress(block: DefaultBlockParam): EvmAddress =
        EvmAddress(Keys.toChecksumAddress(exchangeContractCall(block, Exchange::feeAccount)))

    fun getSovereignWithdrawalDelay(block: DefaultBlockParam): BigInteger =
        exchangeContractCall(block, Exchange::sovereignWithdrawalDelay)

    val exchangeContractAddress: Address
        get() = contractMap.getValue(ContractType.Exchange)

    fun encodeSubmitWithdrawalsFunctionCall(withdrawals: List<ByteArray>): String =
        loadExchangeContract(exchangeContractAddress)
            .submitWithdrawals(withdrawals)
            .encodeFunctionCall()

    fun encodeRollbackBatchFunctionCall(): String =
        loadExchangeContract(exchangeContractAddress)
            .rollbackBatch()
            .encodeFunctionCall()

    fun encodePrepareSettlementBatchFunctionCall(batchSettlement: EvmSettlement.Batch): String =
        loadExchangeContract(exchangeContractAddress)
            .prepareSettlementBatch(DefaultFunctionEncoder().encodeParameters(listOf(batchSettlement)).toHexBytes())
            .encodeFunctionCall()

    fun encodeSubmitSettlementBatchFunctionCall(batchSettlement: EvmSettlement.Batch): String =
        loadExchangeContract(exchangeContractAddress)
            .submitSettlementBatch(DefaultFunctionEncoder().encodeParameters(listOf(batchSettlement)).toHexBytes())
            .encodeFunctionCall()
}

class AirdropperEvmClient(private val evmClient: EvmClient) {
    val logger = KotlinLogging.logger {}

    fun testnetChallengeAirdrop(address: Address, tokenSymbol: SymbolEntity, tokenAmount: BigDecimal, gasSymbol: SymbolEntity, gasAmount: BigDecimal): TxHash? {
        val airdropperNonce = BlockchainNonceEntity.getOrCreateForUpdate(evmClient.airdropperAddress, evmClient.chainId)
        var nonce = airdropperNonce.nonce?.let { it + BigInteger.ONE } ?: evmClient.getConsistentNonce(airdropperNonce.key)

        try {
            logger.debug { "Sending $gasAmount ${gasSymbol.name} to $address" }
            val gasAmountInFundamentalUnits = gasAmount.toFundamentalUnits(gasSymbol.decimals)
            evmClient.sendNativeDepositTx(
                address = address,
                amount = gasAmountInFundamentalUnits,
                nonceOverride = nonce,
            )

            logger.debug { "Sending $tokenAmount ${tokenSymbol.name} to $address" }
            val amountInFundamentalUnits = tokenAmount.toFundamentalUnits(tokenSymbol.decimals)
            val tokenContractAddress = tokenSymbol.contractAddress as? EvmAddress
                ?: throw RuntimeException("Only ERC-20s supported for testnet challenge deposit symbol")
            nonce += BigInteger.ONE
            val txHash = evmClient.sendTransferERC20Tx(
                tokenContractAddress = tokenContractAddress,
                receiver = address as EvmAddress,
                amount = amountInFundamentalUnits,
                nonceOverride = nonce,
            )

            airdropperNonce.nonce = nonce

            return txHash
        } catch (e: Exception) {
            logger.error(e) { "Error during testnet challenge airdrop, resetting nonce." }
            airdropperNonce.nonce = null

            return null
        }
    }
}
