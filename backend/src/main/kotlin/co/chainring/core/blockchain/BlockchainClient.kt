package co.chainring.core.blockchain

import co.chainring.contracts.generated.ERC1967Proxy
import co.chainring.contracts.generated.Exchange
import co.chainring.contracts.generated.UUPSUpgradeable
import co.chainring.core.model.Address
import co.chainring.core.model.db.ChainId
import io.github.oshai.kotlinlogging.KotlinLogging
import io.reactivex.Flowable
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.web3j.crypto.Credentials
import org.web3j.crypto.Keys
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.EthFilter
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.core.methods.response.Log
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.protocol.http.HttpService
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.response.PollingTransactionReceiptProcessor
import org.web3j.utils.Async
import java.math.BigInteger

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
    val deploymentPollingIntervalInMs: Long = System.getenv("DEPLOYMENT_POLLING_INTERVAL_MS")?.toLongOrNull() ?: 1000L,
    val maxPollingAttempts: Long = System.getenv("MAX_POLLING_ATTEMPTS")?.toLongOrNull() ?: 120L,
    val contractCreationLimit: BigInteger = System.getenv("CONTRACT_CREATION_LIMIT")?.toBigIntegerOrNull() ?: BigInteger.valueOf(5_000_000),
    val contractInvocationLimit: BigInteger = System.getenv("CONTRACT_INVOCATION_LIMIT")?.toBigIntegerOrNull() ?: BigInteger.valueOf(3_000_000),
    val defaultMaxPriorityFeePerGasInWei: BigInteger = System.getenv("DEFAULT_MAX_PRIORITY_FEE_PER_GAS_WEI")?.toBigIntegerOrNull() ?: BigInteger.valueOf(5_000_000_000),
    val enableWeb3jLogging: Boolean = (System.getenv("ENABLE_WEB3J_LOGGING") ?: "true") == "true",
)

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

open class BlockchainClient(private val config: BlockchainClientConfig = BlockchainClientConfig()) {
    protected val web3jService: HttpService = httpService(config.url, config.enableWeb3jLogging)
    protected val web3j: Web3j = Web3j.build(
        web3jService,
        System.getenv("EVM_NETWORK_POLLING_INTERVAL")?.toLong() ?: 1000L,
        Async.defaultExecutorService(),
    )
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
    val submitterAddress: Address = submitterCredentials.checksumAddress()

    val gasProvider = GasProvider(
        contractCreationLimit = config.contractCreationLimit,
        contractInvocationLimit = config.contractInvocationLimit,
        defaultMaxPriorityFeePerGas = config.defaultMaxPriorityFeePerGasInWei,
        chainId = chainId.toLong(),
        web3j = web3j,
    )
    private val contractMap = mutableMapOf<ContractType, Address>()

    private lateinit var exchangeContract: Exchange

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

    sealed class DeployContractParams {
        abstract val contractType: ContractType

        data class Exchange(
            val nativePrecision: BigInteger,
        ) : DeployContractParams() {
            override val contractType: ContractType = ContractType.Exchange
        }
    }

    data class DeployedContract(
        val proxyAddress: Address,
        val implementationAddress: Address,
        val version: Int,
    )

    fun deployOrUpgradeWithProxy(params: DeployContractParams, existingProxyAddress: Address?): DeployedContract {
        logger.debug { "Starting deployment for ${params.contractType}" }

        val (implementationContractAddress, version) = when (params) {
            is DeployContractParams.Exchange -> {
                val implementationContract = Exchange.deploy(web3j, transactionManager, gasProvider).send()
                Pair(
                    Address(Keys.toChecksumAddress(implementationContract.contractAddress)),
                    implementationContract.version.send().toInt(),
                )
            }
        }

        val proxyAddress = if (existingProxyAddress != null) {
            // for now call upgradeTo here
            logger.debug { "Calling upgradeTo for ${params.contractType}" }
            UUPSUpgradeable.load(
                existingProxyAddress.value,
                web3j,
                transactionManager,
                gasProvider,
            ).upgradeToAndCall(implementationContractAddress.value, ByteArray(0), BigInteger.ZERO).send()
            existingProxyAddress
        } else {
            // deploy the proxy and call the initialize method in contract - this can only be called once
            logger.debug { "Deploying proxy for ${params.contractType}" }
            val proxyContract = ERC1967Proxy.deploy(
                web3j,
                transactionManager,
                gasProvider,
                BigInteger.ZERO,
                implementationContractAddress.value,
                ByteArray(0),
            ).send()
            logger.debug { "Deploying initialize for ${params.contractType}" }
            when (params) {
                is DeployContractParams.Exchange -> {
                    Exchange.load(
                        proxyContract.contractAddress,
                        web3j,
                        transactionManager,
                        gasProvider,
                    ).initialize(
                        submitterCredentials.address,
                        config.feeAccountAddress,
                        params.nativePrecision,
                    ).send()
                }
            }
            Address(Keys.toChecksumAddress(proxyContract.contractAddress))
        }

        logger.debug { "Deployment complete for ${params.contractType}" }
        setContractAddress(params.contractType, proxyAddress)

        return DeployedContract(
            proxyAddress = proxyAddress,
            implementationAddress = implementationContractAddress,
            version = version,
        )
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

    fun setContractAddress(contractType: ContractType, address: Address) {
        contractMap[contractType] = address
        if (contractType == ContractType.Exchange) {
            exchangeContract = loadExchangeContract(address)
        }
    }

    fun getContractAddress(contractType: ContractType): Address? =
        contractMap[contractType]

    fun getTransactionReceipt(txHash: String): TransactionReceipt? {
        val receipt = web3j.ethGetTransactionReceipt(txHash).send().transactionReceipt
        return if (receipt.isPresent) {
            return receipt.get()
        } else {
            null
        }
    }

    open fun getBlockNumber(): BigInteger {
        return web3j.ethBlockNumber().send().blockNumber
    }

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

    fun ethLogFlowable(filter: EthFilter): Flowable<Log> =
        web3j.ethLogFlowable(filter)
}
