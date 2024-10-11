package xyz.funkybit.apps.ring

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.blockchain.bitcoin.MempoolSpaceApi
import xyz.funkybit.core.blockchain.bitcoin.MempoolSpaceClient
import xyz.funkybit.core.blockchain.bitcoin.bitcoinConfig
import xyz.funkybit.core.model.db.DeployedSmartContractEntity
import xyz.funkybit.core.model.db.DepositEntity
import xyz.funkybit.core.utils.HttpClient
import java.math.BigInteger
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

    private val chainId = bitcoinConfig.chainId
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

            HttpClient.setQuietModeForThread(true)

            while (true) {
                try {
                    Thread.sleep(pollingIntervalInMs)
                    transaction {
                        refreshPendingDeposits(MempoolSpaceClient.getCurrentBlock())
                    }
                } catch (ie: InterruptedException) {
                    logger.warn { "Exiting deposit confirmation handler thread" }
                    return@thread
                } catch (e: Exception) {
                    logger.error(e) { "Unhandled exception handling pending deposits" }
                    Thread.sleep(pollingIntervalInMs)
                }
            }
        }
    }

    private fun refreshPendingDeposits(currentBlockHeight: Long) {
        DepositEntity.getPendingForUpdate(chainId).forEach {
            refreshPendingDeposit(it, currentBlockHeight)
        }
    }

    private fun refreshPendingDeposit(pendingDeposit: DepositEntity, currentBlockHeight: Long) {
        logger.info { "Refreshing pending deposit ${pendingDeposit.guid}, currentBlockHeight=$currentBlockHeight" }
        val tx = MempoolSpaceClient.getTransaction(pendingDeposit.transactionHash)
        if (pendingDeposit.blockNumber == null && tx?.status?.confirmed == true && tx.status.blockHeight != null) {
            pendingDeposit.updateBlockNumber(tx.status.blockHeight.toBigInteger())
        }

        if (tx != null && tx.status.confirmed && currentBlockHeight - tx.status.blockHeight!! + 1 >= numConfirmations) {
            logger.debug { "Marking transaction ${tx.txId} as confirmed" }

            val onChainAmount = onChainDepositAmount(tx)
            if (onChainAmount == BigInteger.ZERO) {
                pendingDeposit.markAsFailed("Invalid transaction", canBeResubmitted = false)
                return
            }

            if (pendingDeposit.amount != onChainAmount) {
                pendingDeposit.amount = onChainAmount
            }

            pendingDeposit.updateBlockNumber(tx.status.blockHeight.toBigInteger())
            pendingDeposit.markAsConfirmed()
        } else if (Clock.System.now() - pendingDeposit.createdAt > maxWaitTime) {
            pendingDeposit.markAsFailed("Deposit not confirmed within $maxWaitTime", canBeResubmitted = true)
        }
    }

    private fun onChainDepositAmount(tx: MempoolSpaceApi.Transaction): BigInteger {
        return tx
            .outputsMatchingWallet(DeployedSmartContractEntity.programBitcoinAddress())
            .sumOf { it.value }
    }
}
