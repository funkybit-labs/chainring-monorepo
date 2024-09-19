package xyz.funkybit.apps.ring

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import xyz.funkybit.core.blockchain.bitcoin.ArchNetworkClient
import xyz.funkybit.core.blockchain.bitcoin.ArchNetworkClient.MAX_INSTRUCTION_SIZE
import xyz.funkybit.core.blockchain.bitcoin.BitcoinClient
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.bitcoin.ArchAccountState
import xyz.funkybit.core.model.db.ArchAccountBalanceIndexEntity
import xyz.funkybit.core.model.db.ArchAccountBalanceIndexStatus
import xyz.funkybit.core.model.db.ArchAccountEntity
import xyz.funkybit.core.model.db.BalanceChange
import xyz.funkybit.core.model.db.BalanceEntity
import xyz.funkybit.core.model.db.BalanceType
import xyz.funkybit.core.model.db.BlockchainTransactionData
import xyz.funkybit.core.model.db.BlockchainTransactionEntity
import xyz.funkybit.core.model.db.BlockchainTransactionStatus
import xyz.funkybit.core.model.db.BroadcasterNotification
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.ChainSettlementBatchEntity
import xyz.funkybit.core.model.db.CreateArchAccountBalanceIndexAssignment
import xyz.funkybit.core.model.db.DeployedSmartContractEntity
import xyz.funkybit.core.model.db.DepositEntity
import xyz.funkybit.core.model.db.DepositStatus
import xyz.funkybit.core.model.db.DepositTable
import xyz.funkybit.core.model.db.SettlementBatchStatus
import xyz.funkybit.core.model.db.TradeEntity
import xyz.funkybit.core.model.db.UpdateArchAccountBalanceIndexAssignment
import xyz.funkybit.core.model.db.WithdrawalEntity
import xyz.funkybit.core.model.db.WithdrawalStatus
import xyz.funkybit.core.model.db.publishBroadcasterNotifications
import xyz.funkybit.core.model.rpc.ArchNetworkRpc
import xyz.funkybit.core.sequencer.SequencerClient
import xyz.funkybit.core.sequencer.toSequencerId
import xyz.funkybit.core.services.UtxoSelectionService
import xyz.funkybit.core.utils.bitcoin.ArchUtils
import xyz.funkybit.core.utils.bitcoin.BitcoinInsufficientFundsException
import xyz.funkybit.core.utils.toHex
import xyz.funkybit.core.utils.tryAcquireAdvisoryLock
import xyz.funkybit.sequencer.core.Asset
import xyz.funkybit.sequencer.proto.SequencerError
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

class ArchTransactionHandler(
    private val sequencerClient: SequencerClient,
    private val confirmationThreshold: Int = System.getenv("BITCOIN_TX_HANDLER_CONFIRMATION_THRESHOLD")?.toIntOrNull() ?: 1,
    private val pollingInterval: Duration = (System.getenv("ARCH_TX_HANDLER_ACTIVE_POLLING_INTERVAL_MS")?.toLongOrNull() ?: 1000L).milliseconds,
    private val failurePollingInterval: Duration = (System.getenv("ARCH_TX_HANDLER_FAILURE_POLLING_INTERVAL_MS")?.toLongOrNull() ?: 3000L).milliseconds,
    private val batchMinWithdrawals: Int = System.getenv("ARCH_WITHDRAWAL_SETTLEMENT_BATCH_MIN_WITHDRAWALS")?.toIntOrNull() ?: 1,
    private val batchMaxInterval: Duration = (System.getenv("ARCH_WITHDRAWAL_SETTLEMENT_BATCH_MAX_WAIT_MS")?.toLongOrNull() ?: 1000).milliseconds,
    private val maxWaitTime: Duration = System.getenv("BITCOIN_TRANSACTION_HANDLER_MAX_WAIT_TIME_HOURS")?.toLongOrNull()?.hours ?: 48.hours,

) {
    private val chainId = BitcoinClient.chainId
    private var archWorkerThread: Thread? = null
    private var bitcoinWorkerThread: Thread? = null
    val logger = KotlinLogging.logger {}

    private lateinit var programAccount: ArchAccountEntity
    private lateinit var programStateAccount: ArchAccountEntity
    private lateinit var programBitcoinAddress: BitcoinAddress

    fun start() {
        logger.debug { "Starting batch transaction handler for $chainId" }
        archWorkerThread = thread(start = true, name = "arch-transaction-handler-$chainId", isDaemon = true) {
            logger.debug { "Arch Transaction handler thread starting" }
            transaction {
                programAccount = ArchAccountEntity.findProgramAccount()!!
                programStateAccount = ArchAccountEntity.findProgramStateAccount()!!
                programBitcoinAddress = DeployedSmartContractEntity.programBitcoinAddress()

                val inProgressSettlementBatch = ChainSettlementBatchEntity.findInProgressBatch(chainId)
                if (inProgressSettlementBatch == null && batchInProgressOnChain()) {
                    logger.debug { "rolling back on chain on startup" }
                    rollbackOnChain()
                }
            }

            var settlementBatchInProgress = false
            var withdrawalBatchInProgress = false

            while (true) {
                try {
                    transaction {
                        if (tryAcquireAdvisoryLock(chainId.value.toLong())) {
                            processBalanceIndexBatch()
                            processDepositBatch()
                        }
                    }
                    if (withdrawalBatchInProgress) {
                        withdrawalBatchInProgress = transaction {
                            if (tryAcquireAdvisoryLock(chainId.value.toLong())) {
                                processWithdrawalBatch()
                            } else {
                                withdrawalBatchInProgress(chainId)
                            }
                        }
                    } else {
                        settlementBatchInProgress = transaction {
                            if (tryAcquireAdvisoryLock(chainId.value.toLong())) {
                                processSettlementBatch()
                            } else {
                                settlementBatchInProgress(chainId)
                            }
                        }
                        if (!settlementBatchInProgress) {
                            withdrawalBatchInProgress = transaction {
                                if (tryAcquireAdvisoryLock(chainId.value.toLong())) {
                                    processWithdrawalBatch()
                                } else {
                                    withdrawalBatchInProgress(chainId)
                                }
                            }
                        }
                    }

                    Thread.sleep(pollingInterval.inWholeMilliseconds)
                } catch (ie: InterruptedException) {
                    logger.warn { "Exiting arch blockchain handler" }
                    return@thread
                } catch (e: Exception) {
                    logger.error(e) { "Unhandled exception in arch handler" }
                    Thread.sleep(failurePollingInterval.inWholeMilliseconds)
                }
            }
        }

        bitcoinWorkerThread = thread(start = true, name = "bitcoin-transaction-handler", isDaemon = true) {
            logger.debug { "Bitcoin Transaction handler thread starting" }

            while (true) {
                try {
                    transaction {
                        processWithdrawalsSettlingOnBitcoin()
                    }
                    Thread.sleep(pollingInterval.inWholeMilliseconds)
                } catch (ie: InterruptedException) {
                    logger.warn { "Exiting bitcoin blockchain handler" }
                    return@thread
                } catch (e: Exception) {
                    logger.error(e) { "Unhandled exception in bitcoin handler" }
                    Thread.sleep(failurePollingInterval.inWholeMilliseconds)
                }
            }
        }
    }

    fun stop() {
        archWorkerThread?.let {
            it.interrupt()
            it.join(100)
        }
        bitcoinWorkerThread?.let {
            it.interrupt()
            it.join(100)
        }
    }

    private fun processBalanceIndexBatch(): Boolean {
        val programPubkey = programAccount.rpcPubkey()
        val assigningBalanceIndexUpdates = ArchAccountBalanceIndexEntity.findAllForStatus(ArchAccountBalanceIndexStatus.Assigning)
        return if (assigningBalanceIndexUpdates.isNotEmpty()) {
            assigningBalanceIndexUpdates.firstOrNull()?.entity?.archTransaction?.let { archTransaction ->
                ArchNetworkClient.getProcessedTransaction(archTransaction.txHash!!)?.let { processedTx ->
                    if (processedTx.status == ArchNetworkRpc.Status.Processed) {
                        Thread.sleep(2000)
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

    private fun settlementBatchInProgress(chainId: ChainId) =
        ChainSettlementBatchEntity.findInProgressBatch(chainId) != null

    private fun withdrawalBatchInProgress(chainId: ChainId) =
        WithdrawalEntity.findSettling(chainId).isNotEmpty()

    private fun processDepositBatch(): Boolean {
        val programPubkey = programAccount.rpcPubkey()
        val settlingDeposits = DepositEntity.getSettlingForUpdate(chainId)
        return if (settlingDeposits.isNotEmpty()) {
            // handle settling deposits
            settlingDeposits.first().archTransaction?.let { archTransaction ->
                ArchNetworkClient.getProcessedTransaction(archTransaction.txHash!!)?.let {
                    if (it.status == ArchNetworkRpc.Status.Processed) {
                        Thread.sleep(2000)
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

    private fun processWithdrawalBatch(): Boolean {
        val programPubkey = programAccount.rpcPubkey()
        if (settlementBatchInProgress(chainId)) {
            return false
        }
        val settlingWithdrawals = WithdrawalEntity.findSettlingOnArch()
        logger.debug { "processWithdrawalBatch count = ${settlingWithdrawals.size}" }
        return if (settlingWithdrawals.isNotEmpty()) {
            val archTransaction = settlingWithdrawals.first().archTransaction!!
            ArchNetworkClient.getProcessedTransaction(archTransaction.txHash!!)?.let { processedTx ->
                if (processedTx.status == ArchNetworkRpc.Status.Processed) {
                    Thread.sleep(2000)
                    archTransaction.markAsCompleted()
                    if (processedTx.bitcoinTxIds.isNotEmpty()) {
                        val bitcoinTxId = processedTx.bitcoinTxIds.first()
                        val transaction = BlockchainTransactionEntity.create(
                            chainId = chainId,
                            transactionData = BlockchainTransactionData("", EvmAddress.zero),
                            batchHash = null,
                            txHash = bitcoinTxId,
                        )
                        transaction.flush()
                        WithdrawalEntity.updateToSettling(settlingWithdrawals, transaction, emptyMap())
                    } else {
                        settlingWithdrawals.forEach {
                            onWithdrawalCompleteOnBitcoin(it, "No bitcoin transaction returned")
                        }
                    }
                }
            }
            true
        } else {
            createNextWithdrawalBatch(programBitcoinAddress, programPubkey)
        }
    }

    private fun processWithdrawalsSettlingOnBitcoin(): Boolean {
        val settlingWithdrawals = WithdrawalEntity.findSettlingOnBitcoin()
        return if (settlingWithdrawals.isNotEmpty()) {
            settlingWithdrawals.groupBy { it.blockchainTransactionGuid!! }.forEach { (blockchainTransactionGuid, withdrawals) ->
                val blockchainTransaction = BlockchainTransactionEntity[blockchainTransactionGuid]
                val bitcoinTx = BitcoinClient.getRawTransaction(blockchainTransaction.txHash!!)
                if (bitcoinTx != null) {
                    if ((bitcoinTx.confirmations ?: 0) >= confirmationThreshold) {
                        blockchainTransaction.markAsCompleted()
                        withdrawals.forEach { withdrawal ->
                            onWithdrawalCompleteOnBitcoin(withdrawal, null)
                        }
                    }
                } else {
                    withdrawals.forEach { withdrawal ->
                        if (Clock.System.now() - (withdrawal.updatedAt ?: withdrawal.createdAt) > maxWaitTime) {
                            // TODO: CHAIN-510 - need to rollback here - both Arch and sequencer - rollback on arch is similar
                            // to doing a batch deposit to put balances back which should update sequencer on completion
                            withdrawal.status = WithdrawalStatus.Failed
                        }
                    }
                }
            }
            true
        } else {
            false
        }
    }

    private fun processSettlementBatch(): Boolean {
        val inProgressBatch = ChainSettlementBatchEntity.findInProgressBatch(chainId)
            ?: return false

        return when (inProgressBatch.status) {
            SettlementBatchStatus.Preparing -> {
                when (inProgressBatch.preparationTx.status) {
                    BlockchainTransactionStatus.Pending -> {
                        // send prepare transaction call
                        submitToArch(inProgressBatch.preparationTx)
                    }
                    BlockchainTransactionStatus.Submitted -> {
                        refreshSubmittedTransaction(
                            tx = inProgressBatch.preparationTx,
                        ) { _, error ->
                            if (error == null) {
                                val onChainBatchHash = getOnChainBatchHash()
                                if (onChainBatchHash != inProgressBatch.preparationTx.batchHash) {
                                    inProgressBatch.markAsFailed("Batch hash mismatch for settlement batch ${inProgressBatch.guid.value}, on chain value: $onChainBatchHash, db value: ${inProgressBatch.preparationTx.batchHash}")
                                } else {
                                    inProgressBatch.markAsPrepared()
                                }
                            } else {
                                inProgressBatch.markAsFailed(error)
                            }
                        }
                    }
                    else -> {}
                }
                true
            }
            SettlementBatchStatus.Submitting -> {
                submitBatch(inProgressBatch)
                true
            }

            SettlementBatchStatus.RollingBack -> {
                rollbackBatch(inProgressBatch)
                true
            }
            SettlementBatchStatus.Submitted -> {
                inProgressBatch.submissionTx?.let { submissionTx ->
                    refreshSubmittedTransaction(
                        tx = submissionTx,
                    ) { _, error ->
                        if (error == null) {
                            inProgressBatch.markAsCompleted()
                        } else {
                            inProgressBatch.markAsFailed(error)
                        }
                    }
                }
                true
            }

            else -> false
        }
    }

    private fun submitBatch(currentBatch: ChainSettlementBatchEntity) {
        currentBatch.submissionTx?.let { submissionTx ->
            submitToArch(submissionTx)
            if (submissionTx.status == BlockchainTransactionStatus.Submitted) {
                currentBatch.markAsSubmitted()
            }
        }
    }

    private fun rollbackBatch(currentBatch: ChainSettlementBatchEntity) {
        // rollback if a batch in progress on chain already
        if (batchInProgressOnChain()) {
            currentBatch.rollbackTx?.let { rollbackTx ->
                logger.debug { "Submitting rollback Tx " }
                submitToArch(rollbackTx)
                if (rollbackTx.status == BlockchainTransactionStatus.Submitted) {
                    currentBatch.markAsRolledBack()
                }
            }
        } else {
            currentBatch.markAsRolledBack()
        }
    }

    private fun batchInProgressOnChain() = getOnChainBatchHash().isNotEmpty()

    private fun getOnChainBatchHash(): String {
        return ArchUtils.getAccountState<ArchAccountState.Program>(programStateAccount.rpcPubkey()).settlementBatchHash
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

    private fun submitToArch(transaction: BlockchainTransactionEntity) {
        submitToArch(
            transaction,
            Json.decodeFromString(transaction.transactionData.data),
        )
    }

    private fun rollbackOnChain() {
        ArchUtils.signAndSendInstruction(ArchUtils.buildRollbackSettlementBatchInstruction(programPubkey = programAccount.rpcPubkey()))
    }

    private fun createNextWithdrawalBatch(programBitcoinAddress: BitcoinAddress, programPubkey: ArchNetworkRpc.Pubkey): Boolean {
        var limit = 10

        while (true) {
            val sequencedWithdrawals =
                WithdrawalEntity.findSequencedArchWithdrawals(limit, TradeEntity.minResponseSequenceForPending())
            if (sequencedWithdrawals.isEmpty()) {
                return false
            }

            val now = Clock.System.now()
            val earliestSequencedWithdrawal =
                sequencedWithdrawals.minBy { it.withdrawalEntity.createdAt }.withdrawalEntity.createdAt
            if (sequencedWithdrawals.size < batchMinWithdrawals && earliestSequencedWithdrawal + batchMaxInterval > now) {
                logger.debug { "Skipping create withdrawal batch. ${sequencedWithdrawals.size} pending withdrawals, max age ${(now - earliestSequencedWithdrawal).inWholeMilliseconds}ms" }
                return false
            }

            val (instruction, selectedUtxos) = try {
                ArchUtils.buildWithdrawBatchInstruction(
                    programBitcoinAddress,
                    programPubkey,
                    sequencedWithdrawals,
                )
            } catch (e: BitcoinInsufficientFundsException) {
                logger.warn(e) { "There are not enough funds to process a withdrawal batch of ${sequencedWithdrawals.size}" }
                if (sequencedWithdrawals.size == 1) {
                    return false
                }
                limit = sequencedWithdrawals.size - 1
                continue
            }
            val serializedInstruction = instruction.serialize()
            logger.debug { "numWithdrawals = ${sequencedWithdrawals.size} instruction size = ${serializedInstruction.size} numUtxos = ${selectedUtxos.size}" }
            if (serializedInstruction.size > MAX_INSTRUCTION_SIZE) {
                if (sequencedWithdrawals.size == 1) {
                    logger.error { "Failed trying to fit a single withdrawal into an arch transaction" }
                    return false
                }
                limit = sequencedWithdrawals.size - 1
                continue
            }
            val transaction = BlockchainTransactionEntity.create(
                chainId = chainId,
                transactionData = BlockchainTransactionData(
                    instruction.serialize().toHex(),
                    programPubkey.toContractAddress(),
                ),
                batchHash = null,
            )
            transaction.flush()
            WithdrawalEntity.updateToSettlingOnArch(sequencedWithdrawals.map { it.withdrawalEntity }, transaction)
            submitToArch(transaction, instruction)
            UtxoSelectionService.reserveUtxos(selectedUtxos, transaction.txHash?.value ?: "")

            return true
        }
    }

    private fun onWithdrawalCompleteOnBitcoin(withdrawalEntity: WithdrawalEntity, error: String? = null) {
        transaction {
            withdrawalEntity.update(
                status = error?.let { WithdrawalStatus.Failed }
                    ?: WithdrawalStatus.Complete,
                error = error,
            )
            if (error == null) {
                BalanceEntity.updateBalances(
                    listOf(
                        BalanceChange.Delta(
                            withdrawalEntity.wallet.id.value,
                            withdrawalEntity.symbol.id.value,
                            withdrawalEntity.resolvedAmount().negate(),
                        ),
                    ),
                    BalanceType.Exchange,
                )
                publishBroadcasterNotifications(listOf(BroadcasterNotification.walletBalances(withdrawalEntity.wallet.userGuid.value)))
            } else {
                val sequencerResponse = runBlocking {
                    sequencerClient.failWithdraw(
                        withdrawalEntity.wallet.userGuid.value.toSequencerId(),
                        Asset(withdrawalEntity.symbol.name),
                        withdrawalEntity.resolvedAmount(),
                    )
                }
                if (sequencerResponse.error == SequencerError.None) {
                    logger.debug { "Successfully notified sequencer" }
                } else {
                    logger.error { "Sequencer failed with error ${sequencerResponse.error} - fail withdrawals" }
                }
            }
        }
    }

    private fun refreshSubmittedTransaction(tx: BlockchainTransactionEntity, onComplete: (ArchNetworkRpc.ProcessedTransaction, String?) -> Unit) {
        val txHash = tx.txHash ?: return
        val processedTx = ArchNetworkClient.getProcessedTransaction(txHash) ?: return
        if (processedTx.status == ArchNetworkRpc.Status.Processed) {
            Thread.sleep(2000)
            // invoke callbacks for transactions in this confirmed batch
            onComplete(processedTx, null)
            // mark batch as complete
            tx.markAsCompleted()
        }
        // TODO error handling once Arch returns errors
    }
}
