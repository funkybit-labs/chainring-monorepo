package xyz.funkybit.apps.ring

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import xyz.funkybit.core.blockchain.BlockchainClient
import xyz.funkybit.core.model.db.BalanceChange
import xyz.funkybit.core.model.db.BalanceEntity
import xyz.funkybit.core.model.db.BalanceType
import xyz.funkybit.core.model.db.DepositEntity
import xyz.funkybit.core.model.db.DepositStatus
import xyz.funkybit.core.model.db.DepositTable
import xyz.funkybit.core.model.db.TestnetChallengeStatus
import xyz.funkybit.core.sequencer.SequencerClient
import xyz.funkybit.core.utils.TestnetChallengeUtils
import xyz.funkybit.core.utils.toFundamentalUnits
import xyz.funkybit.sequencer.core.Asset
import java.math.BigInteger
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class BlockchainDepositHandler(
    private val blockchainClient: BlockchainClient,
    private val sequencerClient: SequencerClient,
    private val numConfirmations: Int = System.getenv("BLOCKCHAIN_DEPOSIT_HANDLER_NUM_CONFIRMATIONS")?.toIntOrNull() ?: DEFAULT_NUM_CONFIRMATIONS,
    private val pollingIntervalInMs: Long = System.getenv("BLOCKCHAIN_DEPOSIT_HANDLER_POLLING_INTERVAL_MS")?.toLongOrNull() ?: 500L,
    private val receiptMaxWaitTime: Duration = System.getenv("BLOCKCHAIN_DEPOSIT_HANDLER_RECEIPT_MAX_WAIT_TIME_MS")?.toLongOrNull()?.milliseconds ?: 10.minutes,
) {
    companion object {
        const val DEFAULT_NUM_CONFIRMATIONS: Int = 2
    }

    private val chainId = blockchainClient.chainId
    private var workerThread: Thread? = null
    val logger = KotlinLogging.logger {}

    fun stop() {
        workerThread?.let {
            it.interrupt()
            it.join(100)
        }
    }

    fun start() {
        logger.debug { "Starting deposit confirmation handler" }

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
        val pendingDeposits = DepositEntity.getPendingForUpdate(chainId)
        val confirmedDeposits = DepositEntity.getConfirmedForUpdate(chainId)

        if (pendingDeposits.isNotEmpty()) {
            val currentBlock = blockchainClient.getBlockNumber()

            // handle pending deposits - if we hit required confirmations, invoke callback
            pendingDeposits.forEach {
                refreshPendingDeposit(it, currentBlock)
            }
        }

        if (confirmedDeposits.isNotEmpty()) {
            confirmedDeposits.forEach(::sendToSequencer)
            if (TestnetChallengeUtils.enabled) {
                confirmedDeposits.forEach { deposit ->
                    if (listOf(TestnetChallengeStatus.PendingDeposit, TestnetChallengeStatus.PendingDepositConfirmation).contains(deposit.wallet.user.testnetChallengeStatus) &&
                        deposit.symbol.name == TestnetChallengeUtils.depositSymbolName &&
                        deposit.amount == TestnetChallengeUtils.depositAmount.toFundamentalUnits(TestnetChallengeUtils.depositSymbol().decimals)
                    ) {
                        deposit.wallet.user.testnetChallengeStatus = TestnetChallengeStatus.Enrolled
                    }
                }
            }
        }
    }

    private fun refreshPendingDeposit(pendingDeposit: DepositEntity, currentBlock: BigInteger) {
        val receipt = blockchainClient.getTransactionReceipt(pendingDeposit.transactionHash.value)
        if (receipt == null) {
            if (Clock.System.now() - pendingDeposit.createdAt > receiptMaxWaitTime) {
                pendingDeposit.markAsFailed("Transaction receipt not found", canBeResubmitted = true)
            }
        } else {
            receipt.blockNumber?.let { blockNumber ->
                when (receipt.status) {
                    "0x1" -> {
                        val confirmationsReceived = confirmations(currentBlock, blockNumber)
                        if (confirmationsReceived >= numConfirmations) {
                            BalanceEntity.updateBalances(
                                listOf(BalanceChange.Delta(pendingDeposit.wallet.id.value, pendingDeposit.symbol.guid.value, pendingDeposit.amount)),
                                BalanceType.Exchange,
                            )

                            pendingDeposit.markAsConfirmed()
                        }

                        if (pendingDeposit.blockNumber == null) {
                            pendingDeposit.updateBlockNumber(blockNumber)
                        }
                    }
                    else -> {
                        val error = receipt.revertReason ?: "Unknown Error"
                        logger.error { "Deposit failed with revert reason $error" }

                        pendingDeposit.markAsFailed(error)
                    }
                }
            }
        }
    }

    private fun sendToSequencer(deposit: DepositEntity) {
        try {
            runBlocking {
                sequencerClient.deposit(deposit.wallet.sequencerId.value, Asset(deposit.symbol.name), deposit.amount, deposit.guid.value)
            }
            // Updating like this in case SequencerResponseProcessor sets status to Complete before we commit this transaction
            DepositTable.update(
                where = {
                    DepositTable.guid.eq(deposit.guid).and(DepositTable.status.eq(DepositStatus.Confirmed))
                },
                body = {
                    it[status] = DepositStatus.SentToSequencer
                },
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to notify Sequencer about deposit ${deposit.guid}" }
        }
    }

    private fun confirmations(currentBlock: BigInteger, startingBlock: BigInteger): Int {
        return (currentBlock - startingBlock).toLong().toInt() + 1
    }
}
