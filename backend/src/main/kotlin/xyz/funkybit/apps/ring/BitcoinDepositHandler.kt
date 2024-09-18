package xyz.funkybit.apps.ring

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.blockchain.bitcoin.BitcoinClient
import xyz.funkybit.core.model.db.DepositEntity
import xyz.funkybit.core.model.db.TxHash
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

class BitcoinDepositHandler(
    private val numConfirmations: Int = System.getenv("BITCOIN_DEPOSIT_HANDLER_NUM_CONFIRMATIONS")?.toIntOrNull() ?: DEFAULT_NUM_CONFIRMATIONS,
    private val pollingIntervalInMs: Long = System.getenv("BITCOIN_DEPOSIT_HANDLER_POLLING_INTERVAL_MS")?.toLongOrNull() ?: 500L,
    private val maxWaitTime: Duration = System.getenv("BITCOIN_DEPOSIT_HANDLER_MAX_WAIT_TIME_HOURS")?.toLongOrNull()?.hours ?: 48.hours,
) {
    companion object {
        const val DEFAULT_NUM_CONFIRMATIONS: Int = 1
    }

    private val chainId = BitcoinClient.chainId
    private var workerThread: Thread? = null
    val logger = KotlinLogging.logger {}

    fun stop() {
        workerThread?.let {
            it.interrupt()
            it.join(100)
        }
    }

    fun start() {
        logger.debug { "Starting bitcoin deposit handler" }

        workerThread = thread(start = true, name = "deposit-confirmation-handler-$chainId", isDaemon = true) {
            logger.debug { "Deposit confirmation handler thread starting for $chainId" }
            while (true) {
                try {
                    Thread.sleep(pollingIntervalInMs)
                    transaction {
                        refreshPendingDeposits()
                    }
                } catch (ie: InterruptedException) {
                    logger.warn { "Exiting deposit confirmation handler thread" }
                    return@thread
                } catch (e: Exception) {
                    logger.error(e) { "Unhandled exception handling pending deposits" }
                }
            }
        }
    }

    private fun refreshPendingDeposits() {
        DepositEntity.getPendingForUpdate(chainId).forEach {
            refreshPendingDeposit(it)
        }
    }

    private fun refreshPendingDeposit(pendingDeposit: DepositEntity) {
        val tx = BitcoinClient.getRawTransaction(TxHash(pendingDeposit.transactionHash.value))
        if (pendingDeposit.blockNumber == null && tx?.confirmations == 1) {
            pendingDeposit.updateBlockNumber((BitcoinClient.getBlockCount() - 1).toBigInteger())
        }
        if (tx != null && (tx.confirmations ?: 0) >= numConfirmations) {
            logger.debug { "Marking transaction as confirmed ${tx.confirmations}" }
            pendingDeposit.markAsConfirmed()
        } else if (Clock.System.now() - pendingDeposit.createdAt > maxWaitTime) {
            pendingDeposit.markAsFailed("Deposit not confirmed within $maxWaitTime", canBeResubmitted = true)
        }
    }
}
