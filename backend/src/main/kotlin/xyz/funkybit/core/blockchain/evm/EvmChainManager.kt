package xyz.funkybit.core.blockchain.evm

import xyz.funkybit.core.model.db.ChainId
import java.math.BigInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

object EvmChainManager {
    private val chainNames = (System.getenv("EVM_CHAINS") ?: "localhost:8545,localhost:8546").split(",").map { it.trim() }
    val evmClientConfigs = chainNames.map { chainName ->
        EvmClientConfig(
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
            airdropperPrivateKeyHex = stringValue(
                chainName,
                "EVM_AIRDROPPER_PRIVATE_KEY",
                "0xc664badcbc1824995c98407e26667e35c648312061a2de44569851f79b0a5371",
            ),
            faucetPrivateKeyHex = stringValue(
                chainName,
                "EVM_FAUCET_PRIVATE_KEY",
                "0x497a24f8d565f1776e7c943e1607d735181d1fc21007fb69dabac1e1e7a641a0",
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

    private val evmClientsByChainId: Map<ChainId, EvmClient> by lazy {
        evmClientConfigs.associate {
            val evmClient = EvmClient(it)
            evmClient.chainId to evmClient
        }
    }

    fun getEvmClients() = evmClientsByChainId.values.toList()

    fun getEvmClient(chainId: ChainId): EvmClient =
        evmClientsByChainId.getValue(chainId)

    fun getEvmClient(chainId: ChainId, privateKeyHex: String) = EvmClient(
        evmClientsByChainId.getValue(chainId).config.copy(privateKeyHex = privateKeyHex),
    )

    fun getAirdropperEvmClient(chainId: ChainId): AirdropperEvmClient {
        return evmClientsByChainId.getValue(chainId).let {
            AirdropperEvmClient(
                EvmClient(it.config.copy(privateKeyHex = it.config.airdropperPrivateKeyHex)),
            )
        }
    }

    fun getFaucetEvmClient(chainId: ChainId): EvmClient? {
        return evmClientsByChainId[chainId]?.let {
            EvmClient(it.config.copy(privateKeyHex = it.config.faucetPrivateKeyHex))
        }
    }

    fun getEvmClient(config: EvmClientConfig, privateKeyHex: String) = EvmClient(
        config.copy(privateKeyHex = privateKeyHex),
    )

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
