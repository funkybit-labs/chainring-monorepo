package co.chainring.core.blockchain

import co.chainring.contracts.generated.Exchange
import co.chainring.core.model.db.BlockchainNonceEntity
import co.chainring.core.model.db.BlockchainTransactionData
import co.chainring.core.model.db.BlockchainTransactionEntity
import co.chainring.core.model.db.BlockchainTransactionStatus
import co.chainring.core.model.db.ExchangeTransactionEntity
import co.chainring.core.services.TxConfirmationCallback
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction
import org.web3j.crypto.Credentials
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.utils.Numeric
import java.math.BigInteger
import kotlin.concurrent.thread

class BlockchainTransactionHandler(
    private val blockchainClient: BlockchainClient,
    private val submitterCredentials: Credentials,
    private val numConfirmations: Int,
    private val pollingIntervalInMs: Long,
) {

    private val chainId = blockchainClient.chainId
    private var workerThread: Thread? = null
    val logger = KotlinLogging.logger {}

    fun stop() {
        workerThread?.let {
            it.interrupt()
            it.join(100)
        }
    }

    fun start(txConfirmationCallback: TxConfirmationCallback) {
        logger.debug { "Starting batch transaction handler" }
        val contractAddress = blockchainClient.getContractAddress(ContractType.Exchange)!!
        val exchange = blockchainClient.loadExchangeContract(contractAddress)
        workerThread = thread(start = true, name = "batch-transaction-handler", isDaemon = true) {
            try {
                BlockchainClient.logger.debug { "Batch Transaction handler thread starting" }
                transaction {
                    BlockchainNonceEntity.clearNonce(submitterCredentials.address, chainId)
                }

                while (true) {
                    Thread.sleep(pollingIntervalInMs)
                    handle(exchange, txConfirmationCallback)
                }
            } catch (ie: InterruptedException) {
                BlockchainClient.logger.warn { "exiting blockchain handler" }
                return@thread
            } catch (e: Exception) {
                BlockchainClient.logger.error(e) { "Unhandled exception submitting tx" }
            }
        }
    }

    private fun handle(exchange: Exchange, txConfirmationCallback: TxConfirmationCallback) {
        val currentBlock = blockchainClient.getBlockNumber()

        val pendingTxs = transaction {
            BlockchainTransactionEntity.getPending(chainId)
        }

        // handle submitted - if we hit required confirmations, invoke callbacks
        pendingTxs.filter { it.status == BlockchainTransactionStatus.Submitted }.forEach { pendingTx ->
            pendingTx.transactionData.txHash?.let { txHash ->
                blockchainClient.getTransactionReceipt(txHash)?.let { receipt ->
                    BlockchainClient.logger.debug { "receipt is $receipt" }
                    receipt.blockNumber?.let { blockNumber ->
                        when (receipt.status) {
                            "0x1" -> {
                                val confirmationsReceived = confirmations(currentBlock, blockNumber)
                                if (pendingTx.blockNumber == null) {
                                    transaction {
                                        pendingTx.markAsSeen(blockNumber = blockNumber)
                                    }
                                }
                                if (numConfirmations >= confirmationsReceived) {
                                    transaction {
                                        pendingTx.markAsConfirmed(
                                            gasAccountFee(receipt),
                                            receipt.gasUsed,
                                        )
                                    }

                                    // invoke callbacks for transactions in this confirmed batch
                                    invokeTxCallbacks(pendingTx, txConfirmationCallback)

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
                                BlockchainClient.logger.error { "transaction batch failed with revert reason $error" }

                                invokeTxCallbacks(pendingTx, txConfirmationCallback, error)

                                transaction {
                                    val lockAddress = BlockchainNonceEntity.lockForUpdate(submitterCredentials.address, chainId)
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
            submitToBlockchain(tx, txConfirmationCallback)
        }

        // create the next batch
        createNextBatch(exchange)?.let { tx ->
            submitToBlockchain(tx, txConfirmationCallback)
        }
    }

    private fun submitToBlockchain(tx: BlockchainTransactionEntity, txConfirmationCallback: TxConfirmationCallback) {
        try {
            transaction {
                val lockAddress = BlockchainNonceEntity.lockForUpdate(submitterCredentials.address, chainId)
                val nonce = lockAddress.nonce?.let { it + BigInteger.ONE } ?: getConsistentNonce(lockAddress.key)
                val transactionData = blockchainClient.getTxManager(nonce).sendPendingTransaction(
                    tx.transactionData,
                    blockchainClient.gasProvider,
                )
                lockAddress.nonce = transactionData.nonce!!
                tx.markAsSubmitted(transactionData)
            }
        } catch (ce: BlockchainClientException) {
            logger.error(ce) { "failed with client exception, ${ce.message}" }
            invokeTxCallbacks(tx, txConfirmationCallback, ce.message ?: "Unknown error")
            transaction {
                tx.markAsFailed(ce.message ?: "Unknown error")
            }
        } catch (se: BlockchainServerException) {
            logger.warn { "failed to send, will retry, ${se.message}" }
        }
    }

    private fun confirmations(currentBlock: BigInteger, startingBlock: BigInteger): Int {
        return (currentBlock - startingBlock).toLong().toInt() + 1
    }

    private fun invokeTxCallbacks(tx: BlockchainTransactionEntity, txConfirmationCallback: TxConfirmationCallback, error: String? = null) {
        val exchangeTransactions = transaction {
            ExchangeTransactionEntity.findExchangeTransactionsForBlockchainTransaction(tx.guid.value).map { it.transactionData }
        }
        exchangeTransactions.forEach { tx ->
            try {
                txConfirmationCallback.onTxConfirmation(tx, error)
            } catch (e: Exception) {
                BlockchainClient.logger.error { "Callback exception for $tx" }
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
                BlockchainClient.logger.error { "Got inconsistent nonces, retrying" }
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
