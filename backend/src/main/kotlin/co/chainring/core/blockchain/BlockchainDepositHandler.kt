package co.chainring.core.blockchain

import co.chainring.core.model.db.DepositEntity
import co.chainring.core.model.db.DepositStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigInteger
import kotlin.concurrent.thread

class BlockchainDepositHandler(
    private val blockchainClient: BlockchainClient,
    private val numConfirmations: Int,
    private val pollingIntervalInMs: Long,
) {

    private val chainId = blockchainClient.chainId
    private var workerThread: Thread? = null
    val logger = KotlinLogging.logger {}

    fun stop() {
        workerThread?.let {
            it.interrupt()
            it.join(100)
        }
    }

    fun start(depositConfirmationCallback: DepositConfirmationCallback) {
        logger.debug { "Starting deposit confirmation handler" }

        workerThread = thread(start = true, name = "deposit-confirmation-handler", isDaemon = true) {
            try {
                logger.debug { "Deposit confirmation handler thread starting" }

                while (true) {
                    Thread.sleep(pollingIntervalInMs)
                    handle(depositConfirmationCallback)
                }
            } catch (ie: InterruptedException) {
                logger.warn { "Exiting deposit confirmation handler thread" }
                return@thread
            } catch (e: Exception) {
                logger.error(e) { "Unhandled exception handling pending deposits" }
            }
        }
    }

    private fun handle(depositConfirmationCallback: DepositConfirmationCallback) {
        val currentBlock = blockchainClient.getBlockNumber()

        val pendingDeposits = transaction {
            DepositEntity.getPending(chainId)
        }

        // handle pending deposits - if we hit required confirmations, invoke callback
        pendingDeposits.forEach { pendingDeposit ->
            blockchainClient.getTransactionReceipt(pendingDeposit.transactionHash.value)?.let { receipt ->
                receipt.blockNumber?.let { blockNumber ->
                    when (receipt.status) {
                        "0x1" -> {
                            val confirmationsReceived = confirmations(currentBlock, blockNumber)
                            if (confirmationsReceived >= numConfirmations) {
                                transaction {
                                    pendingDeposit.update(status = DepositStatus.Confirmed)
                                }

                                try {
                                    depositConfirmationCallback.onExchangeContractDepositConfirmation(pendingDeposit)
                                } catch (e: Exception) {
                                    logger.error(e) { "DepositConfirmationCallback failed for $pendingDeposit" }
                                }

                                transaction {
                                    pendingDeposit.update(status = DepositStatus.Complete)
                                }
                            }
                        }

                        else -> {
                            val error = receipt.revertReason ?: "Unknown Error"
                            logger.error { "Deposit failed with revert reason $error" }

                            transaction {
                                pendingDeposit.update(
                                    status = DepositStatus.Failed,
                                    error = error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun confirmations(currentBlock: BigInteger, startingBlock: BigInteger): Int {
        return (currentBlock - startingBlock).toLong().toInt() + 1
    }
}
