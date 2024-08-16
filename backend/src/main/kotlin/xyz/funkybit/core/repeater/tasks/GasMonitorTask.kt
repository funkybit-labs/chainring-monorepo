package xyz.funkybit.core.repeater.tasks

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.blockchain.ChainManager
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.KeyValueStore
import java.math.BigInteger
import kotlin.time.Duration.Companion.seconds

class GasMonitorTask : RepeaterBaseTask(
    invokePeriod = 30.seconds,
) {
    override val name: String = "gas_monitor"

    val logger = KotlinLogging.logger {}

    private var minimumGasUnitsOverride: BigInteger? = null

    override fun setNextInvocationArgs(args: List<String>) {
        minimumGasUnitsOverride = args.first().toBigInteger()
    }

    override fun runWithLock() {
        ChainManager.getBlockchainClients().forEach {
            // set minimum amount to be equivalent to minimum gas units (or 100,000,000 if not specified) at the max
            // priority fee, as this scales well with the actual gas needed
            val minimumGas = (minimumGasUnitsOverride ?: BigInteger.valueOf(100_000_000L)) * it.gasProvider.getMaxPriorityFeePerGas(null)
            val availableGas = it.getNativeBalance(it.submitterAddress)

            transaction {
                if (availableGas < minimumGas) {
                    if (!isWarned(it.chainId)) {
                        logger.error {
                            "Low gas alert for submitter ${it.submitterAddress} on ${it.chainId}: only $availableGas available, need a minimum of $minimumGas"
                        }
                        setWarned(it.chainId, true)
                    }
                } else {
                    setWarned(it.chainId, false)
                }
            }
        }
        minimumGasUnitsOverride = null
    }

    private fun isWarned(chainId: ChainId): Boolean =
        KeyValueStore.getBoolean(warnedFlagStorageKey(chainId))

    private fun setWarned(chainId: ChainId, value: Boolean) {
        KeyValueStore.setBoolean(warnedFlagStorageKey(chainId), value)
    }

    private fun warnedFlagStorageKey(chainId: ChainId): String =
        "GasMonitorWarned:${chainId.value}"
}
