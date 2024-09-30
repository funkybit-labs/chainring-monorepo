package xyz.funkybit.apps.ring

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.transactions.transaction
import org.web3j.crypto.Hash.sha3
import org.web3j.crypto.RawTransaction
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.tx.Contract
import org.web3j.utils.Numeric
import xyz.funkybit.contracts.generated.Exchange
import xyz.funkybit.core.blockchain.evm.DefaultBlockParam
import xyz.funkybit.core.blockchain.evm.EvmClient
import xyz.funkybit.core.blockchain.evm.EvmClientException
import xyz.funkybit.core.blockchain.evm.EvmServerException
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.db.BalanceChange
import xyz.funkybit.core.model.db.BalanceEntity
import xyz.funkybit.core.model.db.BalanceType
import xyz.funkybit.core.model.db.BlockchainNonceEntity
import xyz.funkybit.core.model.db.BlockchainTransactionData
import xyz.funkybit.core.model.db.BlockchainTransactionEntity
import xyz.funkybit.core.model.db.BlockchainTransactionStatus
import xyz.funkybit.core.model.db.BroadcasterNotification
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.ChainSettlementBatchEntity
import xyz.funkybit.core.model.db.SettlementBatchStatus
import xyz.funkybit.core.model.db.TradeEntity
import xyz.funkybit.core.model.db.WithdrawalEntity
import xyz.funkybit.core.model.db.WithdrawalId
import xyz.funkybit.core.model.db.WithdrawalStatus
import xyz.funkybit.core.model.db.publishBroadcasterNotifications
import xyz.funkybit.core.model.evm.EIP712Transaction
import xyz.funkybit.core.sequencer.SequencerClient
import xyz.funkybit.core.sequencer.toSequencerId
import xyz.funkybit.core.utils.BlockchainUtils.getAsOfBlockOrLater
import xyz.funkybit.core.utils.toHex
import xyz.funkybit.core.utils.toHexBytes
import xyz.funkybit.core.utils.tryAcquireAdvisoryLock
import xyz.funkybit.sequencer.core.Asset
import xyz.funkybit.sequencer.proto.SequencerError
import java.math.BigInteger
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds
import org.jetbrains.exposed.sql.transactions.transaction as dbTransaction

class EvmTransactionHandler(
    private val evmClient: EvmClient,
    private val sequencerClient: SequencerClient,
    private val numConfirmations: Int = System.getenv("EVM_TX_HANDLER_NUM_CONFIRMATIONS")?.toIntOrNull() ?: 1,
    private val activePollingIntervalInMs: Long = System.getenv("EVM_TX_HANDLER_ACTIVE_POLLING_INTERVAL_MS")?.toLongOrNull() ?: 100L,
    private val inactivePollingIntervalInMs: Long = System.getenv("EVM_TX_HANDLER_INACTIVE_POLLING_INTERVAL_MS")?.toLongOrNull() ?: 500L,
    private val failurePollingIntervalInMs: Long = System.getenv("EVM_TX_HANDLER_FAILURE_POLLING_INTERVAL_MS")?.toLongOrNull() ?: 2000L,
    private val maxUnseenBlocksForFork: Int = System.getenv("EVM_TX_HANDLER_MAX_UNSEEN_BLOCKS_FOR_FORK")?.toIntOrNull() ?: 6,
    private val batchMinWithdrawals: Int = System.getenv("WITHDRAWAL_SETTLEMENT_BATCH_MIN_WITHDRAWALS")?.toIntOrNull() ?: 1,
    private val batchMaxIntervalMs: Long = System.getenv("WITHDRAWAL_SETTLEMENT_BATCH_MAX_WAIT_MS")?.toLongOrNull() ?: 1000L,
) {
    private val chainId = evmClient.chainId
    private var workerThread: Thread? = null
    val logger = KotlinLogging.logger {}

    fun start() {
        logger.debug { "Starting batch transaction handler for $chainId" }
        startWorker()
    }

    private fun startWorker() {
        workerThread = thread(start = false, name = "batch-transaction-handler-$chainId", isDaemon = true) {
            logger.debug { "Batch Transaction handler thread starting" }
            dbTransaction {
                BlockchainNonceEntity.clear(evmClient.submitterAddress, chainId)

                try {
                    if (tryAcquireAdvisoryLock(chainId.value.toLong())) {
                        // this is primarily to handle cases where we sent something on-chain but process exited
                        // before tx that updated the blockchain entity was committed, so looks like it was not sent on-chain yet.
                        val withdrawalsSettling = WithdrawalEntity.findSettling(chainId)
                        if (withdrawalsSettling.isNotEmpty()) {
                            syncWithdrawalWithBlockchain(withdrawalsSettling)
                        } else {
                            val inProgressSettlementBatch = ChainSettlementBatchEntity.findInProgressBatch(chainId)
                            if (inProgressSettlementBatch != null) {
                                syncSettlementWithBlockchain(inProgressSettlementBatch)
                            } else if (batchInProgressOnChain(blockNumber = null)) {
                                logger.debug { "rolling back on chain on startup" }
                                rollbackOnChain()
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Error on startup trying to sync to consistent state" }
                }
            }
            var settlementBatchInProgress = false
            var withdrawalBatchInProgress = false
            while (true) {
                try {
                    if (withdrawalBatchInProgress) {
                        withdrawalBatchInProgress = dbTransaction {
                            if (tryAcquireAdvisoryLock(chainId.value.toLong())) {
                                processWithdrawalBatch()
                            } else {
                                withdrawalBatchInProgress(chainId)
                            }
                        }
                    } else {
                        settlementBatchInProgress = dbTransaction {
                            if (tryAcquireAdvisoryLock(chainId.value.toLong())) {
                                processSettlementBatch()
                            } else {
                                settlementBatchInProgress(chainId)
                            }
                        }
                        if (!settlementBatchInProgress) {
                            withdrawalBatchInProgress = dbTransaction {
                                if (tryAcquireAdvisoryLock(chainId.value.toLong())) {
                                    processWithdrawalBatch()
                                } else {
                                    withdrawalBatchInProgress(chainId)
                                }
                            }
                        }
                    }

                    Thread.sleep(
                        if (settlementBatchInProgress || withdrawalBatchInProgress) activePollingIntervalInMs else inactivePollingIntervalInMs,
                    )
                } catch (ie: InterruptedException) {
                    logger.warn { "Exiting blockchain handler" }
                    return@thread
                } catch (e: Exception) {
                    logger.error(e) { "Unhandled exception submitting tx" }
                    Thread.sleep(failurePollingIntervalInMs)
                }
            }
        }.also { thread ->
            thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, throwable ->
                logger.error(throwable) { "Unhandled exception in ${thread.name} thread" }
                startWorker()
            }
            thread.start()
        }
    }

    private fun syncSettlementWithBlockchain(inProgressSettlementBatch: ChainSettlementBatchEntity) {
        when (inProgressSettlementBatch.status) {
            SettlementBatchStatus.Preparing -> {
                val preparationTx = inProgressSettlementBatch.preparationTx
                when (preparationTx.status) {
                    BlockchainTransactionStatus.Pending -> {
                        if (preparationTx.batchHash == getOnChainBatchHash(preparationTx.blockNumber)) {
                            // batch with this hash is already prepared on onchain
                            inProgressSettlementBatch.markAsPrepared()
                        } else {
                            // check if in mempool and if so update txHash and mark tx submitted
                            val txHash = getTxHash(preparationTx.transactionData)
                            evmClient.getTransactionByHash(txHash.value)?.let {
                                preparationTx.markAsSubmitted(txHash, blockNumber = it.blockNumber, lastSeenBlock = it.blockNumber)
                            }
                        }
                    }
                    else -> {}
                }
            }
            SettlementBatchStatus.Submitting -> {
                inProgressSettlementBatch.submissionTx?.let { submissionTx ->
                    when (submissionTx.status) {
                        BlockchainTransactionStatus.Pending -> {
                            if (submissionTx.batchHash == getOnChainLastSettlementBatchHash(submissionTx.blockNumber)) {
                                // this batch is the last one successfully settled, so mark as submitted
                                inProgressSettlementBatch.markAsCompleted()
                            } else {
                                val txHash = getTxHash(submissionTx.transactionData)
                                evmClient.getTransactionByHash(txHash.value)?.let {
                                    submissionTx.markAsSubmitted(txHash, blockNumber = it.blockNumber, lastSeenBlock = it.blockNumber)
                                }
                            }
                        }

                        else -> {}
                    }
                }
            }

            else -> {}
        }
    }

    private fun syncWithdrawalWithBlockchain(settlingWithdrawals: List<WithdrawalEntity>) {
        val blockchainTx: BlockchainTransactionEntity = settlingWithdrawals.first().blockchainTransaction!!
        when (blockchainTx.status) {
            BlockchainTransactionStatus.Pending -> {
                if (blockchainTx.batchHash == getOnChainLastWithdrawalBatchHash(blockchainTx.blockNumber)) {
                    completeWithdrawals(settlingWithdrawals)
                } else {
                    // check if in mempool and if so update txHash and mark tx submitted
                    val txHash = getTxHash(blockchainTx.transactionData)
                    evmClient.getTransactionByHash(txHash.value)?.let {
                        blockchainTx.markAsSubmitted(txHash, blockNumber = it.blockNumber, lastSeenBlock = it.blockNumber)
                    }
                }
            }
            else -> {}
        }
    }

    fun stop() {
        workerThread?.let {
            it.interrupt()
            it.join(100)
        }
    }

    private fun settlementBatchInProgress(chainId: ChainId) =
        ChainSettlementBatchEntity.findInProgressBatch(chainId) != null

    private fun withdrawalBatchInProgress(chainId: ChainId) =
        WithdrawalEntity.findSettling(chainId).isNotEmpty()

    private fun processWithdrawalBatch(): Boolean {
        if (settlementBatchInProgress(chainId)) {
            return false
        }
        val settlingWithdrawals = WithdrawalEntity.findSettling(chainId)
        if (settlingWithdrawals.isEmpty()) {
            return createNextWithdrawalBatch()
        } else {
            val blockchainTx = settlingWithdrawals.first().blockchainTransaction!!
            when (blockchainTx.status) {
                BlockchainTransactionStatus.Pending -> {
                    submitToBlockchain(blockchainTx)
                    return true
                }
                BlockchainTransactionStatus.Submitted -> {
                    return !refreshSubmittedTransaction(
                        tx = blockchainTx,
                        currentBlock = evmClient.getBlockNumber(),
                        confirmationsNeeded = numConfirmations,
                        onTxNotFound = { onWithdrawalTxNotFound(blockchainTx) },
                    ) { txReceipt, error ->
                        if (error == null) {
                            // extract the failed withdrawals from the events
                            val failedWithdrawalBySequence = txReceipt.logs.mapNotNull { eventLog ->
                                Contract.staticExtractEventParameters(
                                    Exchange.WITHDRAWALFAILED_EVENT,
                                    eventLog,
                                )?.let {
                                    val event = Exchange.getWithdrawalFailedEventFromLog(eventLog)
                                    Pair(event.sequence.toLong(), event)
                                }
                            }.toMap()
                            val completedWithdrawalBySequence = txReceipt.logs.mapNotNull { eventLog ->
                                Contract.staticExtractEventParameters(
                                    Exchange.WITHDRAWAL_EVENT,
                                    eventLog,
                                )?.let {
                                    val event = Exchange.getWithdrawalEventFromLog(eventLog)
                                    Pair(event.sequence.toLong(), event)
                                }
                            }.toMap()
                            val (completedWithdrawals, failedWithdrawals) = settlingWithdrawals.partition { completedWithdrawalBySequence.keys.contains(it.sequenceId) }
                            if (failedWithdrawals.isNotEmpty()) {
                                failWithdrawals(failedWithdrawals, failedWithdrawalBySequence, "Unknown Error")
                            }
                            if (completedWithdrawals.isNotEmpty()) {
                                completeWithdrawals(completedWithdrawals, completedWithdrawalBySequence)
                            }
                        } else {
                            // mark all the withdrawals as failed
                            failWithdrawals(settlingWithdrawals, defaultErrorMsg = error)
                        }
                    }
                }
                else -> {}
            }
        }

        return true
    }

    private fun processSettlementBatch(): Boolean {
        val inProgressBatch = ChainSettlementBatchEntity.findInProgressBatch(chainId)
            ?: return false

        return when (inProgressBatch.status) {
            SettlementBatchStatus.Preparing -> {
                when (inProgressBatch.preparationTx.status) {
                    BlockchainTransactionStatus.Pending -> {
                        // send prepare transaction call
                        submitToBlockchain(inProgressBatch.preparationTx)
                    }
                    BlockchainTransactionStatus.Submitted -> {
                        refreshSubmittedTransaction(
                            tx = inProgressBatch.preparationTx,
                            currentBlock = evmClient.getBlockNumber(),
                            confirmationsNeeded = 1,
                            onTxNotFound = { onPreparationTxNotFound(inProgressBatch) },
                        ) { txReceipt, error ->
                            if (error == null) {
                                // extract the failed trades from the failed event
                                val failedTrades = txReceipt.logs.mapNotNull { eventLog ->
                                    Contract.staticExtractEventParameters(
                                        Exchange.SETTLEMENTFAILED_EVENT,
                                        eventLog,
                                    )?.let {
                                        Exchange.getSettlementFailedEventFromLog(eventLog)
                                    }
                                }
                                if (failedTrades.isNotEmpty()) {
                                    failedTrades.forEach {
                                        logger.warn { "Failed trade tx=${inProgressBatch.preparationTx.guid.value} chain=$chainId token=${it.token} address=${it._address}, needed=${it.requestedAmount} balance=${it.balance}" }
                                    }
                                    TradeEntity.markAsFailedSettling(failedTrades.map { it.tradeHashes.map { it.toHex() } }.flatten().toSet(), "Insufficient Balance")
                                }
                                val onChainBatchHash = getOnChainBatchHash(inProgressBatch.preparationTx.blockNumber)
                                if (failedTrades.isEmpty() && onChainBatchHash != inProgressBatch.preparationTx.batchHash) {
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
                        currentBlock = evmClient.getBlockNumber(),
                        confirmationsNeeded = numConfirmations,
                        onTxNotFound = { onSubmissionTxNotFound(inProgressBatch) },
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
            submitToBlockchain(submissionTx)
            if (submissionTx.status == BlockchainTransactionStatus.Submitted) {
                currentBatch.markAsSubmitted()
            }
        }
    }

    private fun onPreparationTxNotFound(batch: ChainSettlementBatchEntity) {
        val onChainBatchHash = getOnChainBatchHash(batch.preparationTx.blockNumber)
        logger.debug { "batchHash stored = ${batch.preparationTx.batchHash} from contract = $onChainBatchHash" }
        if (batch.preparationTx.batchHash == onChainBatchHash) {
            // batch with this hash is already prepared on onchain (anvil restarted case)
            batch.markAsPrepared()
        } else {
            // fork case
            batch.preparationTx.status = BlockchainTransactionStatus.Pending
        }
    }

    private fun onSubmissionTxNotFound(batch: ChainSettlementBatchEntity) {
        batch.submissionTx?.let { submissionTx ->
            val lastSuccessfulBatchHash = getOnChainLastSettlementBatchHash(submissionTx.blockNumber)
            logger.debug { "batchHash stored = ${submissionTx.batchHash} from contract = $lastSuccessfulBatchHash" }
            if (submissionTx.batchHash == lastSuccessfulBatchHash) {
                // batch with this hash is already submitted on onchain (anvil restarted case)
                batch.markAsCompleted()
            } else {
                // fork case
                batch.status = SettlementBatchStatus.Submitting
                submissionTx.status = BlockchainTransactionStatus.Pending
            }
        }
    }
    private fun onWithdrawalTxNotFound(blockchainTx: BlockchainTransactionEntity) {
        val onChainBatchHash = getOnChainLastWithdrawalBatchHash(blockchainTx.blockNumber)
        logger.debug { "batchHash stored = ${blockchainTx.batchHash} from contract = $onChainBatchHash" }
        if (blockchainTx.batchHash == onChainBatchHash) {
            // batch with this hash is already submitted on onchain (anvil restarted case)
            completeWithdrawals(WithdrawalEntity.findSettling(chainId), emptyMap())
        } else {
            // fork case
            blockchainTx.status = BlockchainTransactionStatus.Pending
        }
    }

    private fun completeWithdrawals(settlingWithdrawals: List<WithdrawalEntity>, withdrawalEvents: Map<Long, Exchange.WithdrawalEventResponse> = emptyMap()) {
        settlingWithdrawals.forEach {
            try {
                val withdrawAmount = withdrawalEvents[it.sequenceId]?.let { event ->
                    if (it.amount == BigInteger.ZERO && it.actualAmount != null && event.amount.compareTo(it.actualAmount) != 0) {
                        logger.warn { "Withdraw all mismatch, sequencer=${it.actualAmount} contract=${event.amount} " }
                    }
                    event.amount
                }
                if (withdrawAmount == null) {
                    logger.error { "No withdraw event received for ${it.sequenceId}" }
                }
                onWithdrawalComplete(it, null, withdrawAmount ?: it.actualAmount ?: it.amount)
            } catch (e: Exception) {
                logger.error(e) { "Callback exception for withdrawal with sequence ${it.sequenceId}" }
            }
        }
    }

    private fun failWithdrawals(failedWithdrawals: List<WithdrawalEntity>, failedEvents: Map<Long, Exchange.WithdrawalFailedEventResponse> = emptyMap(), defaultErrorMsg: String = "Unknown Error") {
        failedWithdrawals.forEach {
            try {
                onWithdrawalComplete(
                    it,
                    failedEvents[it.sequenceId]?.toErrorString() ?: defaultErrorMsg,
                    null,
                )
            } catch (e: Exception) {
                logger.error(e) { "Callback exception for withdrawal with sequence ${it.sequenceId}" }
            }
        }
    }

    private fun batchInProgressOnChain(blockNumber: BigInteger?): Boolean {
        return BigInteger(getOnChainBatchHash(blockNumber), 16) != BigInteger.ZERO
    }

    private fun rollbackBatch(currentBatch: ChainSettlementBatchEntity) {
        // rollback if a batch in progress on chain already
        if (batchInProgressOnChain(currentBatch.preparationTx.blockNumber)) {
            currentBatch.rollbackTx?.let { rollbackTx ->
                logger.debug { "Submitting rollback Tx " }
                submitToBlockchain(rollbackTx)
                if (rollbackTx.status == BlockchainTransactionStatus.Submitted) {
                    currentBatch.markAsRolledBack()
                }
            }
        } else {
            currentBatch.markAsRolledBack()
        }
    }

    private fun refreshSubmittedTransaction(tx: BlockchainTransactionEntity, currentBlock: BigInteger, confirmationsNeeded: Int, onTxNotFound: () -> Unit, onComplete: (TransactionReceipt, String?) -> Unit): Boolean {
        val txHash = tx.txHash ?: return true
        val receipt = evmClient.getTransactionReceipt(txHash.value)
        if (receipt == null) {
            if (evmClient.getTransactionByHash(txHash.value) == null) {
                tx.lastSeenBlock?.let { lastSeenBlock ->
                    logger.debug { "Tx Not found - currentBlock=$currentBlock lastSeen=$lastSeenBlock maxUnseen=$maxUnseenBlocksForFork" }
                    if (currentBlock > lastSeenBlock + maxUnseenBlocksForFork.toBigInteger()) {
                        logger.debug { "Transaction not found in $maxUnseenBlocksForFork blocks, handling as a fork" }
                        BlockchainNonceEntity.clear(evmClient.submitterAddress, chainId)
                        onTxNotFound()
                    }
                }
            } else {
                tx.updateLastSeenBlock(currentBlock)
            }
            return true
        }
        tx.updateLastSeenBlock(currentBlock)

        val receiptBlockNumber = receipt.blockNumber ?: return true

        return when (receipt.status) {
            "0x1" -> {
                val confirmationsReceived = confirmations(currentBlock, receiptBlockNumber)
                if (tx.blockNumber == null) {
                    tx.updateBlockNumber(receiptBlockNumber)
                }
                if (confirmationsReceived >= confirmationsNeeded) {
                    tx.markAsConfirmed(gasAccountFee(receipt), receipt.gasUsed)

                    // invoke callbacks for transactions in this confirmed batch
                    onComplete(receipt, null)

                    // mark batch as complete
                    tx.markAsCompleted()
                    true
                } else {
                    false
                }
            }

            else -> {
                // update in DB as failed and log an error
                val error = receipt.revertReason
                    ?: evmClient.extractRevertReasonFromSimulation(
                        tx.transactionData.data,
                        receipt.blockNumber,
                    )
                    ?: "Unknown Error"

                logger.error { "transaction failed with revert reason $error" }

                onComplete(receipt, error)

                val submitterNonce = BlockchainNonceEntity.getOrCreateForUpdate(evmClient.submitterAddress, chainId)
                submitterNonce.nonce = null
                tx.markAsFailed(error, gasAccountFee(receipt), receipt.gasUsed)

                true
            }
        }
    }

    private fun submitToBlockchain(tx: BlockchainTransactionEntity) {
        val submitterNonce = BlockchainNonceEntity.getOrCreateForUpdate(evmClient.submitterAddress, chainId)
        try {
            val nonce = submitterNonce.nonce?.let { it + BigInteger.ONE } ?: getConsistentNonce(submitterNonce.key)
            logger.debug { "sending Tx with nonce $nonce" }
            val txHash = sendPendingTransaction(tx.transactionData, nonce)
            submitterNonce.nonce = nonce
            tx.markAsSubmitted(txHash, blockNumber = null, lastSeenBlock = evmClient.getBlockNumber())
        } catch (ce: EvmClientException) {
            logger.warn(ce) { "Failed with client exception, ${ce.message}" }
            BlockchainNonceEntity.clear(evmClient.submitterAddress, chainId)
        } catch (se: EvmServerException) {
            logger.warn(se) { "Failed to send, will retry, ${se.message}" }
        }
    }

    private fun rollbackOnChain() {
        try {
            val submitterNonce = BlockchainNonceEntity.getOrCreateForUpdate(evmClient.submitterAddress, chainId)
            val nonce = submitterNonce.nonce?.let { it + BigInteger.ONE } ?: getConsistentNonce(submitterNonce.key)
            sendPendingTransaction(
                BlockchainTransactionData(
                    evmClient.encodeRollbackBatchFunctionCall(),
                    evmClient.exchangeContractAddress,
                    BigInteger.ZERO,
                ),
                nonce,
            )
            submitterNonce.nonce = nonce
        } catch (ce: EvmClientException) {
            logger.error(ce) { "Failed with client exception, ${ce.message}" }
        } catch (se: EvmServerException) {
            logger.warn(se) { "Failed to send, will retry, ${se.message}" }
        }
    }

    private fun sendPendingTransaction(transactionData: BlockchainTransactionData, nonce: BigInteger): TxHash {
        val txManager = evmClient.getTxManager(nonce)
        val gasProvider = evmClient.gasProvider

        val response = try {
            txManager.sendEIP1559Transaction(
                gasProvider.chainId,
                gasProvider.getMaxPriorityFeePerGas(""),
                gasProvider.getMaxFeePerGas(""),
                gasProvider.gasLimit,
                transactionData.to.toString(),
                transactionData.data,
                transactionData.value,
            )
        } catch (e: Exception) {
            throw EvmServerException(e.message ?: "Unknown error")
        }

        val txHash = response.transactionHash
        val error = response.error

        if (txHash == null) {
            throw error
                ?.let { EvmClientException(error.message) }
                ?: EvmServerException("Unknown error")
        } else {
            return TxHash(txHash)
        }
    }

    private fun getTxHash(transactionData: BlockchainTransactionData): TxHash {
        val submitterNonce = BlockchainNonceEntity.getOrCreateForUpdate(evmClient.submitterAddress, chainId)
        val nonce = getConsistentNonce(submitterNonce.key)

        val txManager = evmClient.getTxManager(nonce)
        val gasProvider = evmClient.gasProvider

        return TxHash(
            sha3(
                txManager.sign(
                    RawTransaction.createTransaction(
                        gasProvider.chainId,
                        nonce,
                        gasProvider.gasLimit,
                        transactionData.to.toString(),
                        transactionData.value,
                        transactionData.data,
                        gasProvider.getMaxPriorityFeePerGas(""),
                        gasProvider.getMaxFeePerGas(""),
                    ),
                ).toHexBytes(),
            ).toHex(),
        )
    }

    private fun confirmations(currentBlock: BigInteger, startingBlock: BigInteger): Int {
        return (currentBlock - startingBlock).toLong().toInt() + 1
    }

    private fun gasAccountFee(receipt: TransactionReceipt): BigInteger? {
        return receipt.gasUsed?.let { gasUsed ->
            (gasUsed * Numeric.decodeQuantity(receipt.effectiveGasPrice))
        }
    }

    private fun getConsistentNonce(address: String): BigInteger {
        // this logic handles the fact that all RPC nodes may not be in sync, so we try to get a consistent nonce
        // by making multiple calls until we get a consistent value. Subsequently, we keep track of it ourselves.
        var isConsistent = false
        var candidateNonce: BigInteger? = null
        while (!isConsistent) {
            candidateNonce = evmClient.getNonce(address)
            isConsistent = (1..2).map { evmClient.getNonce(address) }.all { it == candidateNonce }
            if (!isConsistent) {
                logger.error { "Got inconsistent nonces, retrying" }
                Thread.sleep(100)
            }
        }
        return candidateNonce!!
    }

    private fun createNextWithdrawalBatch(): Boolean {
        val sequencedWithdrawals = WithdrawalEntity.findSequenced(evmClient.chainId, 50, TradeEntity.minResponseSequenceForPending())
        if (sequencedWithdrawals.isEmpty()) {
            return false
        }

        val now = Clock.System.now()
        val earliestSequencedWithdrawal = sequencedWithdrawals.minBy { it.createdAt }.createdAt
        if (sequencedWithdrawals.size < batchMinWithdrawals && earliestSequencedWithdrawal + batchMaxIntervalMs.milliseconds > now) {
            logger.debug { "Skipping create withdrawal batch. ${sequencedWithdrawals.size} pending withdrawals, max age ${(now - earliestSequencedWithdrawal).inWholeMilliseconds}ms" }
            return false
        }

        val transactionDataByWithdrawal = mutableMapOf<WithdrawalId, EIP712Transaction>()
        val withdrawals = sequencedWithdrawals.map { withdrawal ->
            (
                withdrawal.transactionData ?: run {
                    withdrawal.toEip712Transaction().also {
                        transactionDataByWithdrawal[withdrawal.guid.value] = it
                    }
                }
                ).getTxData(withdrawal.sequenceId)
        }
        val batchHash = sha3(withdrawals.map { sha3(it) }.reduce { a, b -> a + b }).toHex(add0x = false)
        logger.debug { "calculated withdrawal hash = $batchHash" }
        val transactionData = BlockchainTransactionData(
            data = evmClient.encodeSubmitWithdrawalsFunctionCall(withdrawals),
            to = evmClient.exchangeContractAddress,
            value = BigInteger.ZERO,
        )
        val transaction = BlockchainTransactionEntity.create(
            chainId = chainId,
            transactionData = transactionData,
            batchHash = batchHash,
        )
        transaction.flush()
        WithdrawalEntity.updateToSettling(sequencedWithdrawals, transaction, transactionDataByWithdrawal)
        submitToBlockchain(transaction)

        return true
    }

    private fun onWithdrawalComplete(withdrawalEntity: WithdrawalEntity, error: String?, withdrawAmount: BigInteger?) {
        transaction {
            val broadcasterNotifications = mutableListOf<BroadcasterNotification>()
            val tx = withdrawalEntity.transactionData!! as EIP712Transaction.WithdrawTx
            withdrawalEntity.update(
                status = error?.let { WithdrawalStatus.Failed }
                    ?: WithdrawalStatus.Complete,
                error = error,
                actualAmount = withdrawAmount,
            )
            if (error == null) {
                BalanceEntity.updateBalances(
                    listOf(
                        BalanceChange.Delta(
                            withdrawalEntity.wallet.id.value,
                            withdrawalEntity.symbol.id.value,
                            withdrawAmount!!.negate(),
                        ),
                    ),
                    BalanceType.Exchange,
                )
                broadcasterNotifications.add(BroadcasterNotification.walletBalances(withdrawalEntity.wallet.userGuid.value))
            } else {
                val sequencerResponse = runBlocking {
                    sequencerClient.failWithdraw(
                        withdrawalEntity.wallet.userGuid.value.toSequencerId(),
                        Asset(withdrawalEntity.symbol.name),
                        tx.amount,
                    )
                }
                if (sequencerResponse.error == SequencerError.None) {
                    logger.debug { "Successfully notified sequencer" }
                } else {
                    logger.error { "Sequencer failed with error ${sequencerResponse.error} - fail withdrawals" }
                }
            }

            publishBroadcasterNotifications(broadcasterNotifications)
        }
    }

    private fun getOnChainBatchHash(blockNumber: BigInteger?): String {
        return blockNumber?.let {
            val blockParam = DefaultBlockParam.BlockNumber(it)
            getAsOfBlockOrLater(blockParam) { bp ->
                evmClient.batchHash(bp)
            }
        } ?: evmClient.batchHash(DefaultBlockParam.Latest)
    }

    private fun getOnChainLastSettlementBatchHash(blockNumber: BigInteger?): String {
        return blockNumber?.let {
            val blockParam = DefaultBlockParam.BlockNumber(it)
            getAsOfBlockOrLater(blockParam) { bp ->
                evmClient.lastSettlementBatchHash(bp)
            }
        } ?: evmClient.lastSettlementBatchHash(DefaultBlockParam.Latest)
    }

    private fun getOnChainLastWithdrawalBatchHash(blockNumber: BigInteger?): String {
        return blockNumber?.let {
            val blockParam = DefaultBlockParam.BlockNumber(it)
            getAsOfBlockOrLater(blockParam) { bp ->
                evmClient.lastWithdrawalBatchHash(bp)
            }
        } ?: evmClient.lastWithdrawalBatchHash(DefaultBlockParam.Latest)
    }
}

fun Exchange.WithdrawalFailedEventResponse.toErrorString(): String {
    return when (this.errorCode) {
        BigInteger.ZERO -> "Invalid Signature"
        BigInteger.ONE -> "Insufficient Balance, token(${this.token}), requested=${this.amount}, balance=${this.balance}"
        else -> "Unknown Error"
    }
}
