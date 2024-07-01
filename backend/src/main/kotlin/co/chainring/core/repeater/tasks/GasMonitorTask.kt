package co.chainring.core.repeater.tasks

import co.chainring.core.blockchain.ChainManager
import co.chainring.core.model.db.ChainId
import io.github.oshai.kotlinlogging.KotlinLogging
import java.math.BigInteger
import kotlin.time.Duration.Companion.seconds

val warned = mutableSetOf<ChainId>()

class GasMonitorTask() : RepeaterBaseTask(
    invokePeriod = 30.seconds,
) {
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
            if (availableGas < minimumGas) {
                if (!warned.contains(it.chainId)) {
                    logger.error {
                        "Low gas alert for submitter ${it.submitterAddress} on ${it.chainId}: only $availableGas available, need a minimum of $minimumGas"
                    }
                    warned.add(it.chainId)
                }
            } else {
                warned.remove(it.chainId)
            }
        }
        minimumGasUnitsOverride = null
    }
}
