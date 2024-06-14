package co.chainring.core.blockchain

import co.chainring.contracts.generated.Exchange
import co.chainring.core.evm.EIP712Transaction
import co.chainring.core.model.Address
import co.chainring.core.model.db.BalanceChange
import co.chainring.core.model.db.BalanceEntity
import co.chainring.core.model.db.BalanceType
import co.chainring.core.model.db.BlockchainNonceEntity
import co.chainring.core.model.db.BlockchainTransactionData
import co.chainring.core.model.db.BlockchainTransactionEntity
import co.chainring.core.model.db.BlockchainTransactionStatus
import co.chainring.core.model.db.BroadcasterNotification
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.ChainSettlementBatchEntity
import co.chainring.core.model.db.SettlementBatchStatus
import co.chainring.core.model.db.TradeEntity
import co.chainring.core.model.db.TxHash
import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.model.db.WithdrawalId
import co.chainring.core.model.db.WithdrawalStatus
import co.chainring.core.model.db.publishBroadcasterNotifications
import co.chainring.core.sequencer.SequencerClient
import co.chainring.core.sequencer.toSequencerId
import co.chainring.core.utils.toHex
import co.chainring.core.utils.toHexBytes
import co.chainring.core.utils.tryAcquireAdvisoryLock
import co.chainring.sequencer.core.Asset
import co.chainring.sequencer.proto.SequencerError
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.transactions.transaction
import org.web3j.crypto.Hash.sha3
import org.web3j.crypto.RawTransaction
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.tx.Contract
import org.web3j.utils.Numeric
import java.math.BigInteger
import kotlin.concurrent.thread
import org.jetbrains.exposed.sql.transactions.transaction as dbTransaction

class BlockchainTransactionHandler(
    private val blockchainClient: BlockchainClient,
    private val sequencerClient: SequencerClient,
    private val numConfirmations: Int = System.getenv("BLOCKCHAIN_TX_HANDLER_NUM_CONFIRMATIONS")?.toIntOrNull() ?: 1,
    private val activePollingIntervalInMs: Long = System.getenv("BLOCKCHAIN_TX_HANDLER_ACTIVE_POLLING_INTERVAL_MS")?.toLongOrNull() ?: 100L,
    private val inactivePollingIntervalInMs: Long = System.getenv("BLOCKCHAIN_TX_HANDLER_INACTIVE_POLLING_INTERVAL_MS")?.toLongOrNull() ?: 500L,
    private val failurePollingIntervalInMs: Long = System.getenv("BLOCKCHAIN_TX_HANDLER_FAILURE_POLLING_INTERVAL_MS")?.toLongOrNull() ?: 2000L,
    private val maxUnseenBlocksForFork: Int = System.getenv("BLOCKCHAIN_TX_HANDLER_MAX_UNSEEN_BLOCKS_FOR_FORK")?.toIntOrNull() ?: 6,
) {
    private val chainId = blockchainClient.chainId
    private var workerThread: Thread? = null
    val logger = KotlinLogging.logger {}

    fun start() {
        logger.debug { "Starting batch transaction handler for $chainId" }
        workerThread = thread(start = true, name = "batch-transaction-handler-$chainId", isDaemon = true) {
            logger.debug { "Batch Transaction handler thread starting" }
            dbTransaction {
                BlockchainNonceEntity.clearNonce(blockchainClient.submitterAddress, chainId)

                try {
                    if (tryAcquireAdvisoryLock(chainId.value.toLong())) {
                        // this is primarily to handle cases where we sent something on chain but process exited
                        // before tx that updated the blockchain entity was committed,so looks like it was not sent onchain yet.
                        val withdrawalsSettling = WithdrawalEntity.findSettling(chainId)
                        if (withdrawalsSettling.isNotEmpty()) {
                            syncWithdrawalWithBlockchain(withdrawalsSettling)
                        } else {
                            val inProgressSettlementBatch = ChainSettlementBatchEntity.findInProgressBatch(chainId)
                            if (inProgressSettlementBatch != null) {
                                syncSettlementWithBlockchain(inProgressSettlementBatch)
                            } else if (batchInProgressOnChain()) {
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
        }
    }

    private fun syncSettlementWithBlockchain(inProgressSettlementBatch: ChainSettlementBatchEntity) {
        when (inProgressSettlementBatch.status) {
            SettlementBatchStatus.Preparing -> {
                when (inProgressSettlementBatch.prepararationTx.status) {
                    BlockchainTransactionStatus.Pending -> {
                        if (inProgressSettlementBatch.prepararationTx.batchHash == blockchainClient.batchHash()) {
                            // batch with this hash is already prepared on onchain
                            inProgressSettlementBatch.markAsPrepared()
                        } else {
                            // check if in mempool and if so update txHash and mark tx submitted
                            val txHash = getTxHash(inProgressSettlementBatch.prepararationTx.transactionData)
                            blockchainClient.getTransactionByHash(txHash.value)?.let {
                                inProgressSettlementBatch.prepararationTx.markAsSubmitted(txHash, it.blockNumber)
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
                            if (submissionTx.batchHash == blockchainClient.lastSettlementBatchHash()) {
                                // this batch is the last one successfully settled, so mark as submitted
                                inProgressSettlementBatch.markAsCompleted()
                            } else {
                                val txHash = getTxHash(submissionTx.transactionData)
                                blockchainClient.getTransactionByHash(txHash.value)?.let {
                                    submissionTx.markAsSubmitted(txHash, it.blockNumber)
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
                if (blockchainTx.batchHash == blockchainClient.lastWithdrawalBatchHash()) {
                    completeWithdrawals(settlingWithdrawals)
                } else {
                    // check if in mempool and if so update txHash and mark tx submitted
                    val txHash = getTxHash(blockchainTx.transactionData)
                    blockchainClient.getTransactionByHash(txHash.value)?.let {
                        blockchainTx.markAsSubmitted(txHash, it.blockNumber)
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
                        currentBlock = blockchainClient.getBlockNumber(),
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
                when (inProgressBatch.prepararationTx.status) {
                    BlockchainTransactionStatus.Pending -> {
                        // send prepare transaction call
                        submitToBlockchain(inProgressBatch.prepararationTx)
                    }
                    BlockchainTransactionStatus.Submitted -> {
                        refreshSubmittedTransaction(
                            tx = inProgressBatch.prepararationTx,
                            currentBlock = blockchainClient.getBlockNumber(),
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
                                        logger.warn { "Failed trade address=${it._address}, needed=${it.requestedAmount} balance=${it.balance}" }
                                    }
                                    TradeEntity.markAsFailedSettling(failedTrades.map { it.tradeHashes.map { it.toHex() } }.flatten().toSet(), "Insufficient Balance")
                                }
                                inProgressBatch.markAsPrepared()
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
                        currentBlock = blockchainClient.getBlockNumber(),
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
        val onChainBatchHash = blockchainClient.batchHash()
        logger.debug { "batchHash stored = ${batch.prepararationTx.batchHash} from contract = $onChainBatchHash" }
        if (batch.prepararationTx.batchHash == onChainBatchHash) {
            // batch with this hash is already prepared on onchain (anvil restarted case)
            batch.markAsPrepared()
        } else {
            // fork case
            batch.prepararationTx.status = BlockchainTransactionStatus.Pending
        }
    }

    private fun onSubmissionTxNotFound(batch: ChainSettlementBatchEntity) {
        val lastSuccessfulBatchHash = blockchainClient.lastSettlementBatchHash()
        batch.submissionTx?.let { submissionTx ->
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
        val onChainBatchHash = blockchainClient.lastWithdrawalBatchHash()
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

    private fun batchInProgressOnChain(): Boolean {
        return BigInteger(blockchainClient.batchHash(), 16) != BigInteger.ZERO
    }

    private fun rollbackBatch(currentBatch: ChainSettlementBatchEntity) {
        // rollback if a batch in progress on chain already
        if (batchInProgressOnChain()) {
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
        val receipt = blockchainClient.getTransactionReceipt(txHash.value)
        if (receipt == null) {
            if (blockchainClient.getTransactionByHash(txHash.value) == null) {
                tx.lastSeenBlock?.let { lastSeenBlock ->
                    logger.debug { "Tx Not found - currentBlock=$currentBlock lastSeen=$lastSeenBlock maxUnseen=$maxUnseenBlocksForFork" }
                    if (currentBlock > lastSeenBlock + maxUnseenBlocksForFork.toBigInteger()) {
                        logger.debug { "Transaction not found in $maxUnseenBlocksForFork blocks, handling as a fork" }
                        BlockchainNonceEntity.clearNonce(blockchainClient.submitterAddress, chainId)
                        onTxNotFound()
                    }
                }
            } else {
                tx.markAsSeen(currentBlock)
            }
            return true
        }
        tx.markAsSeen(currentBlock)

        val receiptBlockNumber = receipt.blockNumber ?: return true

        return when (receipt.status) {
            "0x1" -> {
                val confirmationsReceived = confirmations(currentBlock, receiptBlockNumber)
                if (tx.blockNumber == null) {
                    tx.updateBlockProcessed(blockNumber = receiptBlockNumber)
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
                    ?: blockchainClient.extractRevertReasonFromSimulation(
                        tx.transactionData.data,
                        receipt.blockNumber,
                    )
                    ?: "Unknown Error"

                logger.error { "transaction failed with revert reason $error" }

                onComplete(receipt, error)

                val submitterNonce = BlockchainNonceEntity.lockForUpdate(blockchainClient.submitterAddress, chainId)
                submitterNonce.nonce = null
                tx.markAsFailed(error, gasAccountFee(receipt), receipt.gasUsed)

                true
            }
        }
    }

    private fun submitToBlockchain(tx: BlockchainTransactionEntity) {
        val submitterNonce = BlockchainNonceEntity.lockForUpdate(blockchainClient.submitterAddress, chainId)
        val blockNumber = blockchainClient.getBlockNumber()
        try {
            val nonce = submitterNonce.nonce?.let { it + BigInteger.ONE } ?: getConsistentNonce(submitterNonce.key)
            logger.debug { "sending Tx with nonce $nonce" }
            val txHash = sendPendingTransaction(tx.transactionData, nonce)
            submitterNonce.nonce = nonce
            tx.markAsSubmitted(txHash, blockNumber)
        } catch (ce: BlockchainClientException) {
            logger.warn(ce) { "Failed with client exception, ${ce.message}" }
            BlockchainNonceEntity.clearNonce(blockchainClient.submitterAddress, chainId)
        } catch (se: BlockchainServerException) {
            logger.warn(se) { "Failed to send, will retry, ${se.message}" }
        }
    }

    private fun rollbackOnChain() {
        try {
            val submitterNonce = BlockchainNonceEntity.lockForUpdate(blockchainClient.submitterAddress, chainId)
            val nonce = submitterNonce.nonce?.let { it + BigInteger.ONE } ?: getConsistentNonce(submitterNonce.key)
            sendPendingTransaction(
                BlockchainTransactionData(
                    blockchainClient.exchangeContract.rollbackBatch().encodeFunctionCall(),
                    blockchainClient.getContractAddress(ContractType.Exchange),
                    BigInteger.ZERO,
                ),
                nonce,
            )
            submitterNonce.nonce = nonce
        } catch (ce: BlockchainClientException) {
            logger.error(ce) { "Failed with client exception, ${ce.message}" }
        } catch (se: BlockchainServerException) {
            logger.warn(se) { "Failed to send, will retry, ${se.message}" }
        }
    }

    private fun sendPendingTransaction(transactionData: BlockchainTransactionData, nonce: BigInteger): TxHash {
        val txManager = blockchainClient.getTxManager(nonce)
        val gasProvider = blockchainClient.gasProvider

        val response = try {
            txManager.sendEIP1559Transaction(
                gasProvider.chainId,
                gasProvider.getMaxPriorityFeePerGas(""),
                gasProvider.getMaxFeePerGas(""),
                gasProvider.gasLimit,
                transactionData.to.value,
                transactionData.data,
                transactionData.value,
            )
        } catch (e: Exception) {
            throw BlockchainServerException(e.message ?: "Unknown error")
        }

        val txHash = response.transactionHash
        val error = response.error

        if (txHash == null) {
            throw error
                ?.let { BlockchainClientException(error.message) }
                ?: BlockchainServerException("Unknown error")
        } else {
            return TxHash(txHash)
        }
    }

    private fun getTxHash(transactionData: BlockchainTransactionData): TxHash {
        val submitterNonce = BlockchainNonceEntity.lockForUpdate(blockchainClient.submitterAddress, chainId)
        val nonce = getConsistentNonce(submitterNonce.key)

        val txManager = blockchainClient.getTxManager(nonce)
        val gasProvider = blockchainClient.gasProvider

        return TxHash(
            sha3(
                txManager.sign(
                    RawTransaction.createTransaction(
                        gasProvider.chainId,
                        nonce,
                        gasProvider.gasLimit,
                        transactionData.to.value,
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
            candidateNonce = blockchainClient.getNonce(address)
            isConsistent = (1..2).map { blockchainClient.getNonce(address) }.all { it == candidateNonce }
            if (!isConsistent) {
                logger.error { "Got inconsistent nonces, retrying" }
                Thread.sleep(100)
            }
        }
        return candidateNonce!!
    }

    private fun createNextWithdrawalBatch(): Boolean {
        val sequencedWithdrawals = WithdrawalEntity.findSequenced(blockchainClient.chainId, 50)
        return if (sequencedWithdrawals.isNotEmpty()) {
            val transactionDataByWithdrawal = mutableMapOf<WithdrawalId, EIP712Transaction>()
            val withdrawals = sequencedWithdrawals.map { withdrawal ->
                (
                    withdrawal.transactionData ?: run {
                        withdrawal.toEip712Transaction().also {
                            transactionDataByWithdrawal[withdrawal.guid.value] = it
                        }
                    }
                    ).getTxData(withdrawal.sequenceId.toLong())
            }
            val batchHash = sha3(withdrawals.map { sha3(it) }.reduce { a, b -> a + b }).toHex(false)
            logger.debug { "calculated withdrawal hash = $batchHash" }
            val transactionData = BlockchainTransactionData(
                data = blockchainClient.exchangeContract.submitWithdrawals(
                    withdrawals,
                ).encodeFunctionCall(),
                to = Address(blockchainClient.exchangeContract.contractAddress),
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
            true
        } else {
            false
        }
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
                broadcasterNotifications.add(BroadcasterNotification.walletBalances(withdrawalEntity.wallet))
            } else {
                val sequencerResponse = runBlocking {
                    sequencerClient.failWithdraw(
                        tx.sender.toSequencerId().value,
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
}

fun Exchange.WithdrawalFailedEventResponse.toErrorString(): String {
    return when (this.errorCode) {
        BigInteger.ZERO -> "Invalid Signature"
        BigInteger.ONE -> "Insufficient Balance, token(${this.token}), requested=${this.amount}, balance=${this.balance}"
        else -> "Unknown Error"
    }
}
