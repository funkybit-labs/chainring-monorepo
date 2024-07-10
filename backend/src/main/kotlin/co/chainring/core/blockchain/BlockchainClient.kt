package co.chainring.core.blockchain

import co.chainring.contracts.generated.ERC1967Proxy
import co.chainring.contracts.generated.ERC20
import co.chainring.contracts.generated.Exchange
import co.chainring.contracts.generated.MockERC20
import co.chainring.contracts.generated.UUPSUpgradeable
import co.chainring.core.evm.BatchSettlement
import co.chainring.core.model.Address
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.TxHash
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.SymbolEntity
import co.chainring.core.model.toEvmSignature
import co.chainring.core.utils.fromFundamentalUnits
import co.chainring.core.utils.toHex
import co.chainring.core.utils.toHexBytes
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.web3j.abi.DefaultFunctionEncoder
import org.web3j.crypto.Credentials
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
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.jvm.optionals.getOrNull

enum class ContractType {
    Exchange,
}

class BlockchainServerException(message: String) : Exception(message)
class BlockchainClientException(message: String) : Exception(message)

open class TransactionManagerWithNonceOverride(
    web3j: Web3j,
    val credentials: Credentials,
    private val nonceOverride: BigInteger,
) : RawTransactionManager(web3j, credentials) {
    override fun getNonce(): BigInteger =
        nonceOverride
}

fun Credentials.checksumAddress(): Address {
    return Address(Keys.toChecksumAddress(this.address))
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

open class BlockchainClient(val config: BlockchainClientConfig) {
    protected val web3jService: HttpService = httpService(config.url, config.enableWeb3jLogging)
    protected val web3j: Web3j = Web3j.build(
        web3jService,
        config.pollingIntervalInMs,
        Async.defaultExecutorService(),
    )
    protected val credentials = Credentials.create(config.privateKeyHex)
    val chainId = ChainId(web3j.ethChainId().send().chainId)
    private val receiptProcessor = PollingTransactionReceiptProcessor(
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
    val submitterAddress: Address = submitterCredentials.checksumAddress()

    val gasProvider = GasProvider(
        contractCreationLimit = config.contractCreationLimit,
        contractInvocationLimit = config.contractInvocationLimit,
        defaultMaxPriorityFeePerGas = config.defaultMaxPriorityFeePerGasInWei,
        chainId = chainId.toLong(),
        web3j = web3j,
    )
    private val contractMap = mutableMapOf<ContractType, Address>()

    companion object {
        val logger = KotlinLogging.logger {}
        fun httpService(url: String, logging: Boolean): HttpService {
            val builder = OkHttpClient.Builder()

            fun shouldLogRpcInvocation(requestBody: String, responseBody: String): Boolean =
                runCatching {
                    val rpcMethod = Json.parseToJsonElement(requestBody).jsonObject["method"]?.jsonPrimitive?.contentOrNull
                    val rpcResult = Json.parseToJsonElement(responseBody).jsonObject["result"]
                    // filter out periodic calls for event logs unless they contain any results
                    if (rpcMethod == "eth_getFilterChanges" && rpcResult?.jsonArray?.isEmpty() == true) {
                        return false
                    } else {
                        return true
                    }
                }.getOrDefault(true)

            if (logging) {
                builder.addInterceptor {
                    val request = it.request()

                    // making a copy of request body since it can be consumed only once
                    val requestBodyCopy = request.newBuilder().build().body?.let { body ->
                        val requestBuffer = Buffer()
                        body.writeTo(requestBuffer)
                        requestBuffer.readUtf8()
                    } ?: ""

                    val response = it.proceed(request)
                    val contentType: MediaType? = response.body?.contentType()
                    val responseBody = response.body?.string()

                    if (shouldLogRpcInvocation(requestBodyCopy, responseBody ?: "")) {
                        logger.debug { "RPC call: url=${request.url}, request=$requestBodyCopy, response code=${response.code}, response body=$responseBody" }
                    }

                    // making a copy of response body since it can be consumed only once
                    response.newBuilder().body(responseBody?.toResponseBody(contentType)).build()
                }
            }
            return HttpService(url, builder.build())
        }
    }

    data class DeployedContract(
        val proxyAddress: Address,
        val implementationAddress: Address,
        val version: Int,
    )

    fun deployOrUpgradeWithProxy(contractType: ContractType, existingProxyAddress: Address?): DeployedContract {
        logger.debug { "Starting deployment for $contractType" }

        val (implementationContractAddress, version) = when (contractType) {
            ContractType.Exchange -> {
                val implementationContract = Exchange.deploy(web3j, transactionManager, gasProvider).send()
                Pair(
                    Address(Keys.toChecksumAddress(implementationContract.contractAddress)),
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
                    ).send()
                }
            }
            Address(Keys.toChecksumAddress(proxyContract.contractAddress))
        }

        logger.debug { "Deployment complete for $contractType" }
        setContractAddress(contractType, proxyAddress)

        return DeployedContract(
            proxyAddress = proxyAddress,
            implementationAddress = implementationContractAddress,
            version = version,
        )
    }

    private fun <A> exchangeContractCall(block: DefaultBlockParam, f: Exchange.() -> RemoteFunctionCall<A>): RemoteFunctionCall<A> {
        val contract = loadExchangeContract(exchangeContractAddress)
        contract.setDefaultBlockParameter(block.toWeb3j())
        val startTime = Clock.System.now()
        while (true) {
            try {
                return f(contract)
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

    suspend fun getExchangeBalance(address: Address, tokenAddress: Address, block: DefaultBlockParam): BigInteger {
        return exchangeContractCall(block) {
            balances(address.value, tokenAddress.value)
        }.sendAsync().await()
    }

    private suspend fun getExchangeBalances(walletAddress: Address, tokenAddresses: List<Address>, block: DefaultBlockParam): Map<Address, BigInteger> =
        tokenAddresses.associateWith { tokenAddress ->
            getExchangeBalance(walletAddress, tokenAddress, block)
        }

    suspend fun getExchangeBalances(walletAddresses: List<Address>, tokenAddresses: List<Address>, block: DefaultBlockParam): Map<Address, Map<Address, BigInteger>> =
        walletAddresses.associateWith { walletAddress ->
            getExchangeBalances(walletAddress, tokenAddresses, block)
        }

    fun loadExchangeContract(address: Address): Exchange {
        return Exchange.load(address.value, web3j, transactionManager, gasProvider)
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
            .ethGetLogs(EthFilter(block.toWeb3j(), block.toWeb3j(), address.value))
            .send()

    fun getExchangeContractLogs(block: BigInteger): EthLog =
        getLogs(DefaultBlockParam.BlockNumber(block), getContractAddress(ContractType.Exchange))

    fun getExchangeContractLogs(block: DefaultBlockParam): EthLog =
        getLogs(block, getContractAddress(ContractType.Exchange))

    fun getTxManager(nonceOverride: BigInteger): TransactionManagerWithNonceOverride {
        return TransactionManagerWithNonceOverride(web3j, submitterCredentials, nonceOverride)
    }

    fun getNonce(address: String): BigInteger {
        return web3j.ethGetTransactionCount(address, DefaultBlockParameterName.PENDING).send().transactionCount
    }

    fun extractRevertReasonFromSimulation(data: String, blockNumber: BigInteger): String? {
        val response = web3j.ethCall(
            Transaction.createEthCallTransaction(
                submitterCredentials.address,
                contractMap[ContractType.Exchange]!!.value,
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

    fun loadERC20(address: Address) = ERC20.load(address.value, web3j, transactionManager, gasProvider)

    fun signData(hash: ByteArray): EvmSignature {
        val signature = Sign.signMessage(hash, credentials.ecKeyPair, false)
        return (signature.r + signature.s + signature.v).toHex().toEvmSignature()
    }

    fun getBalance(walletAddress: Address, symbol: SymbolEntity): BigDecimal =
        runBlocking { asyncGetBalance(walletAddress, symbol) }

    suspend fun asyncGetBalance(walletAddress: Address, symbol: SymbolEntity): BigDecimal =
        (
            symbol.contractAddress
                ?.let { contractAddress -> asyncGetERC20Balance(contractAddress, walletAddress) }
                ?: asyncGetNativeBalance(walletAddress)
            ).fromFundamentalUnits(symbol.decimals)

    fun getNativeBalance(address: Address): BigInteger =
        runBlocking { asyncGetNativeBalance(address) }

    suspend fun asyncGetNativeBalance(address: Address): BigInteger =
        web3j.ethGetBalance(address.value, DefaultBlockParameter.valueOf("latest")).sendAsync().await().balance

    fun getERC20Balance(erc20Address: Address, walletAddress: Address): BigInteger =
        runBlocking { asyncGetERC20Balance(erc20Address, walletAddress) }

    suspend fun asyncGetERC20Balance(erc20Address: Address, walletAddress: Address): BigInteger =
        loadERC20(erc20Address).balanceOf(walletAddress.value).sendAsync().await()

    fun sendTransaction(address: Address, data: String, amount: BigInteger): TxHash =
        transactionManager.sendTransaction(
            web3j.ethGasPrice().send().gasPrice,
            gasProvider.gasLimit,
            address.value,
            data,
            amount,
        ).transactionHash.let { TxHash(it) }

    fun asyncDepositNative(address: Address, amount: BigInteger): TxHash =
        sendTransaction(address, "", amount)

    fun batchHash(block: BigInteger): String =
        exchangeContractCall(DefaultBlockParam.BlockNumber(block), Exchange::batchHash).send().toHex(false)

    fun batchHash(block: DefaultBlockParam): String =
        exchangeContractCall(block, Exchange::batchHash).send().toHex(false)

    fun lastSettlementBatchHash(block: DefaultBlockParam): String =
        exchangeContractCall(block, Exchange::lastSettlementBatchHash).send().toHex(false)

    fun lastWithdrawalBatchHash(block: DefaultBlockParam): String =
        exchangeContractCall(block, Exchange::lastWithdrawalBatchHash).send().toHex(false)

    fun asyncMintERC20(
        tokenContractAddress: Address,
        receiver: Address,
        amount: BigInteger,
    ): TxHash =
        sendTransaction(
            tokenContractAddress,
            MockERC20
                .load(tokenContractAddress.value, web3j, transactionManager, gasProvider)
                .mint(receiver.value, amount)
                .encodeFunctionCall(),
            BigInteger.ZERO,
        )

    fun getFeeAccountAddress(block: DefaultBlockParam): Address =
        Address(Keys.toChecksumAddress(exchangeContractCall(block, Exchange::feeAccount).send()))

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

    fun encodePrepareSettlementBatchFunctionCall(batchSettlement: BatchSettlement): String =
        loadExchangeContract(exchangeContractAddress)
            .prepareSettlementBatch(DefaultFunctionEncoder().encodeParameters(listOf(batchSettlement)).toHexBytes())
            .encodeFunctionCall()

    fun encodeSubmitSettlementBatchFunctionCall(batchSettlement: BatchSettlement): String =
        loadExchangeContract(exchangeContractAddress)
            .submitSettlementBatch(DefaultFunctionEncoder().encodeParameters(listOf(batchSettlement)).toHexBytes())
            .encodeFunctionCall()
}
