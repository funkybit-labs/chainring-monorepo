package xyz.funkybit.core.blockchain

import org.bitcoinj.core.ECKey
import org.bitcoinj.core.NetworkParameters
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.utils.schnorr.Point
import xyz.funkybit.core.utils.toHexBytes
import java.math.BigInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

data class BlockchainClientConfig(
    val name: String,
    val url: String,
    val blockExplorerNetName: String,
    val blockExplorerUrl: String,
    val privateKeyHex: String,
    val submitterPrivateKeyHex: String,
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

enum class SmartFeeMode {
    ECONOMICAL,
    CONSERVATIVE,
}

data class FeeEstimationSettings(
    val blocks: Int,
    val mode: SmartFeeMode,
    val minValue: Int,
    val maxValue: Int,
)

data class BitcoinBlockchainClientConfig(
    val enabled: Boolean,
    val url: String,
    val net: String,
    val enableBasicAuth: Boolean,
    val user: String,
    val password: String,
    val feeSettings: FeeEstimationSettings,
    val blockExplorerNetName: String,
    val blockExplorerUrl: String,
    val faucetAddress: String?,
    val privateKey: ByteArray,
    val changeDustThreshold: BigInteger,
    val feeAccountAddress: BitcoinAddress,
) {
    val params: NetworkParameters = NetworkParameters.fromID(net)!!
    val submitterEcKey: ECKey = ECKey.fromPrivate(privateKey)
    val submitterAddress = BitcoinAddress.fromKey(params, submitterEcKey)
    val submitterXOnlyPublicKey = Point.genPubKey(privateKey)
}

object ChainManager {

    private val chainNames = (System.getenv("EVM_CHAINS") ?: "localhost:8545,localhost:8546").split(",").map { it.trim() }
    val blockchainConfigs = chainNames.map { chainName ->
        BlockchainClientConfig(
            name = chainName,
            url = System.getenv("EVM_NETWORK_URL_$chainName") ?: "http://$chainName",
            blockExplorerNetName = System.getenv("BLOCK_EXPLORER_NET_NAME_$chainName") ?: "funkybit $chainName",
            blockExplorerUrl = System.getenv("BLOCK_EXPLORER_URL_$chainName") ?: "http://$chainName",
            privateKeyHex = stringValue(
                chainName,
                "EVM_CONTRACT_MANAGEMENT_PRIVATE_KEY",
                "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80",
            ),
            submitterPrivateKeyHex = stringValue(
                chainName,
                "EVM_SUBMITTER_PRIVATE_KEY",
                "0x8b3a350cf5c34c9194ca85829a2df0ec3153be0318b5e2d3348e872092edffba",
            ),
            feeAccountAddress = stringValue(
                chainName,
                "EVM_FEE_ACCOUNT_ADDRESS",
                "0x9965507D1a55bcC2695C58ba16FB37d819B0A4dc",
            ),
            pollingIntervalInMs = longValue(
                chainName,
                "POLLING_INTERVAL_MS",
                "1000",
            ),
            maxPollingAttempts = longValue(
                chainName,
                "MAX_POLLING_ATTEMPTS",
                "120",
            ),
            contractCreationLimit = bigIntegerValue(
                chainName,
                "CONTRACT_CREATION_LIMIT",
                "5000000",
            ),
            contractInvocationLimit = bigIntegerValue(
                chainName,
                "CONTRACT_INVOCATION_LIMIT",
                "3000000",
            ),
            defaultMaxPriorityFeePerGasInWei = bigIntegerValue(
                chainName,
                "DEFAULT_MAX_PRIORITY_FEE_PER_GAS_WEI",
                "5000000000",
            ),
            enableWeb3jLogging = (System.getenv("ENABLE_WEB3J_LOGGING") ?: "true") == "true",
            maxRpcNodeEventualConsistencyTolerance = System.getenv("MAX_RPC_NODE_EVENTUAL_CONSISTENCE_TOLERANCE_MS")?.toLongOrNull()?.milliseconds ?: 1.minutes,
            sovereignWithdrawalDelaySeconds = bigIntegerValue(chainName, "SOVEREIGN_WITHDRAWAL_DELAY_SECONDS", "604800"),
        )
    }
    val bitcoinBlockchainClientConfig = BitcoinBlockchainClientConfig(
        enabled = (System.getenv("BITCOIN_NETWORK_ENABLED") ?: "true").toBoolean(),
        url = System.getenv("BITCOIN_NETWORK_RPC_URL") ?: "http://localhost:18443",
        net = System.getenv("BITCOIN_NETWORK_NAME") ?: "org.bitcoin.regtest",
        enableBasicAuth = (System.getenv("BITCOIN_NETWORK_ENABLE_BASIC_AUTH") ?: "true").toBoolean(),
        user = System.getenv("BITCOIN_NETWORK_RPC_USER") ?: "user",
        password = System.getenv("BITCOIN_NETWORK_RPC_PASSWORD") ?: "password",
        feeSettings = FeeEstimationSettings(1, SmartFeeMode.CONSERVATIVE, 5, 50),
        blockExplorerNetName = System.getenv("BLOCK_EXPLORER_NET_NAME_BITCOIN") ?: "Bitcoin Network",
        blockExplorerUrl = System.getenv("BLOCK_EXPLORER_URL_BITCOIN") ?: "http://localhost:1080",
        faucetAddress = "bcrt1q3nyukkpkg6yj0y5tj6nj80dh67m30p963mzxy7",
        privateKey = (System.getenv("BITCOIN_SUBMITTER_PRIVATE_KEY") ?: "0x7ebc626d01c2d916c61dffee4ed2501f579009ad362360d82fcc30e3d8746cec").toHexBytes(),
        changeDustThreshold = BigInteger(System.getenv("BITCOIN_CHANGE_DUST_THRESHOLD") ?: "300"),
        feeAccountAddress = BitcoinAddress(System.getenv("BITCOIN_FEE_ACCOUNT_ADDRESS") ?: "bcrt1qdca3sam9mldju3ssryrrcmjvd8pgnw30ccaggx"),
        /* privKey for bcrt1qdca3sam9mldju3ssryrrcmjvd8pgnw30ccaggx = 0x614293096668ec4c26ede066b1570edc6bd6d663701dffb8247b63e7a3b0f566 */
    )

    private val blockchainClientsByChainId: Map<ChainId, BlockchainClient> by lazy {
        blockchainConfigs.associate {
            val blockchainClient = BlockchainClient(it)
            blockchainClient.chainId to blockchainClient
        }
    }

    fun getBlockchainClients() = blockchainClientsByChainId.values.toList()

    fun getBlockchainClient(chainId: ChainId): BlockchainClient =
        blockchainClientsByChainId.getValue(chainId)

    fun getBlockchainClient(chainId: ChainId, privateKeyHex: String) = BlockchainClient(
        blockchainClientsByChainId.getValue(chainId).config.copy(privateKeyHex = privateKeyHex),
    )

    fun getBlockchainClient(config: BlockchainClientConfig, privateKeyHex: String) = BlockchainClient(
        config.copy(privateKeyHex = privateKeyHex),
    )

    fun getContractAddress(chainId: ChainId, contractType: ContractType): Address =
        blockchainClientsByChainId.getValue(chainId).getContractAddress(contractType)

    private fun stringValue(chainName: String, paramName: String, defaultValue: String): String {
        return System.getenv(
            "${paramName}_$chainName",
        ) ?: System.getenv(
            paramName,
        ) ?: defaultValue
    }

    private fun longValue(chainName: String, paramName: String, defaultValue: String): Long {
        return try {
            stringValue(chainName, paramName, defaultValue).toLong()
        } catch (e: Exception) {
            defaultValue.toLong()
        }
    }

    private fun bigIntegerValue(chainName: String, paramName: String, defaultValue: String): BigInteger {
        return try {
            stringValue(chainName, paramName, defaultValue).toBigInteger()
        } catch (e: Exception) {
            defaultValue.toBigInteger()
        }
    }
}
