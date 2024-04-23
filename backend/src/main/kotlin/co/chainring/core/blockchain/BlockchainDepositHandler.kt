package co.chainring.core.blockchain

import co.chainring.contracts.generated.Exchange
import co.chainring.core.model.Address
import co.chainring.core.model.TxHash
import co.chainring.core.model.db.DepositEntity
import co.chainring.core.model.db.DepositStatus
import co.chainring.core.model.db.WalletEntity
import io.github.oshai.kotlinlogging.KotlinLogging
import io.reactivex.Flowable
import org.jetbrains.exposed.sql.transactions.transaction
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.EthFilter
import org.web3j.tx.Contract
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

interface DepositConfirmationHandler {
    fun onExchangeContractDepositConfirmation(deposit: DepositEntity)
}

class BlockchainDepositHandler(
    private val blockchainClient: BlockchainClient,
    private val depositConfirmationHandler: DepositConfirmationHandler,
    private val numConfirmations: Int = System.getenv("BLOCKCHAIN_DEPOSIT_HANDLER_NUM_CONFIRMATIONS")?.toIntOrNull() ?: 1,
    private val pollingIntervalInMs: Long = System.getenv("BLOCKCHAIN_DEPOSIT_HANDLER_POLLING_INTERVAL_MS")?.toLongOrNull() ?: 500L,
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

    fun start() {
        logger.debug { "Starting deposit confirmation handler" }

        registerDepositEventsConsumer()

        workerThread = thread(start = true, name = "deposit-confirmation-handler", isDaemon = true) {
            try {
                logger.debug { "Deposit confirmation handler thread starting" }

                while (true) {
                    Thread.sleep(pollingIntervalInMs)
                    transaction {
                        refreshPendingDeposits()
                    }
                }
            } catch (ie: InterruptedException) {
                logger.warn { "Exiting deposit confirmation handler thread" }
                return@thread
            } catch (e: Exception) {
                logger.error(e) { "Unhandled exception handling pending deposits" }
            }
        }
    }

    private fun registerDepositEventsConsumer() {
        val exchangeContractAddress = blockchainClient.getContractAddress(ContractType.Exchange)!!.value

        val startFromBlock = maxSeenBlockNumber()
            ?: System.getenv("EVM_NETWORK_EARLIEST_BLOCK")?.let { DefaultBlockParameter.valueOf(it.toBigInteger()) }
            ?: DefaultBlockParameterName.EARLIEST

        val filter = EthFilter(startFromBlock, DefaultBlockParameterName.LATEST, exchangeContractAddress)

        blockchainClient.ethLogFlowable(filter)
            .retryWhen { f: Flowable<Throwable> -> f.take(5).delay(300, TimeUnit.MILLISECONDS) }
            .subscribe(
                { eventLog ->
                    // listen to all events of the exchange contract and manually check for DEPOSIT_EVENT
                    // exchangeContract.depositEventFlowable(filter) fails with null pointer on any other event form the contract
                    if (Contract.staticExtractEventParameters(Exchange.DEPOSIT_EVENT, eventLog) != null) {
                        val depositEventResponse = Exchange.getDepositEventFromLog(eventLog)
                        logger.debug { "Received deposit event (from: ${depositEventResponse.from}, amount: ${depositEventResponse.amount}, token: ${depositEventResponse.token}, txHash: ${depositEventResponse.log.transactionHash})" }

                        val blockNumber = depositEventResponse.log.blockNumber
                        val txHash = TxHash(depositEventResponse.log.transactionHash)

                        transaction {
                            if (DepositEntity.findByTxHash(txHash) != null) {
                                logger.debug { "Skipping already recorded deposit (tx hash: $txHash)" }
                            } else {
                                val walletAddress = Address(depositEventResponse.from)
                                val wallet = WalletEntity.getOrCreate(walletAddress)
                                val tokenAddress = Address(depositEventResponse.token).takeIf { it != Address.zero }
                                val amount = depositEventResponse.amount

                                DepositEntity.create(
                                    chainId = chainId,
                                    wallet = wallet,
                                    tokenAddress = tokenAddress,
                                    amount = amount,
                                    blockNumber = blockNumber,
                                    transactionHash = txHash,
                                )
                            }
                        }
                    }
                },
                { throwable: Throwable ->
                    logger.error(throwable) { "Unexpected error occurred while processing deposit events" }
                    registerDepositEventsConsumer()
                },
            )
    }

    private fun refreshPendingDeposits() {
        val pendingDeposits = DepositEntity.getPendingForUpdate(chainId)

        if (pendingDeposits.isNotEmpty()) {
            val currentBlock = blockchainClient.getBlockNumber()

            // handle pending deposits - if we hit required confirmations, invoke callback
            pendingDeposits.forEach {
                refreshPendingDeposit(it, currentBlock)
            }
        }
    }

    private fun refreshPendingDeposit(pendingDeposit: DepositEntity, currentBlock: BigInteger) {
        blockchainClient.getTransactionReceipt(pendingDeposit.transactionHash.value)?.let { receipt ->
            receipt.blockNumber?.let { blockNumber ->
                when (receipt.status) {
                    "0x1" -> {
                        val confirmationsReceived = confirmations(currentBlock, blockNumber)
                        if (confirmationsReceived >= numConfirmations) {
                            pendingDeposit.update(DepositStatus.Confirmed)

                            try {
                                depositConfirmationHandler.onExchangeContractDepositConfirmation(pendingDeposit)
                            } catch (e: Exception) {
                                logger.error(e) { "DepositConfirmationCallback failed for $pendingDeposit" }
                            }

                            pendingDeposit.update(DepositStatus.Complete)
                        }
                    }

                    else -> {
                        val error = receipt.revertReason ?: "Unknown Error"
                        logger.error { "Deposit failed with revert reason $error" }

                        pendingDeposit.update(DepositStatus.Failed, error)
                    }
                }
            }
        }
    }

    private fun maxSeenBlockNumber() = transaction {
        DepositEntity.maxBlockNumber()?.let { DefaultBlockParameter.valueOf(it) }
    }

    private fun confirmations(currentBlock: BigInteger, startingBlock: BigInteger): Int {
        return (currentBlock - startingBlock).toLong().toInt() + 1
    }
}
