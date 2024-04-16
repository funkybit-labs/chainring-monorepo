package co.chainring.core.blockchain

import co.chainring.contracts.generated.ERC1967Proxy
import co.chainring.contracts.generated.Exchange
import co.chainring.contracts.generated.UUPSUpgradeable
import co.chainring.core.evm.EIP712Transaction
import co.chainring.core.model.Address
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.DeployedSmartContractEntity
import co.chainring.core.model.db.DepositEntity
import co.chainring.core.model.db.SymbolEntity
import co.chainring.core.services.TxConfirmationCallback
import io.github.oshai.kotlinlogging.KotlinLogging
import io.reactivex.Flowable
import kotlinx.coroutines.future.await
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.jetbrains.exposed.sql.transactions.transaction
import org.web3j.crypto.Credentials
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.EthFilter
import org.web3j.protocol.http.HttpService
import org.web3j.tx.Contract
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.response.PollingTransactionReceiptProcessor
import java.math.BigInteger
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

enum class ContractType {
    Exchange,
}

data class BlockchainClientConfig(
    val url: String = System.getenv("EVM_NETWORK_URL") ?: "http://localhost:8545",
    val privateKeyHex: String =
        System.getenv(
            "EVM_CONTRACT_MANAGEMENT_PRIVATE_KEY",
        ) ?: "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80",
    val submitterPrivateKeyHex: String =
        System.getenv(
            "EVM_SUBMITTER_PRIVATE_KEY",
        ) ?: "0x8b3a350cf5c34c9194ca85829a2df0ec3153be0318b5e2d3348e872092edffba",
    val feeAccountAddress: String =
        System.getenv(
            "EVM_FEE_ACCOUNT_ADDRESS",
        ) ?: "0x9965507D1a55bcC2695C58ba16FB37d819B0A4dc",
    val deploymentPollingIntervalInMs: Long = longValue("DEPLOYMENT_POLLING_INTERVAL_MS", 1000L),
    val maxPollingAttempts: Long = longValue("MAX_POLLING_ATTEMPTS", 120L),
    val contractCreationLimit: BigInteger = bigIntegerValue("CONTRACT_CREATION_LIMIT", BigInteger.valueOf(5_000_000)),
    val contractInvocationLimit: BigInteger = bigIntegerValue("CONTRACT_INVOCATION_LIMIT", BigInteger.valueOf(1_000_000)),
    val defaultMaxPriorityFeePerGasInWei: BigInteger = bigIntegerValue("DEFAULT_MAX_PRIORITY_FEE_PER_GAS_WEI", BigInteger.valueOf(5_000_000_000)),
    val enableWeb3jLogging: Boolean = (System.getenv("ENABLE_WEB3J_LOGGING") ?: "true") == "true",
) {
    companion object {
        fun longValue(name: String, defaultValue: Long): Long {
            return try {
                System.getenv(name)?.toLong() ?: defaultValue
            } catch (e: Exception) {
                defaultValue
            }
        }

        fun bigIntegerValue(name: String, defaultValue: BigInteger): BigInteger {
            return try {
                System.getenv(name)?.let { BigInteger(it) } ?: defaultValue
            } catch (e: Exception) {
                defaultValue
            }
        }
    }
}

interface DepositConfirmationCallback {
    fun onExchangeContractDepositConfirmation(event: Exchange.DepositEventResponse)
}

open class BlockchainClient(private val config: BlockchainClientConfig = BlockchainClientConfig()) {

    protected val web3j = Web3j.build(httpService(config.url, config.enableWeb3jLogging))
    protected val credentials = Credentials.create(config.privateKeyHex)
    val chainId = ChainId(web3j.ethChainId().send().chainId)
    private val receiptProcessor = PollingTransactionReceiptProcessor(
        web3j,
        config.deploymentPollingIntervalInMs,
        config.maxPollingAttempts.toInt(),
    )
    protected val transactionManager = RawTransactionManager(
        web3j,
        credentials,
        chainId.value.toLong(),
        receiptProcessor,
    )
    protected val submitterCredentials = Credentials.create(config.submitterPrivateKeyHex)
    protected val submitterTransactionManager = RawTransactionManager(
        web3j,
        submitterCredentials,
        chainId.value.toLong(),
        receiptProcessor,
    )
    private var submitterWorkerThread: Thread? = null
    private val txQueue = LinkedBlockingQueue<List<EIP712Transaction>>(10)

    protected val gasProvider = GasProvider(
        contractCreationLimit = config.contractCreationLimit,
        contractInvocationLimit = config.contractInvocationLimit,
        defaultMaxPriorityFeePerGas = config.defaultMaxPriorityFeePerGasInWei,
        chainId = chainId.toLong(),
        web3j = web3j,
    )
    private val contractMap = mutableMapOf<ContractType, Address>()

    private lateinit var exchangeContract: Exchange

    private lateinit var txConfirmationCallback: TxConfirmationCallback

    companion object {

        val logger = KotlinLogging.logger {}
        fun httpService(url: String, logging: Boolean): HttpService {
            val builder = OkHttpClient.Builder()
            if (logging) {
                builder.addInterceptor {
                    val request = it.request()
                    val requestCopy = request.newBuilder().build()
                    val requestBuffer = Buffer()
                    requestCopy.body?.writeTo(requestBuffer)
                    logger.debug {
                        ">> ${request.method} ${request.url} | ${requestBuffer.readUtf8()}"
                    }
                    val response = it.proceed(request)
                    val contentType: MediaType? = response.body!!.contentType()
                    val content = response.body!!.string()
                    logger.debug {
                        "<< ${response.code} | $content"
                    }
                    response.newBuilder().body(content.toResponseBody(contentType)).build()
                }
            }
            return HttpService(url, builder.build())
        }
    }

    fun updateContracts() {
        transaction {
            ContractType.entries.forEach { contractType ->
                val deployedContract = DeployedSmartContractEntity
                    .findLastDeployedContractByNameAndChain(contractType.name, chainId)

                if (deployedContract == null) {
                    logger.info { "Deploying contract: $contractType" }
                    deployOrUpgradeWithProxy(contractType, null)
                } else if (deployedContract.deprecated) {
                    logger.info { "Upgrading contract: $contractType" }
                    deployOrUpgradeWithProxy(contractType, deployedContract.proxyAddress)
                } else {
                    contractMap[contractType] = deployedContract.proxyAddress
                }
            }
            exchangeContract = loadExchangeContract(contractMap[ContractType.Exchange]!!)
        }
    }

    private fun deployOrUpgradeWithProxy(
        contractType: ContractType,
        existingProxyAddress: Address?,
    ) {
        logger.debug { "Starting deployment for $contractType" }
        val (implementationContractAddress, version) =
            when (contractType) {
                ContractType.Exchange -> {
                    val implementationContract = Exchange.deploy(web3j, transactionManager, gasProvider).send()
                    Pair(
                        Address(implementationContract.contractAddress),
                        implementationContract.version.send().toInt(),
                    )
                }
            }
        val proxyAddress =
            if (existingProxyAddress != null) {
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
                val proxyContract =
                    ERC1967Proxy.deploy(
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
                            transaction {
                                SymbolEntity.forChain(chainId)
                                    .firstOrNull { it.contractAddress == null }?.decimals?.toInt()?.toBigInteger()
                                    ?: BigInteger("18")
                            },
                        ).send()
                    }
                }
                Address(proxyContract.contractAddress)
            }

        logger.debug { "Creating db entry for $contractType" }
        contractMap[contractType] = proxyAddress
        DeployedSmartContractEntity.create(
            name = contractType.name,
            chainId = chainId,
            implementationAddress = implementationContractAddress,
            proxyAddress = proxyAddress,
            version = version,
        )
        logger.debug { "Deployment complete for $contractType" }
    }

    suspend fun getExchangeBalance(address: Address, tokenAddress: Address): BigInteger {
        return if (tokenAddress == Address.zero) {
            exchangeContract.nativeBalances(address.value).sendAsync().await()
        } else {
            exchangeContract.balances(address.value, tokenAddress.value).sendAsync().await()
        }
    }

    suspend fun getExchangeBalances(walletAddress: Address, tokenAddresses: List<Address>): Map<Address, BigInteger> =
        tokenAddresses.associateWith { tokenAddress ->
            getExchangeBalance(walletAddress, tokenAddress)
        }

    suspend fun getExchangeBalances(walletAddresses: List<Address>, tokenAddresses: List<Address>): Map<Address, Map<Address, BigInteger>> =
        walletAddresses.associateWith { walletAddress ->
            getExchangeBalances(walletAddress, tokenAddresses)
        }

    fun loadExchangeContract(address: Address): Exchange {
        return Exchange.load(address.value, web3j, transactionManager, gasProvider)
    }

    fun getContractAddress(contractType: ContractType) = contractMap[contractType]

    fun queueTransactions(txs: List<EIP712Transaction>) {
        if (txs.isNotEmpty()) {
            txQueue.put(txs)
        }
    }

    fun startTransactionSubmitter(txConfirmationCallback: TxConfirmationCallback, pendingTransactions: List<EIP712Transaction>) {
        this.txConfirmationCallback = txConfirmationCallback
        logger.debug { "Starting transaction submitter" }
        val exchange = Exchange.load(contractMap[ContractType.Exchange]!!.value, web3j, submitterTransactionManager, gasProvider)
        submitterWorkerThread = thread(start = true, name = "transaction-processor", isDaemon = true) {
            try {
                logger.debug { "Transaction submitter thread starting" }
                while (true) {
                    val txs = txQueue.take()

                    val error = try {
                        logger.debug { "Submitting ${txs.size} transactions" }
                        try {
                            exchange.submitTransactions(txs.map { it.getTxData() }).send()
                            null
                        } catch (e: org.web3j.protocol.exceptions.TransactionException) {
                            e.transactionReceipt.get().revertReason
                        }
                    } catch (e: Exception) {
                        logger.error(e) { "Unhandled exception submitting tx" }
                        "Unhandled error ${e.message}"
                    }

                    txs.forEach { tx ->
                        try {
                            logger.debug { "Finished processing tx - ${error?.let { "failed with $error" } ?: "success"}" }
                            txConfirmationCallback.onTxConfirmation(tx, error)
                        } catch (e: Exception) {
                            logger.error(e) { "Unhandled exception updating tx" }
                        }
                    }
                }
            } catch (ie: InterruptedException) {
                logger.warn { "existing blockchain handler" }
                return@thread
            } catch (e: Exception) {
                logger.error(e) { "Unhandled exception submitting tx" }
            }
        }
        txQueue.put(pendingTransactions)
    }

    fun stopTransactionSubmitter() {
        txQueue.clear()
        submitterWorkerThread?.let {
            it.interrupt()
            it.join(100)
        }
    }

    fun registerDepositEventsConsumer(depositConfirmationCallback: DepositConfirmationCallback) {
        val exchangeContract = loadExchangeContract(contractMap[ContractType.Exchange]!!)

        val startFromBlock = maxSeenBlockNumber()
            ?: System.getenv("EVM_NETWORK_EARLIEST_BLOCK")?.let { DefaultBlockParameter.valueOf(it.toBigInteger()) }
            ?: DefaultBlockParameterName.EARLIEST

        val filter = EthFilter(startFromBlock, DefaultBlockParameterName.LATEST, exchangeContract.contractAddress)

        web3j.ethLogFlowable(filter)
            .retryWhen { f: Flowable<Throwable> -> f.take(5).delay(300, TimeUnit.MILLISECONDS) }
            .subscribe(
                { eventLog ->
                    // listen to all events of the exchange contract and manually check for DEPOSIT_EVENT
                    // exchangeContract.depositEventFlowable(filter) fails with null pointer on any other event form the contract
                    if (Contract.staticExtractEventParameters(Exchange.DEPOSIT_EVENT, eventLog) != null) {
                        val depositEventResponse = Exchange.getDepositEventFromLog(eventLog)
                        logger.debug { "Received deposit event (from: ${depositEventResponse.from}, amount: ${depositEventResponse.amount}, token: ${depositEventResponse.token}, txHash: ${depositEventResponse.log.transactionHash})" }
                        depositConfirmationCallback.onExchangeContractDepositConfirmation(depositEventResponse)
                    }
                },
                { throwable: Throwable ->
                    logger.error(throwable) { "Unexpected error occurred while processing deposit events" }

                    registerDepositEventsConsumer(depositConfirmationCallback)
                },
            )
    }

    private fun maxSeenBlockNumber() = transaction {
        DepositEntity.maxBlockNumber()?.let { DefaultBlockParameter.valueOf(it) }
    }
}
