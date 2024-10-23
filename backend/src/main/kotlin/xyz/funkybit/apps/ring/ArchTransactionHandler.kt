package xyz.funkybit.apps.ring

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import xyz.funkybit.core.blockchain.bitcoin.ArchNetworkClient
import xyz.funkybit.core.blockchain.bitcoin.ArchNetworkClient.MAX_INSTRUCTION_SIZE
import xyz.funkybit.core.blockchain.bitcoin.MempoolSpaceClient
import xyz.funkybit.core.blockchain.bitcoin.bitcoinConfig
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.ConfirmedBitcoinDeposit
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.bitcoin.ArchAccountState
import xyz.funkybit.core.model.db.ArchAccountBalanceIndexEntity
import xyz.funkybit.core.model.db.ArchAccountBalanceIndexStatus
import xyz.funkybit.core.model.db.ArchAccountBalanceInfo
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
import xyz.funkybit.core.model.db.DeployedSmartContractEntity
import xyz.funkybit.core.model.db.DepositEntity
import xyz.funkybit.core.model.db.DepositStatus
import xyz.funkybit.core.model.db.DepositTable
import xyz.funkybit.core.model.db.OrderExecutionEntity
import xyz.funkybit.core.model.db.SettlementBatchStatus
import xyz.funkybit.core.model.db.SymbolId
import xyz.funkybit.core.model.db.TradeEntity
import xyz.funkybit.core.model.db.UpdateArchAccountBalanceIndexAssignment
import xyz.funkybit.core.model.db.WalletEntity
import xyz.funkybit.core.model.db.WithdrawalEntity
import xyz.funkybit.core.model.db.WithdrawalStatus
import xyz.funkybit.core.model.db.publishBroadcasterNotifications
import xyz.funkybit.core.model.rpc.ArchNetworkRpc
import xyz.funkybit.core.sequencer.SequencerClient
import xyz.funkybit.core.sequencer.toSequencerId
import xyz.funkybit.core.services.UtxoManager
import xyz.funkybit.core.utils.bitcoin.ArchUtils
import xyz.funkybit.core.utils.bitcoin.BitcoinInsufficientFundsException
import xyz.funkybit.core.utils.toHex
import xyz.funkybit.core.utils.triggerRepeaterTask
import xyz.funkybit.core.utils.tryAcquireAdvisoryLock
import xyz.funkybit.sequencer.core.Asset
import xyz.funkybit.sequencer.proto.SequencerError
import java.math.BigInteger
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ArchTransactionHandler(
    private val sequencerClient: SequencerClient,
    private val pollingInterval: Duration = (System.getenv("ARCH_TX_HANDLER_ACTIVE_POLLING_INTERVAL_MS")?.toLongOrNull() ?: 1000L).milliseconds,
    private val failurePollingInterval: Duration = (System.getenv("ARCH_TX_HANDLER_FAILURE_POLLING_INTERVAL_MS")?.toLongOrNull() ?: 3000L).milliseconds,
    private val batchMinWithdrawals: Int = System.getenv("ARCH_WITHDRAWAL_SETTLEMENT_BATCH_MIN_WITHDRAWALS")?.toIntOrNull() ?: 1,
    private val batchMaxInterval: Duration = (System.getenv("ARCH_WITHDRAWAL_SETTLEMENT_BATCH_MAX_WAIT_MS")?.toLongOrNull() ?: 1000).milliseconds,
    private val maxWaitTime: Duration = System.getenv("BITCOIN_TRANSACTION_HANDLER_MAX_WAIT_TIME_HOURS")?.toLongOrNull()?.hours ?: 48.hours,

) {
    private val chainId = bitcoinConfig.chainId
    private var archWorkerThread: Thread? = null
    private var bitcoinWorkerThread: Thread? = null
    val logger = KotlinLogging.logger {}

    private lateinit var programAccount: ArchAccountEntity
    private lateinit var programStateAccount: ArchAccountEntity
    private lateinit var programBitcoinAddress: BitcoinAddress

    fun start() {
        logger.debug { "Starting batch transaction handler for $chainId" }
        startArchWorker()
        startBitcoinWorker()
    }

    private fun startArchWorker() {
        archWorkerThread = thread(start = false, name = "arch-transaction-handler-$chainId", isDaemon = true) {
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

            var settlementBatchInProgress: Boolean
            var withdrawalBatchInProgress = false

            while (true) {
                try {
                    transaction {
                        if (tryAcquireAdvisoryLock(chainId.value.toLong())) {
                            processBalanceIndexBatch()
                            processDepositBatch()
                            processFailedWithdrawalsBatch()
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
        }.also { thread ->
            thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, throwable ->
                logger.error(throwable) { "Uncaught exception in ${thread.name} thread" }
                Thread.sleep(5.seconds.inWholeMilliseconds)
                startArchWorker()
            }
            thread.start()
        }
    }

    private fun startBitcoinWorker() {
        bitcoinWorkerThread = thread(start = false, name = "bitcoin-transaction-handler", isDaemon = true) {
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
        }.also { thread ->
            thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, throwable ->
                logger.error(throwable) { "Uncaught exception in ${thread.name} thread" }
                Thread.sleep(5.seconds.inWholeMilliseconds)
                startBitcoinWorker()
            }
            thread.start()
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
                refreshSubmittedTransaction(archTransaction) { _, error ->
                    if (error == null) {
                        archTransaction.markAsCompleted()
                        val assignments = assigningBalanceIndexUpdates.groupBy { it.archAccountAddress }.flatMap { (pubkey, updates) ->
                            val tokenAccountState = ArchUtils.getAccountState<ArchAccountState.Token>(pubkey)
                            val balances = tokenAccountState.balances.reversed()
                            updates.map { update ->
                                val index = balances.indexOfFirst { it.walletAddress == update.walletAddress }
                                if (index == -1) {
                                    // throw an error since we did not find it
                                    throw Exception("Did not find an index for wallet ${update.walletAddress.value}")
                                }
                                UpdateArchAccountBalanceIndexAssignment(
                                    update.entity,
                                    balances.size - index - 1,
                                )
                            }.also {
                                ArchAccountEntity.getByPubkey(pubkey)?.let {
                                    if (tokenAccountState.balances.size > getWalletsPerTokenAccountThreshold()) {
                                        it.markAsFull()
                                    }
                                }
                            }
                        }
                        ArchAccountBalanceIndexEntity.updateToAssigned(assignments)
                    } else {
                        ArchAccountBalanceIndexEntity.updateToPending(assigningBalanceIndexUpdates.map { it.entity })
                    }
                }
            }
            true
        } else {
            val (pendingBalanceIndexUpdates, instruction) = createNextBalanceIndexBatch(programPubkey)
            if (instruction != null) {
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

    private fun getWalletsPerTokenAccountThreshold(): Int {
        return ArchUtils.walletsPerTokenAccountThreshold
    }

    private fun settlementBatchInProgress(chainId: ChainId) =
        ChainSettlementBatchEntity.findInProgressBatch(chainId) != null

    private fun withdrawalBatchInProgress(chainId: ChainId) =
        WithdrawalEntity.findSettling(chainId).isNotEmpty()

    private fun processDepositBatch(): Boolean {
        val programPubkey = programAccount.rpcPubkey()
        val settlingDeposits = DepositEntity.getSettlingForUpdate(chainId)
        if (settlingDeposits.isNotEmpty()) {
            settlingDeposits.forEach(::sendToSequencer)
        }

        val sentToArchDeposits = DepositEntity.getSentToArchForUpdate(chainId)
        return if (sentToArchDeposits.isNotEmpty()) {
            // handle deposits sent to arch
            sentToArchDeposits.first().archTransaction?.let { archTransaction ->
                refreshSubmittedTransaction(archTransaction) { _, error ->
                    if (error == null) {
                        archTransaction.markAsCompleted()
                        BalanceEntity.updateBalances(
                            sentToArchDeposits.map { deposit ->
                                BalanceChange.Delta(deposit.wallet.id.value, deposit.symbol.guid.value, deposit.amount)
                            },
                            BalanceType.Exchange,
                        )
                        DepositEntity.updateToSettling(sentToArchDeposits)
                    } else {
                        // move back to confirmed - we will try again
                        DepositEntity.updateToConfirmed(sentToArchDeposits)
                    }
                }
            }
            true
        } else {
            val (confirmedDepositsWithAssignedIndex, instruction) = createNextDepositBatch(programPubkey)
            if (instruction != null) {
                val transaction = BlockchainTransactionEntity.create(
                    chainId = chainId,
                    transactionData = BlockchainTransactionData(instruction.serialize().toHex(), programPubkey.toContractAddress()),
                    batchHash = null,
                )
                transaction.flush()
                DepositEntity.updateToSentToArch(confirmedDepositsWithAssignedIndex.map { it.depositEntity }, transaction)
                submitToArch(transaction, instruction)
                initiateProgramUtxoRefresh()
                true
            } else {
                false
            }
        }
    }

    private fun processFailedWithdrawalsBatch(): Boolean {
        val programPubkey = programAccount.rpcPubkey()

        val rollingBackOnArchWithdrawals = WithdrawalEntity.findRollingBackOnArch()
        return if (rollingBackOnArchWithdrawals.isNotEmpty()) {
            // handle withdrawal rollback sent to arch
            rollingBackOnArchWithdrawals.first().archRollbackTransaction?.let { archRollbackTransaction ->
                refreshSubmittedTransaction(archRollbackTransaction) { _, error ->
                    if (error == null) {
                        archRollbackTransaction.markAsCompleted()
                        rollingBackOnArchWithdrawals.forEach { withdrawalEntity ->
                            onWithdrawalCompleteOnBitcoin(withdrawalEntity, "failed to confirm on bitcoin network")
                        }
                    } else {
                        WithdrawalEntity.updateToPendingRollbackOnArch(rollingBackOnArchWithdrawals)
                    }
                }
            }
            true
        } else {
            createNextRollbackWithdrawalBatch(programPubkey)
        }
    }

    private fun processWithdrawalBatch(): Boolean {
        val programPubkey = programAccount.rpcPubkey()
        if (settlementBatchInProgress(chainId)) {
            return false
        }
        val settlingWithdrawals = WithdrawalEntity.findSettlingOnArch()
        return if (settlingWithdrawals.isNotEmpty()) {
            logger.debug { }
            val archTransaction = settlingWithdrawals.first().archTransaction!!
            refreshSubmittedTransaction(archTransaction) { processedTx, error ->
                if (error == null) {
                    archTransaction.markAsCompleted()
                    val failedWithdrawalEvents = getFailedWithdrawalEvents()
                    val (successfulWithdrawals, failedWithdrawals) = if (failedWithdrawalEvents.isNotEmpty()) {
                        logger.warn { "received failed withdrawal events - $failedWithdrawalEvents" }
                        partitionWithdrawals(settlingWithdrawals, failedWithdrawalEvents, archTransaction)
                    } else {
                        Pair(settlingWithdrawals, emptyList())
                    }
                    if (processedTx.bitcoinTxId != null) {
                        val transaction = BlockchainTransactionEntity.create(
                            chainId = chainId,
                            transactionData = BlockchainTransactionData("", EvmAddress.zero),
                            batchHash = null,
                            txHash = processedTx.bitcoinTxId,
                        )
                        transaction.flush()
                        if (successfulWithdrawals.isNotEmpty()) {
                            WithdrawalEntity.updateToSettling(successfulWithdrawals, transaction, emptyMap())
                        }
                    } else {
                        successfulWithdrawals.forEach {
                            onWithdrawalCompleteOnBitcoin(it, "No bitcoin transaction returned")
                        }
                        archTransaction.txHash?.let {
                            UtxoManager.releaseUtxos(it.value)
                        }
                    }
                    if (failedWithdrawals.isNotEmpty()) {
                        failedWithdrawals.forEach {
                            onWithdrawalCompleteOnBitcoin(it, "Insufficient Balance")
                        }
                    }
                } else {
                    settlingWithdrawals.forEach {
                        onWithdrawalCompleteOnBitcoin(it, error)
                    }
                    archTransaction.txHash?.let {
                        UtxoManager.releaseUtxos(it.value)
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
                val bitcoinTx = MempoolSpaceClient.getTransaction(blockchainTransaction.txHash!!)
                if (bitcoinTx != null) {
                    if (bitcoinTx.status.confirmed) {
                        blockchainTransaction.markAsCompleted()
                        withdrawals.forEach { withdrawal ->
                            onWithdrawalCompleteOnBitcoin(withdrawal, null)
                        }
                        initiateProgramUtxoRefresh()
                    }
                } else {
                    withdrawals.forEach { withdrawal ->
                        if (Clock.System.now() - (withdrawal.updatedAt ?: withdrawal.createdAt) > maxWaitTime) {
                            withdrawal.status = WithdrawalStatus.PendingRollback
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
                                val (onChainBatchHash, failedEvents) = getOnChainBatchHashAndFailedEvents(retryIfEmpty = true)
                                if (failedEvents.isNotEmpty()) {
                                    val orderExecutions = OrderExecutionEntity.findForTrades(TradeEntity.findSettling())
                                    val accounts = inProgressBatch.preparationTx.archAccounts()
                                    failedEvents.forEach {
                                        logger.warn { "Got a failed settlement event $it" }
                                    }
                                    val failedTradeIds = failedEvents.groupBy { it.accountIndex }
                                        .mapValues { it.value.map { it.addressIndex } }
                                        .mapNotNull { (accountIndex, addressIndexes) ->
                                            val pubKey = accounts[accountIndex.toInt()].pubkey
                                            val tokenAccountState =
                                                ArchUtils.getAccountState<ArchAccountState.Token>(pubKey)
                                            val walletAddresses =
                                                addressIndexes.map { tokenAccountState.balances[it.toInt()].walletAddress }
                                            val userGuids = walletAddresses.mapNotNull {
                                                WalletEntity.findByAddresses(walletAddresses)?.userGuid
                                            }
                                            ArchAccountEntity.getByPubkey(pubKey)?.symbol?.name?.let { symbolName ->
                                                orderExecutions.filter {
                                                    userGuids.contains(it.userGuid) && it.marketGuid.value.hasSymbol(symbolName)
                                                }.map { it.tradeGuid }
                                            }
                                        }.flatten().toSet()

                                    TradeEntity.markAsFailedSettlingByGuid(
                                        failedTradeIds,
                                        "Insufficient Balance",
                                    )
                                }

                                if (failedEvents.isEmpty() && onChainBatchHash.value != inProgressBatch.preparationTx.batchHash) {
                                    val errorMsg = "Batch hash mismatch for settlement batch ${inProgressBatch.guid.value}, on chain value: $onChainBatchHash, db value: ${inProgressBatch.preparationTx.batchHash}"
                                    logger.error { errorMsg }
                                    inProgressBatch.markAsFailed(errorMsg)
                                } else {
                                    inProgressBatch.markAsPrepared()
                                }
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

    private fun batchInProgressOnChain() = getOnChainBatchHashAndFailedEvents().first != TxHash.emptyHashArch

    private fun getOnChainBatchHashAndFailedEvents(retryIfEmpty: Boolean = false): Pair<TxHash, List<ArchAccountState.Event>> {
        val archAccount = ArchUtils.getAccountState<ArchAccountState.Program>(programStateAccount.rpcPubkey())
        if (archAccount.settlementBatchHash == TxHash.emptyHashArch && archAccount.events.isEmpty() && retryIfEmpty) {
            (1..3).forEach { i ->
                logger.debug { "got an empty batch hash - retry $i" }
                ArchUtils.getAccountState<ArchAccountState.Program>(programStateAccount.rpcPubkey()).let {
                    if (it.settlementBatchHash != TxHash.emptyHashArch) {
                        return Pair(it.settlementBatchHash, it.events)
                    }
                    Thread.sleep(500)
                }
            }
        }
        return Pair(
            archAccount.settlementBatchHash,
            archAccount.events.filter { it.eventType == ArchAccountState.EventType.FailedSettlement.value },
        )
    }

    private fun getFailedWithdrawalEvents(): List<ArchAccountState.Event> {
        val archAccount = ArchUtils.getAccountState<ArchAccountState.Program>(programStateAccount.rpcPubkey())
        return archAccount.events.filter { it.eventType == ArchAccountState.EventType.FailedWithdrawal.value }
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
        var limit = 15

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
                try {
                    initiateProgramUtxoRefresh()
                } catch (e: Exception) {
                    logger.warn(e) { "failed to refresh program utxos" }
                }
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
                    Json.encodeToString(instruction),
                    programPubkey.toContractAddress(),
                ),
                batchHash = null,
            )
            transaction.flush()
            WithdrawalEntity.updateToSettlingOnArch(sequencedWithdrawals.map { it.withdrawalEntity }, transaction)
            submitToArch(transaction, instruction)
            UtxoManager.reserveUtxos(selectedUtxos, transaction.txHash?.value ?: "")

            return true
        }
    }

    private fun createNextRollbackWithdrawalBatch(programPubkey: ArchNetworkRpc.Pubkey): Boolean {
        val failedWithdrawalsToRollback = WithdrawalEntity.findPendingRollbackOnArch()
        if (failedWithdrawalsToRollback.isEmpty()) {
            return false
        }

        val instruction = ArchUtils.buildRollbackWithdrawBatchInstruction(
            programPubkey,
            failedWithdrawalsToRollback,
        )
        val transaction = BlockchainTransactionEntity.create(
            chainId = chainId,
            transactionData = BlockchainTransactionData(
                instruction.serialize().toHex(),
                programPubkey.toContractAddress(),
            ),
            batchHash = null,
        )
        transaction.flush()
        WithdrawalEntity.updateToRollingBackOnArch(failedWithdrawalsToRollback.map { it.withdrawalEntity }, transaction)
        failedWithdrawalsToRollback.mapNotNull { it.withdrawalEntity.archTransaction?.txHash }.toSet().forEach {
            UtxoManager.releaseUtxos(it.value)
        }
        submitToArch(transaction, instruction)

        return true
    }

    private fun createNextBalanceIndexBatch(programPubkey: ArchNetworkRpc.Pubkey): Pair<List<ArchAccountBalanceInfo>, ArchNetworkRpc.Instruction?> {
        var limit = 100

        while (true) {
            val pendingBalanceIndexUpdates =
                ArchAccountBalanceIndexEntity.findAllForStatus(ArchAccountBalanceIndexStatus.Pending, limit = limit)
            if (pendingBalanceIndexUpdates.isNotEmpty()) {
                val instruction = ArchUtils.buildInitTokenBalanceIndexBatchInstruction(programPubkey, pendingBalanceIndexUpdates)
                val serializedInstruction = instruction.serialize()
                logger.debug { "in createNextBalanceIndexBatch - batch size = ${pendingBalanceIndexUpdates.size}, serialized size=${serializedInstruction.size}" }
                if (serializedInstruction.size > MAX_INSTRUCTION_SIZE) {
                    limit -= 2
                    continue
                } else {
                    logger.debug { "Initiating ${pendingBalanceIndexUpdates.size} index updates" }
                    return Pair(pendingBalanceIndexUpdates, instruction)
                }
            }
            return Pair(listOf(), null)
        }
    }

    private fun createNextDepositBatch(programPubkey: ArchNetworkRpc.Pubkey): Pair<List<ConfirmedBitcoinDeposit>, ArchNetworkRpc.Instruction?> {
        var limit = 100

        while (true) {
            val confirmedDepositsWithAssignedIndex = ArchUtils.getConfirmedBitcoinDeposits(limit)

            if (confirmedDepositsWithAssignedIndex.isNotEmpty()) {
                val instruction = ArchUtils.buildDepositBatchInstruction(programPubkey, confirmedDepositsWithAssignedIndex)
                val serializedInstruction = instruction.serialize()
                logger.debug { "in createNextDepositBatch - batch size = ${confirmedDepositsWithAssignedIndex.size}, serialized size=${serializedInstruction.size}" }
                if (serializedInstruction.size > MAX_INSTRUCTION_SIZE) {
                    limit = maxOf(1, limit - 5)
                    continue
                } else {
                    logger.debug { "Handling ${confirmedDepositsWithAssignedIndex.size} confirmed deposits" }
                    return Pair(confirmedDepositsWithAssignedIndex, instruction)
                }
            }
            return Pair(listOf(), null)
        }
    }

    private fun initiateProgramUtxoRefresh() {
        triggerRepeaterTask("program_utxo_refresher")
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
        when (processedTx.statusInfo.status) {
            ArchNetworkRpc.Status.Processed -> {
                onComplete(processedTx, null)
                tx.markAsCompleted()
            }
            ArchNetworkRpc.Status.Failed -> {
                val errorInfo = processedTx.statusInfo.errorInfo
                logger.warn { "Arch RPC call failed with $errorInfo" }
                val error = errorInfo?.msg ?: "Unknown Error"
                onComplete(processedTx, error)
                tx.markAsFailed(error)
            }
            else -> {}
        }
    }

    private fun partitionWithdrawals(settlingWithdrawals: List<WithdrawalEntity>, failedWithdrawals: List<ArchAccountState.Event>, archTransaction: BlockchainTransactionEntity): Pair<List<WithdrawalEntity>, List<WithdrawalEntity>> {
        val accounts = archTransaction.archAccounts()
        val failedWithdrawalGuids = failedWithdrawals.groupBy { it.accountIndex }
            .mapNotNull { (accountIndex, failedEvents) ->
                val pubKey = accounts[accountIndex.toInt()].pubkey
                val tokenAccountState = ArchUtils.getAccountState<ArchAccountState.Token>(pubKey)
                ArchAccountEntity.getByPubkey(pubKey)?.symbolGuid?.value?.let { symbolId ->
                    val failedSet: List<Triple<SymbolId, BigInteger, BitcoinAddress>> =
                        failedEvents.map {
                            Triple(
                                symbolId,
                                it.requestedAmount.toLong().toBigInteger(),
                                tokenAccountState.balances[it.addressIndex.toInt()].walletAddress,
                            )
                        }
                    settlingWithdrawals.filter {
                        failedSet.contains(
                            Triple(
                                it.symbolGuid.value,
                                it.resolvedAmount(),
                                it.wallet.address as BitcoinAddress,
                            ),
                        )
                    }.map { it.guid }
                }
            }.flatten()
        return settlingWithdrawals.partition { !failedWithdrawalGuids.contains(it.guid) }
    }
}
