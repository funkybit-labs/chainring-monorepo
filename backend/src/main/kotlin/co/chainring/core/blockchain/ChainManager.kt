package co.chainring.core.blockchain

import co.chainring.core.model.Address
import co.chainring.core.model.db.ChainId
import java.math.BigInteger

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
)

object ChainManager {

    private val chainNames = (System.getenv("EVM_CHAINS") ?: "localhost:8545,localhost:8546").split(",").map { it.trim() }
    val blockchainConfigs = chainNames.map { chainName ->
        BlockchainClientConfig(
            name = chainName,
            url = System.getenv("EVM_NETWORK_URL_$chainName") ?: "http://$chainName",
            blockExplorerNetName = System.getenv("BLOCK_EXPLORER_NET_NAME_$chainName") ?: "ChainRing $chainName",
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
        )
    }

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
