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
import co.chainring.core.model.db.SymbolEntity
import co.chainring.core.model.db.TradeEntity
import co.chainring.core.model.db.TxHash
import co.chainring.core.model.db.WalletEntity
import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.model.db.WithdrawalId
import co.chainring.core.model.db.WithdrawalStatus
import co.chainring.core.model.db.publishBroadcasterNotifications
import co.chainring.core.sequencer.SequencerClient
import co.chainring.core.sequencer.toSequencerId
import co.chainring.core.utils.toHex
import co.chainring.sequencer.core.Asset
import co.chainring.sequencer.proto.SequencerError
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.transactions.transaction
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
) {
    private val chainId = blockchainClient.chainId
    private val symbolMap = mutableMapOf<String, SymbolEntity>()
    private var workerThread: Thread? = null
    val logger = KotlinLogging.logger {}

    fun start() {
        logger.debug { "Starting batch transaction handler for $chainId" }
        workerThread = thread(start = true, name = "batch-transaction-handler-$chainId", isDaemon = true) {
            logger.debug { "Batch Transaction handler thread starting" }
            dbTransaction {
                BlockchainNonceEntity.clearNonce(blockchainClient.submitterAddress, chainId)

                // defensive code (may only happen in lower env if we clear out the DB, but don't redeploy proxy)
                // no settlement in progress based on DB but onchain has one in progress so roll it back.
                if (noBatchInProgress(blockchainClient.chainId) && batchInProgressOnChain()) {
                    logger.debug { "rolling back on chain on startup" }
                    rollbackOnChain()
                }
            }
            var settlementBatchInProgress = false
            var withdrawalBatchInProgress = false
            while (true) {
                try {
                    if (withdrawalBatchInProgress) {
                        withdrawalBatchInProgress = dbTransaction {
                            processWithdrawalBatch()
                        }
                    } else {
                        settlementBatchInProgress = dbTransaction {
                            processSettlementBatch()
                        }
                        if (!settlementBatchInProgress) {
                            withdrawalBatchInProgress = dbTransaction {
                                processWithdrawalBatch()
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

    private fun noBatchInProgress(chainId: ChainId): Boolean =
        ChainSettlementBatchEntity.findCurrentBatch(chainId) == null

    private fun processWithdrawalBatch(): Boolean {
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
                    return !refreshSubmittedTransaction(blockchainTx, blockchainClient.getBlockNumber(), numConfirmations) { txReceipt, error ->
                        if (error == null) {
                            // extract the failed withdrawals from the events
                            val failedWithdrawals = txReceipt.logs.mapNotNull { eventLog ->
                                Contract.staticExtractEventParameters(
                                    Exchange.WITHDRAWALFAILED_EVENT,
                                    eventLog,
                                )?.let {
                                    Exchange.getWithdrawalFailedEventFromLog(eventLog)
                                }
                            }

                            val failedSequences = failedWithdrawals.map { failedWithdrawal ->
                                val errorMsg = errorCodeToString(failedWithdrawal.errorCode)
                                logger.error { "Received failed event for sequence ${failedWithdrawal.sequence}, errorMsg=$errorMsg errorCode=${failedWithdrawal.errorCode}" }
                                settlingWithdrawals.firstOrNull { it.sequenceId.toBigInteger() == failedWithdrawal.sequence }?.let {
                                    try {
                                        onTxComplete(it.transactionData!!, errorMsg)
                                    } catch (e: Exception) {
                                        logger.error(e) { "Callback exception for withdrawal with sequence ${it.sequenceId}" }
                                    }
                                }
                                failedWithdrawal.sequence
                            }.toSet()

                            settlingWithdrawals.filterNot { failedSequences.contains(it.sequenceId.toBigInteger()) }.forEach {
                                onTxComplete(it.transactionData!!, null)
                            }
                        } else {
                            // mark all the withdrawals as failed
                            settlingWithdrawals.forEach {
                                onTxComplete(it.transactionData!!, error)
                            }
                        }
                    }
                }
                else -> {}
            }
        }

        return true
    }

    private fun processSettlementBatch(): Boolean {
        val currentBatch = ChainSettlementBatchEntity.findCurrentBatch(chainId)
            ?: return false

        return when (currentBatch.status) {
            SettlementBatchStatus.Preparing -> {
                when (currentBatch.prepararationTx.status) {
                    BlockchainTransactionStatus.Pending -> {
                        // send prepare transaction call
                        submitToBlockchain(currentBatch.prepararationTx)
                    }
                    BlockchainTransactionStatus.Submitted -> {
                        refreshSubmittedTransaction(currentBatch.prepararationTx, blockchainClient.getBlockNumber(), 1) { txReceipt, error ->
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
                                    TradeEntity.markAsFailedSettling(failedTrades.map { it.tradeHashes.map { it.toHex() } }.flatten().toSet(), errorCodeToString(failedTrades.first().errorCode))
                                }
                                currentBatch.markAsPrepared()
                            } else {
                                currentBatch.markAsFailed(error)
                            }
                        }
                    }
                    else -> {}
                }
                true
            }
            SettlementBatchStatus.Submitting -> {
                submitBatch(currentBatch)
                true
            }

            SettlementBatchStatus.RollingBack -> {
                rollbackBatch(currentBatch)
                true
            }
            SettlementBatchStatus.Submitted -> {
                currentBatch.submissionTx?.let { submissionTx ->
                    refreshSubmittedTransaction(submissionTx, blockchainClient.getBlockNumber(), numConfirmations) { _, error ->
                        if (error == null) {
                            currentBatch.markAsCompleted()
                        } else {
                            currentBatch.markAsFailed(error)
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

    private fun batchInProgressOnChain(): Boolean {
        return BigInteger(blockchainClient.exchangeContract.batchHash().send().toHex(false), 16) != BigInteger.ZERO
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

    private fun refreshSubmittedTransaction(tx: BlockchainTransactionEntity, currentBlock: BigInteger, confirmationsNeeded: Int, onComplete: (TransactionReceipt, String?) -> Unit): Boolean {
        val txHash = tx.txHash ?: return true
        val receipt = blockchainClient.getTransactionReceipt(txHash.value) ?: return true

        val receiptBlockNumber = receipt.blockNumber ?: return true

        return when (receipt.status) {
            "0x1" -> {
                val confirmationsReceived = confirmations(currentBlock, receiptBlockNumber)
                if (tx.blockNumber == null) {
                    tx.markAsSeen(blockNumber = receiptBlockNumber)
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
        try {
            val submitterNonce = BlockchainNonceEntity.lockForUpdate(blockchainClient.submitterAddress, chainId)
            val nonce = submitterNonce.nonce?.let { it + BigInteger.ONE } ?: getConsistentNonce(submitterNonce.key)
            val txHash = sendPendingTransaction(tx.transactionData, nonce)
            submitterNonce.nonce = nonce
            tx.markAsSubmitted(txHash)
        } catch (ce: BlockchainClientException) {
            logger.error(ce) { "Failed with client exception, ${ce.message}" }
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

    private fun errorCodeToString(errorCode: BigInteger): String {
        return when (errorCode) {
            BigInteger.ZERO -> "Invalid Signature"
            BigInteger.ONE -> "Insufficient Balance"
            else -> "Unknown Error"
        }
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
            val transactionData = BlockchainTransactionData(
                data = blockchainClient.exchangeContract.submitWithdrawals(
                    sequencedWithdrawals.map { withdrawal ->
                        (
                            withdrawal.transactionData ?: run {
                                withdrawal.toEip712Transaction().also {
                                    transactionDataByWithdrawal[withdrawal.guid.value] = it
                                }
                            }
                            ).getTxData(withdrawal.sequenceId.toLong())
                    },
                ).encodeFunctionCall(),
                to = Address(blockchainClient.exchangeContract.contractAddress),
                value = BigInteger.ZERO,
            )
            val transaction = BlockchainTransactionEntity.create(
                chainId = chainId,
                transactionData = transactionData,
            )
            transaction.flush()
            WithdrawalEntity.updateToSettling(sequencedWithdrawals, transaction, transactionDataByWithdrawal)
            submitToBlockchain(transaction)
            true
        } else {
            false
        }
    }

    private fun getSymbol(asset: String): SymbolEntity {
        return symbolMap.getOrPut(asset) {
            transaction { SymbolEntity.forChainAndName(blockchainClient.chainId, asset) }
        }
    }

    private fun getContractAddress(asset: String): Address {
        return getSymbol(asset).contractAddress ?: Address.zero
    }

    private fun onTxComplete(tx: EIP712Transaction, error: String?) {
        transaction {
            val broadcasterNotifications = mutableListOf<BroadcasterNotification>()
            when (tx) {
                is EIP712Transaction.WithdrawTx -> {
                    WithdrawalEntity.findSettlingByWalletAndNonce(
                        WalletEntity.getByAddress(tx.sender),
                        tx.nonce,
                    )?.let {
                        it.update(
                            status = error?.let { WithdrawalStatus.Failed }
                                ?: WithdrawalStatus.Complete,
                            error = error,
                        )
                        if (error == null) {
                            val finalBalance = runBlocking {
                                blockchainClient.getExchangeBalance(
                                    it.wallet.address,
                                    it.symbol.contractAddress ?: Address.zero,
                                )
                            }
                            BalanceEntity.updateBalances(
                                listOf(
                                    BalanceChange.Replace(
                                        it.wallet.id.value,
                                        it.symbol.id.value,
                                        finalBalance,
                                    ),
                                ),
                                BalanceType.Exchange,
                            )
                            broadcasterNotifications.add(BroadcasterNotification.walletBalances(it.wallet))
                        } else {
                            val sequencerResponse = runBlocking {
                                sequencerClient.failWithdraw(
                                    tx.sender.toSequencerId().value,
                                    Asset(it.symbol.name),
                                    tx.amount,
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
                else -> {}
            }

            publishBroadcasterNotifications(broadcasterNotifications)
        }
    }
}
