package xyz.funkybit.core.repeater.tasks

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.blockchain.bitcoin.bitcoinConfig
import xyz.funkybit.core.blockchain.evm.EvmChainManager
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.db.KeyValueStore
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.services.UtxoManager
import xyz.funkybit.core.utils.HttpClient
import xyz.funkybit.core.utils.TestnetChallengeUtils
import xyz.funkybit.core.utils.toFundamentalUnits
import java.math.BigInteger
import kotlin.time.Duration.Companion.minutes

data class BalancesMonitorConfig(
    val testnetChallengeMinEnrollments: BigInteger = System.getenv("TESTNET_CHALLENGE_MIN_ENROLLMENTS_THRESHOLD")?.toBigInteger() ?: 1_000.toBigInteger(),
    val bitcoinNetworkFeePayerMinGas: BigInteger = System.getenv("BITCOIN_NETWORK_FEE_PAYER_MIN_GAS_THRESHOLD")?.toBigInteger() ?: 10_000_000.toBigInteger(),
)

class BalancesMonitorTask(val config: BalancesMonitorConfig = BalancesMonitorConfig()) : RepeaterBaseTask(
    invokePeriod = 1.minutes,
) {
    override val name: String = "balances_monitor"

    val logger = KotlinLogging.logger {}

    private data class InvocationArgsOverride(val mode: String, val value: BigInteger)
    private var nextInvocationArgs: InvocationArgsOverride? = null

    override fun setNextInvocationArgs(args: List<String>) {
        nextInvocationArgs = InvocationArgsOverride(mode = args.first(), value = args.drop(1).first().toBigInteger())
    }

    override fun runWithLock() {
        try {
            HttpClient.setQuietModeForThread(true)

            val args = nextInvocationArgs
            when (args?.mode) {
                "evm_submitter" -> {
                    checkSubmittersGas(gasUnits = args.value)
                }

                "testnet_challenge" -> {
                    checkTestnetChallengeAirdropperBalance(minEnrollments = args.value)
                }

                "bitcoin_fee_payer" -> {
                    checkBitcoinFeePayerGas(minimumGas = args.value)
                }

                else -> {
                    checkSubmittersGas()

                    if (TestnetChallengeUtils.enabled) {
                        checkTestnetChallengeAirdropperBalance()
                    }

                    if (bitcoinConfig.enabled) {
                        checkBitcoinFeePayerGas()
                    }
                }
            }
        } finally {
            nextInvocationArgs = null
            HttpClient.setQuietModeForThread(false)
        }
    }

    private fun checkBitcoinFeePayerGas(minimumGas: BigInteger = config.bitcoinNetworkFeePayerMinGas) {
        val availableGas = transaction { UtxoManager.getUnspentTotal(bitcoinConfig.feePayerAddress) }

        handleLowBalanceWarning(availableGas, minimumGas, notificationKey = "${0}:bitcoin_fee_payer_gas") {
            "Low gas alert for bitcoin fee payer ${bitcoinConfig.feePayerAddress}: only $availableGas available, need a minimum of $minimumGas"
        }
    }

    private fun checkSubmittersGas(gasUnits: BigInteger = BigInteger.valueOf(100_000_000L)) {
        EvmChainManager.getEvmClients().forEach {
            // set minimum amount to be equivalent to minimum gas units (or 100,000,000 if not specified) at the max
            // priority fee, as this scales well with the actual gas needed
            val minimumGas = gasUnits * it.gasProvider.getMaxPriorityFeePerGas(null)
            val availableGas = it.getNativeBalance(it.submitterAddress)

            handleLowBalanceWarning(availableGas, minimumGas, notificationKey = "${it.chainId}:evm_submitter_gas") {
                "Low gas alert for submitter ${it.submitterAddress} on ${it.chainId}: only $availableGas available, need a minimum of $minimumGas"
            }
        }
    }

    private fun checkTestnetChallengeAirdropperBalance(minEnrollments: BigInteger = config.testnetChallengeMinEnrollments) {
        val depositSymbol = transaction { TestnetChallengeUtils.depositSymbol() }
        val gasSymbol = transaction { SymbolEntity.forChainAndContractAddress(depositSymbol.chainId.value, null) }
        val chainId = depositSymbol.chainId.value

        val evmClient = EvmChainManager.getEvmClient(chainId)

        // check gas balance
        val minimumGas = (minEnrollments.toBigDecimal() * TestnetChallengeUtils.gasDepositAmount).toFundamentalUnits(gasSymbol.decimals)
        val availableGas = evmClient.getNativeBalance(evmClient.airdropperAddress)
        handleLowBalanceWarning(availableGas, minimumGas, notificationKey = "$chainId:testnet_challenge_gas") {
            "Low gas alert for airdropper testnet challenge ${evmClient.airdropperAddress} on $chainId: only $availableGas available, need a minimum of $minimumGas"
        }

        // check ERC20 balance
        val minimumUSDC =
            (minEnrollments.toBigDecimal() * TestnetChallengeUtils.depositAmount).toFundamentalUnits(depositSymbol.decimals)
        val availableUSDC = evmClient.getERC20Balance(
            depositSymbol.contractAddress as EvmAddress,
            evmClient.airdropperAddress,
        )
        handleLowBalanceWarning(availableUSDC, minimumUSDC, notificationKey = "$chainId:testnet_challenge_erc20") {
            "Low ${depositSymbol.name} alert for airdropper testnet challenge ${evmClient.airdropperAddress} on $chainId: only $availableUSDC available, need a minimum of $minimumUSDC"
        }
    }

    private fun handleLowBalanceWarning(
        availableGas: BigInteger,
        minimumGas: BigInteger,
        notificationKey: String,
        lowBalanceErrorMessage: () -> String,
    ) {
        transaction {
            if (availableGas < minimumGas) {
                if (!isWarned(notificationKey)) {
                    logger.error { lowBalanceErrorMessage() }
                    setWarned(notificationKey, true)
                }
            } else {
                setWarned(notificationKey, false)
            }
        }
    }

    private fun isWarned(notificationKey: String): Boolean =
        KeyValueStore.getBoolean(warnedFlagStorageKey(notificationKey))

    private fun setWarned(notificationKey: String, value: Boolean) =
        KeyValueStore.setBoolean(warnedFlagStorageKey(notificationKey), value)

    private fun warnedFlagStorageKey(notificationKey: String): String = "GasMonitorWarned:$notificationKey"
}
