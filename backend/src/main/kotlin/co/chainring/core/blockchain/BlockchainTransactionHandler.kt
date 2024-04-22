package co.chainring.core.blockchain

import co.chainring.contracts.generated.Exchange
import co.chainring.core.evm.EIP712Transaction
import co.chainring.core.model.db.BlockchainNonceEntity
import co.chainring.core.model.db.BlockchainTransactionData
import co.chainring.core.model.db.BlockchainTransactionEntity
import co.chainring.core.model.db.BlockchainTransactionStatus
import co.chainring.core.model.db.ExchangeTransactionEntity
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.utils.Numeric
import java.math.BigInteger
import kotlin.concurrent.thread

interface TxConfirmationHandler {
    fun onTxConfirmation(tx: EIP712Transaction, error: String?)
}

class BlockchainTransactionHandler(
    private val blockchainClient: BlockchainClient,
    private val txConfirmationHandler: TxConfirmationHandler,
    private val numConfirmations: Int = System.getenv("BLOCKCHAIN_TX_HANDLER_NUM_CONFIRMATIONS")?.toIntOrNull() ?: 1,
    private val pollingIntervalInMs: Long = System.getenv("BLOCKCHAIN_TX_HANDLER_POLLING_INTERVAL_MS")?.toLongOrNull() ?: 500L,
) {
    private val chainId = blockchainClient.chainId
    private var workerThread: Thread? = null
    val logger = KotlinLogging.logger {}

    fun start() {
        logger.debug { "Starting batch transaction handler" }
        val contractAddress = blockchainClient.getContractAddress(ContractType.Exchange)!!
        val exchange = blockchainClient.loadExchangeContract(contractAddress)
        workerThread = thread(start = true, name = "batch-transaction-handler", isDaemon = true) {
            try {
                logger.debug { "Batch Transaction handler thread starting" }
                transaction {
                    BlockchainNonceEntity.clearNonce(blockchainClient.submitterAddress, chainId)
                }

                while (true) {
                    Thread.sleep(pollingIntervalInMs)
                    handle(exchange, txConfirmationHandler)
                }
            } catch (ie: InterruptedException) {
                logger.warn { "exiting blockchain handler" }
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

    private fun handle(exchange: Exchange, txConfirmationHandler: TxConfirmationHandler) {
        val currentBlock = blockchainClient.getBlockNumber()

        val pendingTxs = transaction {
            BlockchainTransactionEntity.getPending(chainId)
        }

        // handle submitted - if we hit required confirmations, invoke callbacks
        pendingTxs.filter { it.status == BlockchainTransactionStatus.Submitted }.forEach { pendingTx ->
            pendingTx.transactionData.txHash?.let { txHash ->
                blockchainClient.getTransactionReceipt(txHash)?.let { receipt ->
                    logger.debug { "receipt is $receipt" }
                    receipt.blockNumber?.let { blockNumber ->
                        when (receipt.status) {
                            "0x1" -> {
                                val confirmationsReceived = confirmations(currentBlock, blockNumber)
                                if (pendingTx.blockNumber == null) {
                                    transaction {
                                        pendingTx.markAsSeen(blockNumber = blockNumber)
                                    }
                                }
                                if (confirmationsReceived >= numConfirmations) {
                                    transaction {
                                        pendingTx.markAsConfirmed(
                                            gasAccountFee(receipt),
                                            receipt.gasUsed,
                                        )
                                    }

                                    // invoke callbacks for transactions in this confirmed batch
                                    invokeTxCallbacks(pendingTx, txConfirmationHandler)

                                    // mark batch as complete and remove
                                    transaction {
                                        pendingTx.markAsCompleted()
                                    }
                                }
                            }

                            else -> {
                                // update in DB as failed, log an error and remove
                                val error = receipt.revertReason
                                    ?: blockchainClient.extractRevertReasonFromSimulation(pendingTx.transactionData.data, receipt.blockNumber)
                                    ?: "Unknown Error"
                                logger.error { "transaction batch failed with revert reason $error" }

                                invokeTxCallbacks(pendingTx, txConfirmationHandler, error)

                                transaction {
                                    val lockAddress = BlockchainNonceEntity.lockForUpdate(blockchainClient.submitterAddress, chainId)
                                    lockAddress.nonce = null
                                    pendingTx.markAsFailed(
                                        error,
                                        gasAccountFee(receipt),
                                        receipt.gasUsed,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Send any pending batches not submitted yet
        pendingTxs.filter { it.status == BlockchainTransactionStatus.Pending }.forEach { tx ->
            submitToBlockchain(tx, txConfirmationHandler)
        }

        // create the next batch
        createNextBatch(exchange)?.let { tx ->
            submitToBlockchain(tx, txConfirmationHandler)
        }
    }

    private fun submitToBlockchain(tx: BlockchainTransactionEntity, txConfirmationHandler: TxConfirmationHandler) {
        try {
            transaction {
                val lockAddress = BlockchainNonceEntity.lockForUpdate(blockchainClient.submitterAddress, chainId)
                val nonce = lockAddress.nonce?.let { it + BigInteger.ONE } ?: getConsistentNonce(lockAddress.key)
                val transactionData = sendPendingTransaction(tx.transactionData, nonce)
                lockAddress.nonce = transactionData.nonce!!
                tx.markAsSubmitted(transactionData)
            }
        } catch (ce: BlockchainClientException) {
            logger.error(ce) { "Failed with client exception, ${ce.message}" }
            invokeTxCallbacks(tx, txConfirmationHandler, ce.message ?: "Unknown error")
            transaction {
                tx.markAsFailed(ce.message ?: "Unknown error")
            }
        } catch (se: BlockchainServerException) {
            logger.warn(se) { "Failed to send, will retry, ${se.message}" }
        }
    }

    private fun sendPendingTransaction(transactionData: BlockchainTransactionData, nonce: BigInteger): BlockchainTransactionData {
        val txManager = blockchainClient.getTxManager(nonce)
        val gasProvider = blockchainClient.gasProvider

        val response = try {
            txManager.sendEIP1559Transaction(
                gasProvider.chainId,
                gasProvider.getMaxPriorityFeePerGas(""),
                gasProvider.getMaxFeePerGas(""),
                gasProvider.gasLimit,
                transactionData.to,
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
            return transactionData.copy(nonce = nonce, txHash = txHash)
        }
    }

    private fun confirmations(currentBlock: BigInteger, startingBlock: BigInteger): Int {
        return (currentBlock - startingBlock).toLong().toInt() + 1
    }

    private fun invokeTxCallbacks(tx: BlockchainTransactionEntity, txConfirmationHandler: TxConfirmationHandler, error: String? = null) {
        transaction {
            ExchangeTransactionEntity.findExchangeTransactionsForBlockchainTransaction(tx.guid.value)
        }.forEach { exchangeTx ->
            try {
                txConfirmationHandler.onTxConfirmation(exchangeTx.transactionData, error)
            } catch (e: Exception) {
                logger.error(e) { "Callback exception for $tx" }
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

    private fun createNextBatch(exchange: Exchange): BlockchainTransactionEntity? {
        return transaction {
            val unassignedTxs = ExchangeTransactionEntity.findUnassignedExchangeTransactions(blockchainClient.chainId, 50)
            if (unassignedTxs.isNotEmpty()) {
                BlockchainTransactionEntity.create(
                    chainId = chainId,
                    transactionData = BlockchainTransactionData(
                        data = exchange.submitTransactions(unassignedTxs.map { it.transactionData.getTxData() })
                            .encodeFunctionCall(),
                        to = exchange.contractAddress,
                        value = BigInteger.ZERO,
                    ),
                    transactions = unassignedTxs,
                )
            } else {
                null
            }
        }
    }
}
