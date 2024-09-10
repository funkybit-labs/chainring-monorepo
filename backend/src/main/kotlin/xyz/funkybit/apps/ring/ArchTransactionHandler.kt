package xyz.funkybit.apps.ring

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import xyz.funkybit.core.blockchain.bitcoin.ArchNetworkClient
import xyz.funkybit.core.blockchain.bitcoin.BitcoinClient
import xyz.funkybit.core.model.bitcoin.ArchAccountState
import xyz.funkybit.core.model.db.ArchAccountBalanceIndexEntity
import xyz.funkybit.core.model.db.ArchAccountBalanceIndexStatus
import xyz.funkybit.core.model.db.ArchAccountEntity
import xyz.funkybit.core.model.db.BalanceChange
import xyz.funkybit.core.model.db.BalanceEntity
import xyz.funkybit.core.model.db.BalanceType
import xyz.funkybit.core.model.db.BlockchainTransactionData
import xyz.funkybit.core.model.db.BlockchainTransactionEntity
import xyz.funkybit.core.model.db.CreateArchAccountBalanceIndexAssignment
import xyz.funkybit.core.model.db.DepositEntity
import xyz.funkybit.core.model.db.DepositStatus
import xyz.funkybit.core.model.db.DepositTable
import xyz.funkybit.core.model.db.UpdateArchAccountBalanceIndexAssignment
import xyz.funkybit.core.model.rpc.ArchNetworkRpc
import xyz.funkybit.core.sequencer.SequencerClient
import xyz.funkybit.core.sequencer.toSequencerId
import xyz.funkybit.core.utils.bitcoin.ArchUtils
import xyz.funkybit.core.utils.toHex
import xyz.funkybit.sequencer.core.Asset
import kotlin.concurrent.thread

class ArchTransactionHandler(
    private val sequencerClient: SequencerClient,
    private val activePollingIntervalInMs: Long = System.getenv("ARCH_TX_HANDLER_ACTIVE_POLLING_INTERVAL_MS")?.toLongOrNull() ?: 1000L,
    private val inactivePollingIntervalInMs: Long = System.getenv("ARCH_TX_HANDLER_INACTIVE_POLLING_INTERVAL_MS")?.toLongOrNull() ?: 2000L,
    private val failurePollingIntervalInMs: Long = System.getenv("ARCH_TX_HANDLER_FAILURE_POLLING_INTERVAL_MS")?.toLongOrNull() ?: 5000L,
) {
    private val chainId = BitcoinClient.chainId
    private var workerThread: Thread? = null
    val logger = KotlinLogging.logger {}

    fun start() {
        logger.debug { "Starting batch transaction handler for $chainId" }
        workerThread = thread(start = true, name = "batch-transaction-handler-$chainId", isDaemon = true) {
            logger.debug { "Batch Transaction handler thread starting" }
            val programPubkey = transaction { ArchAccountEntity.findProgramAccount() }!!.rpcPubkey()

            var txInProgress: Boolean

            while (true) {
                try {
                    txInProgress = transaction {
                        processBalanceIndexBatch(programPubkey) ||
                            processDepositBatch(programPubkey)
                    }

                    Thread.sleep(
                        if (txInProgress) activePollingIntervalInMs else inactivePollingIntervalInMs,
                    )
                } catch (ie: InterruptedException) {
                    logger.warn { "Exiting blockchain handler" }
                    return@thread
                } catch (e: Exception) {
                    logger.error(e) { "Unhandled exception submitting tx" }
                    Thread.sleep(failurePollingIntervalInMs)
                }
            }
        }
    }

    fun stop() {
        workerThread?.let {
            it.interrupt()
            it.join(100)
        }
    }

    private fun processBalanceIndexBatch(programPubkey: ArchNetworkRpc.Pubkey): Boolean {
        val assigningBalanceIndexUpdates = ArchAccountBalanceIndexEntity.findAllForStatus(ArchAccountBalanceIndexStatus.Assigning)
        return if (assigningBalanceIndexUpdates.isNotEmpty()) {
            assigningBalanceIndexUpdates.firstOrNull()?.entity?.archTransaction?.let { archTransaction ->
                ArchNetworkClient.getProcessedTransaction(archTransaction.txHash!!)?.let { processedTx ->
                    if (processedTx.status == ArchNetworkRpc.Status.Processed) {
                        archTransaction.markAsCompleted()
                        val assignments = assigningBalanceIndexUpdates.groupBy { it.archAccountAddress }.flatMap { (pubkey, updates) ->
                            val tokenAccountState: ArchAccountState.Token = ArchUtils.getAccountState(pubkey)
                            val balances = tokenAccountState.balances.reversed()
                            updates.map { update ->
                                val index = balances.indexOfFirst { it.walletAddress == update.walletAddress.value }
                                if (index == -1) {
                                    // throw an error since we did not find it
                                    throw Exception("Did not find an index for wallet ${update.walletAddress.value}")
                                }
                                UpdateArchAccountBalanceIndexAssignment(
                                    update.entity,
                                    balances.size - index - 1,
                                )
                            }
                        }
                        ArchAccountBalanceIndexEntity.updateToAssigned(assignments)
                    }
                }
            }
            true
        } else {
            val pendingBalanceIndexUpdates = ArchAccountBalanceIndexEntity.findAllForStatus(ArchAccountBalanceIndexStatus.Pending)
            if (pendingBalanceIndexUpdates.isNotEmpty()) {
                logger.debug { "Initiating ${pendingBalanceIndexUpdates.size} index updates" }
                val instruction = ArchUtils.buildInitTokenBalanceIndexBatchInstruction(programPubkey, pendingBalanceIndexUpdates)
                val transaction = BlockchainTransactionEntity.create(
                    chainId = chainId,
                    transactionData = BlockchainTransactionData(instruction.serialize().toHex(), programPubkey.toContractAddress()),
                    batchHash = null,
                )
                transaction.flush()
                ArchAccountBalanceIndexEntity.updateToAssigning(pendingBalanceIndexUpdates.map { it.entity }, transaction)
                submitToArch(transaction, instruction)
                true
            } else {
                false
            }
        }
    }

    private fun processDepositBatch(programPubkey: ArchNetworkRpc.Pubkey): Boolean {
        val settlingDeposits = DepositEntity.getSettlingForUpdate(chainId)
        return if (settlingDeposits.isNotEmpty()) {
            // handle settling deposits
            settlingDeposits.first().archTransaction?.let { archTransaction ->
                ArchNetworkClient.getProcessedTransaction(archTransaction.txHash!!)?.let {
                    if (it.status == ArchNetworkRpc.Status.Processed) {
                        archTransaction.markAsCompleted()
                        BalanceEntity.updateBalances(
                            settlingDeposits.map { deposit ->
                                BalanceChange.Delta(deposit.wallet.id.value, deposit.symbol.guid.value, deposit.amount)
                            },
                            BalanceType.Exchange,
                        )
                        settlingDeposits.forEach(::sendToSequencer)
                    }
                }
            }
            true
        } else {
            val confirmedDeposits = DepositEntity.getConfirmedBitcoinDeposits()

            // handle deposits with an already assigned balance index
            val confirmedDepositsWithAssignedIndex = confirmedDeposits.filter {
                it.balanceIndexStatus == ArchAccountBalanceIndexStatus.Assigned
            }
            val hasConfirmedDeposits = if (confirmedDepositsWithAssignedIndex.isNotEmpty()) {
                logger.debug { "Handling ${confirmedDepositsWithAssignedIndex.size} confirmed deposits" }
                val instruction = ArchUtils.buildDepositBatchInstruction(programPubkey, confirmedDepositsWithAssignedIndex)
                val transaction = BlockchainTransactionEntity.create(
                    chainId = chainId,
                    transactionData = BlockchainTransactionData(instruction.serialize().toHex(), programPubkey.toContractAddress()),
                    batchHash = null,
                )
                transaction.flush()
                DepositEntity.updateToSettling(confirmedDepositsWithAssignedIndex.map { it.depositEntity }, transaction)
                submitToArch(transaction, instruction)
                true
            } else {
                false
            }

            // for deposits with no balance index, initiate index creation
            val confirmedDepositsWithoutAssignedIndex = confirmedDeposits.filter { it.balanceIndexStatus == null }
            if (confirmedDepositsWithoutAssignedIndex.isNotEmpty()) {
                logger.debug { "Handling ${confirmedDepositsWithoutAssignedIndex.size} confirmed deposits with no index" }
                ArchAccountBalanceIndexEntity.batchCreate(
                    confirmedDepositsWithoutAssignedIndex.map {
                        CreateArchAccountBalanceIndexAssignment(
                            walletGuid = it.depositEntity.walletGuid,
                            archAccountGuid = it.archAccountEntity.guid,
                        )
                    }.toSet().toList(),
                )
                true
            } else {
                hasConfirmedDeposits
            }
        }
    }

    private fun sendToSequencer(deposit: DepositEntity) {
        try {
            runBlocking {
                sequencerClient.deposit(deposit.wallet.userGuid.value.toSequencerId(), Asset(deposit.symbol.name), deposit.amount, deposit.guid.value)
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

    private fun submitToArch(transaction: BlockchainTransactionEntity, instruction: ArchNetworkRpc.Instruction) {
        transaction.markAsSubmitted(ArchUtils.signAndSendInstruction(instruction))
    }
}
