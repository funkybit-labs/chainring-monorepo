package co.chainring.core.blockchain

import co.chainring.apps.api.model.websocket.TradeUpdated
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
import co.chainring.core.model.db.ExchangeTransactionBatchEntity
import co.chainring.core.model.db.ExchangeTransactionBatchStatus
import co.chainring.core.model.db.ExchangeTransactionEntity
import co.chainring.core.model.db.OrderExecutionEntity
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.SymbolEntity
import co.chainring.core.model.db.TradeEntity
import co.chainring.core.model.db.TxHash
import co.chainring.core.model.db.WalletEntity
import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.model.db.WithdrawalStatus
import co.chainring.core.model.db.publishBroadcasterNotifications
import co.chainring.core.sequencer.SequencerClient
import co.chainring.core.sequencer.toSequencerId
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
        logger.debug { "Starting batch transaction handler" }
        workerThread = thread(start = true, name = "batch-transaction-handler", isDaemon = true) {
            try {
                logger.debug { "Batch Transaction handler thread starting" }
                dbTransaction {
                    BlockchainNonceEntity.clearNonce(blockchainClient.submitterAddress, chainId)
                }

                while (true) {
                    val batchInProgress = dbTransaction {
                        processBatch()
                    }

                    Thread.sleep(
                        if (batchInProgress) activePollingIntervalInMs else inactivePollingIntervalInMs,
                    )
                }
            } catch (ie: InterruptedException) {
                logger.warn { "Exiting blockchain handler" }
                return@thread
            } catch (e: Exception) {
                logger.error(e) { "Unhandled exception submitting tx" }
            }
        }
    }

    fun stop() {
        workerThread?.let {
            it.interrupt()
            it.join(100)
        }
    }

    private fun processBatch(): Boolean {
        val currentBatch = ExchangeTransactionBatchEntity.findCurrentBatch(chainId)
            ?: createNextBatch()
            ?: return false

        return when (currentBatch.status) {
            ExchangeTransactionBatchStatus.Preparing -> {
                when (currentBatch.prepareBlockchainTransaction.status) {
                    BlockchainTransactionStatus.Pending -> {
                        // send prepare transaction call
                        submitToBlockchain(currentBatch.prepareBlockchainTransaction)
                    }
                    BlockchainTransactionStatus.Submitted -> {
                        refreshSubmittedTransaction(currentBatch.prepareBlockchainTransaction, blockchainClient.getBlockNumber(), 1) { txReceipt, error ->
                            if (error == null) {
                                // extract the failed txs from the prepare call
                                val failedTxs = txReceipt.logs.mapNotNull { eventLog ->
                                    Contract.staticExtractEventParameters(
                                        Exchange.PREPARETRANSACTIONFAILED_EVENT,
                                        eventLog,
                                    )?.let {
                                        Exchange.getPrepareTransactionFailedEventFromLog(eventLog)
                                    }
                                }
                                if (failedTxs.isEmpty()) {
                                    currentBatch.markAsPrepared()
                                } else {
                                    failedTxs.forEach { failedTx ->
                                        val errorMsg = errorCodeToString(failedTx.errorCode)
                                        logger.error { "Received failed event for sequence ${failedTx.sequence}, errorMsg=$errorMsg errorCode=${failedTx.errorCode}" }
                                        ExchangeTransactionEntity.findForBatchAndSequence(
                                            currentBatch,
                                            failedTx.sequence.toInt(),
                                        )?.also { exchangeTx ->
                                            exchangeTx.markAsFailed(errorMsg)
                                            try {
                                                onTxComplete(exchangeTx.transactionData, errorMsg)
                                            } catch (e: Exception) {
                                                logger.error(e) { "Callback exception for exchange transaction with sequence ${exchangeTx.sequenceId}" }
                                            }
                                            exchangeTx.refresh(flush = true)
                                        }
                                    }
                                    updateBatch(currentBatch)
                                }
                            } else {
                                currentBatch.markAsFailed(error)
                            }
                        }
                    }
                    else -> {}
                }
                true
            }
            ExchangeTransactionBatchStatus.Prepared -> {
                if (currentBatch.submitBlockchainTransaction == null) {
                    // create blockchain entity and try to submit
                    createSubmitTransaction(currentBatch)?.let {
                        submitBatch(currentBatch, it)
                    }
                } else {
                    // this is a retry case - we created the entity but was not able to submit it
                    if (currentBatch.submitBlockchainTransaction?.status == BlockchainTransactionStatus.Pending) {
                        submitBatch(currentBatch, currentBatch.submitBlockchainTransaction!!)
                    }
                }
                true
            }
            ExchangeTransactionBatchStatus.Submitted -> {
                currentBatch.submitBlockchainTransaction?.let { submitBlockchainTransaction ->
                    refreshSubmittedTransaction(submitBlockchainTransaction, blockchainClient.getBlockNumber(), numConfirmations) { _, error ->
                        if (error == null) {
                            invokeTxCallbacks(currentBatch)
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

    private fun submitBatch(currentBatch: ExchangeTransactionBatchEntity, submitTx: BlockchainTransactionEntity) {
        submitToBlockchain(submitTx)
        if (submitTx.status == BlockchainTransactionStatus.Submitted) {
            currentBatch.markAsSubmitted()
        }
    }

    private fun refreshSubmittedTransaction(tx: BlockchainTransactionEntity, currentBlock: BigInteger, confirmationsNeeded: Int, onComplete: (TransactionReceipt, String?) -> Unit) {
        val txHash = tx.txHash ?: return
        val receipt = blockchainClient.getTransactionReceipt(txHash.value) ?: return

        val receiptBlockNumber = receipt.blockNumber ?: return

        when (receipt.status) {
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

    private fun invokeTxCallbacks(batch: ExchangeTransactionBatchEntity) {
        ExchangeTransactionEntity
            .findAssignedExchangeTransactionsForBatch(batch)
            .forEach { exchangeTx ->
                try {
                    onTxComplete(exchangeTx.transactionData, null)
                } catch (e: Exception) {
                    logger.error(e) { "Callback exception for exchange transaction with sequence ${exchangeTx.sequenceId}" }
                }
            }
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

    private fun updateBatch(batch: ExchangeTransactionBatchEntity): BlockchainTransactionEntity? {
        val assignedTxs = ExchangeTransactionEntity.findAssignedExchangeTransactionsForBatch(batch)
        return if (assignedTxs.isNotEmpty()) {
            BlockchainTransactionEntity.create(
                chainId = chainId,
                transactionData = BlockchainTransactionData(
                    data = blockchainClient.exchangeContract.prepareBatch(assignedTxs.map { it.transactionData.getTxData(it.sequenceId.toLong()) }).encodeFunctionCall(),
                    to = Address(blockchainClient.exchangeContract.contractAddress),
                    value = BigInteger.ZERO,
                ),
            ).also {
                batch.prepareBlockchainTransaction = it
            }
        } else {
            batch.markAsCompleted()
            null
        }
    }

    private fun createSubmitTransaction(batch: ExchangeTransactionBatchEntity): BlockchainTransactionEntity? {
        val assignedTxs = ExchangeTransactionEntity.findAssignedExchangeTransactionsForBatch(batch)
        return if (assignedTxs.isNotEmpty()) {
            BlockchainTransactionEntity.create(
                chainId = chainId,
                transactionData = BlockchainTransactionData(
                    data = blockchainClient.exchangeContract.submitBatch(assignedTxs.map { it.transactionData.getTxData(it.sequenceId.toLong()) }).encodeFunctionCall(),
                    to = Address(blockchainClient.exchangeContract.contractAddress),
                    value = BigInteger.ZERO,
                ),
            ).also {
                batch.submitBlockchainTransaction = it
            }
        } else {
            null
        }
    }

    private fun createNextBatch(): ExchangeTransactionBatchEntity? {
        val unassignedTxs = ExchangeTransactionEntity.findUnassignedExchangeTransactions(blockchainClient.chainId, 50)
        return if (unassignedTxs.isNotEmpty()) {
            val transactionData = BlockchainTransactionData(
                data = blockchainClient.exchangeContract.prepareBatch(unassignedTxs.map { it.transactionData.getTxData(it.sequenceId.toLong()) }).encodeFunctionCall(),
                to = Address(blockchainClient.exchangeContract.contractAddress),
                value = BigInteger.ZERO,
            )
            ExchangeTransactionBatchEntity.create(
                chainId = chainId,
                transactions = unassignedTxs,
                BlockchainTransactionEntity.create(
                    chainId = chainId,
                    transactionData = transactionData,
                ),
            )
        } else {
            null
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
                    WithdrawalEntity.findPendingByWalletAndNonce(
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
                            val response = runBlocking {
                                sequencerClient.failWithdraw(
                                    tx.sender.toSequencerId().value,
                                    Asset(it.symbol.name),
                                    tx.amount,
                                )
                            }
                            if (response.error == SequencerError.None) {
                                logger.debug { "Successfully notified sequencer" }
                            } else {
                                logger.error { "Sequencer failed with error ${response.error} - fail withdrawals" }
                            }
                        }
                    }
                }

                is EIP712Transaction.Order -> {}

                is EIP712Transaction.CancelOrder -> {}

                is EIP712Transaction.Trade -> {
                    TradeEntity.findById(tx.tradeId)?.let { tradeEntity ->
                        val executions = OrderExecutionEntity.findForTrade(tradeEntity)

                        if (error != null) {
                            logger.error { "settlement failed for ${tx.tradeId} - error is <$error>" }
                            tradeEntity.failSettlement(error)
                            val buyOrder = executions.first { it.order.side == OrderSide.Buy }.order
                            val sellOrder = executions.first { it.order.side == OrderSide.Sell }.order
                            val response = runBlocking {
                                sequencerClient.failSettlement(
                                    buyWallet = buyOrder.wallet.address.toSequencerId().value,
                                    sellWallet = sellOrder.wallet.address.toSequencerId().value,
                                    marketId = tradeEntity.marketGuid.value,
                                    buyOrderId = buyOrder.guid.value,
                                    sellOrderId = sellOrder.guid.value,
                                    amount = tradeEntity.amount,
                                    price = tradeEntity.price,
                                )
                            }
                            if (response.error == SequencerError.None) {
                                logger.debug { "Successfully notified sequencer" }
                            } else {
                                logger.error { "Sequencer failed with error ${response.error} - failed settlements" }
                            }
                        } else {
                            logger.debug { "settlement completed for ${tx.tradeId}" }
                            tradeEntity.settle()

                            // update the onchain balances
                            val wallets = executions.map { it.order.wallet }
                            val symbols = listOf(
                                executions.first().order.market.baseSymbol,
                                executions.first().order.market.quoteSymbol,
                            )
                            val finalExchangeBalances = runBlocking {
                                blockchainClient.getExchangeBalances(
                                    wallets.map { it.address },
                                    symbols.map { getContractAddress(it.name) },
                                )
                            }

                            BalanceEntity.updateBalances(
                                wallets.map { wallet ->
                                    symbols.map { symbol ->
                                        BalanceChange.Replace(
                                            walletId = wallet.guid.value,
                                            symbolId = symbol.guid.value,
                                            amount = finalExchangeBalances.getValue(wallet.address).getValue(
                                                getContractAddress(
                                                    symbol.name,
                                                ),
                                            ),
                                        )
                                    }
                                }.flatten(),
                                BalanceType.Exchange,
                            )

                            wallets.forEach { wallet ->
                                broadcasterNotifications.add(BroadcasterNotification.walletBalances(wallet))
                            }
                        }

                        executions.forEach { execution ->
                            broadcasterNotifications.add(
                                BroadcasterNotification(
                                    TradeUpdated(execution.toTradeResponse()),
                                    recipient = execution.order.wallet.address,
                                ),
                            )
                        }
                    }
                }
            }

            publishBroadcasterNotifications(broadcasterNotifications)
        }
    }
}
