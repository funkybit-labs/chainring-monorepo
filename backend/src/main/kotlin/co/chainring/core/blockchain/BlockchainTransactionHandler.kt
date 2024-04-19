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
import co.chainring.core.model.db.ExchangeTransactionEntity
import co.chainring.core.model.db.OrderExecutionEntity
import co.chainring.core.model.db.SymbolEntity
import co.chainring.core.model.db.TradeEntity
import co.chainring.core.model.db.TxHash
import co.chainring.core.model.db.WalletEntity
import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.model.db.WithdrawalStatus
import co.chainring.core.model.db.publishBroadcasterNotifications
import co.chainring.core.utils.BroadcasterNotifications
import co.chainring.core.utils.add
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.transactions.transaction
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.utils.Numeric
import java.math.BigInteger
import kotlin.concurrent.thread
import org.jetbrains.exposed.sql.transactions.transaction as dbTransaction

interface TxConfirmationHandler {
    fun onTxConfirmation(tx: EIP712Transaction, error: String?)
}

class BlockchainTransactionHandler(
    private val blockchainClient: BlockchainClient,
    private val numConfirmations: Int = System.getenv("BLOCKCHAIN_TX_HANDLER_NUM_CONFIRMATIONS")?.toIntOrNull() ?: 1,
    private val pollingIntervalInMs: Long = System.getenv("BLOCKCHAIN_TX_HANDLER_POLLING_INTERVAL_MS")?.toLongOrNull() ?: 500L,
) {
    private val chainId = blockchainClient.chainId
    private val symbolMap = mutableMapOf<String, SymbolEntity>()
    private var workerThread: Thread? = null
    val logger = KotlinLogging.logger {}

    fun start() {
        logger.debug { "Starting batch transaction handler" }
        val contractAddress = blockchainClient.getContractAddress(ContractType.Exchange)!!
        val exchange = blockchainClient.loadExchangeContract(contractAddress)
        workerThread = thread(start = true, name = "batch-transaction-handler", isDaemon = true) {
            try {
                logger.debug { "Batch Transaction handler thread starting" }
                dbTransaction {
                    BlockchainNonceEntity.clearNonce(blockchainClient.submitterAddress, chainId)
                }

                while (true) {
                    Thread.sleep(pollingIntervalInMs)

                    dbTransaction {
                        processUncompletedTransactions()
                        createNextPendingTransaction(exchange)
                            ?.also(::submitToBlockchain)
                    }
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

    private fun processUncompletedTransactions() {
        val uncompletedTransactions = BlockchainTransactionEntity.getUncompletedForUpdate(chainId)

        if (uncompletedTransactions.isNotEmpty()) {
            val currentBlock = blockchainClient.getBlockNumber()

            // Refresh submitted - if we hit required confirmations, invoke callbacks
            uncompletedTransactions
                .filter { it.status == BlockchainTransactionStatus.Submitted }
                .forEach { tx -> refreshSubmittedTransaction(tx, currentBlock) }

            // Send any pending batches not submitted yet
            uncompletedTransactions
                .filter { it.status == BlockchainTransactionStatus.Pending }
                .forEach(::submitToBlockchain)
        }
    }

    private fun refreshSubmittedTransaction(tx: BlockchainTransactionEntity, currentBlock: BigInteger) {
        val txHash = tx.txHash ?: return
        val receipt = blockchainClient.getTransactionReceipt(txHash.value) ?: return

        logger.debug { "receipt is $receipt" }

        val receiptBlockNumber = receipt.blockNumber ?: return

        when (receipt.status) {
            "0x1" -> {
                val confirmationsReceived = confirmations(currentBlock, receiptBlockNumber)
                if (tx.blockNumber == null) {
                    tx.markAsSeen(blockNumber = receiptBlockNumber)
                }
                if (confirmationsReceived >= numConfirmations) {
                    tx.markAsConfirmed(gasAccountFee(receipt), receipt.gasUsed)

                    // invoke callbacks for transactions in this confirmed batch
                    invokeTxCallbacks(tx)

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

                logger.error { "transaction batch failed with revert reason $error" }

                invokeTxCallbacks(tx, error)

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
            invokeTxCallbacks(tx, ce.message ?: "Unknown error")
            tx.markAsFailed(ce.message ?: "Unknown error")
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

    private fun confirmations(currentBlock: BigInteger, startingBlock: BigInteger): Int {
        return (currentBlock - startingBlock).toLong().toInt() + 1
    }

    private fun invokeTxCallbacks(tx: BlockchainTransactionEntity, error: String? = null) {
        ExchangeTransactionEntity
            .findExchangeTransactionsForBlockchainTransaction(tx.guid.value)
            .forEach { exchangeTx ->
                try {
                    onTxConfirmation(exchangeTx.transactionData, error)
                } catch (e: Exception) {
                    logger.error(e) { "Callback exception for transaction ${tx.guid.value}" }
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

    private fun createNextPendingTransaction(exchange: Exchange): BlockchainTransactionEntity? {
        val unassignedTxs = ExchangeTransactionEntity.findUnassignedExchangeTransactions(blockchainClient.chainId, 50)
        return if (unassignedTxs.isNotEmpty()) {
            BlockchainTransactionEntity.create(
                chainId = chainId,
                transactionData = BlockchainTransactionData(
                    data = exchange
                        .submitTransactions(unassignedTxs.map { it.transactionData.getTxData() })
                        .encodeFunctionCall(),
                    to = Address(exchange.contractAddress),
                    value = BigInteger.ZERO,
                ),
                transactions = unassignedTxs,
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

    private fun onTxConfirmation(tx: EIP712Transaction, error: String?) {
        transaction {
            val broadcasterNotifications: BroadcasterNotifications = mutableMapOf()
            when (tx) {
                is EIP712Transaction.WithdrawTx -> {
                    WithdrawalEntity.findPendingByWalletAndNonce(
                        WalletEntity.getByAddress(tx.sender)!!,
                        tx.nonce,
                    )?.let {
                        it.update(
                            status = error?.let { WithdrawalStatus.Failed }
                                ?: WithdrawalStatus.Complete,
                            error = error,
                        )
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
                    }
                }

                is EIP712Transaction.Order -> {}

                is EIP712Transaction.Trade -> {
                    TradeEntity.findById(tx.tradeId)?.let { tradeEntity ->
                        if (error != null) {
                            BlockchainClient.logger.error { "settlement failed for ${tx.tradeId} - error is <$error>" }
                            tradeEntity.failSettlement()
                        } else {
                            BlockchainClient.logger.debug { "settlement completed for ${tx.tradeId}" }
                            tradeEntity.settle()
                        }

                        val executions = OrderExecutionEntity.findForTrade(tradeEntity)
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

                        executions.forEach { execution ->
                            broadcasterNotifications.add(execution.order.wallet.address, TradeUpdated(execution.toTradeResponse()))
                        }
                    }
                }
            }

            publishBroadcasterNotifications(
                broadcasterNotifications.flatMap { (address, notifications) ->
                    notifications.map { BroadcasterNotification(it, address) }
                },
            )
        }
    }
}
