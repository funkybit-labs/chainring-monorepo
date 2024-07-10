package co.chainring.apps.ring

import co.chainring.contracts.generated.Exchange
import co.chainring.core.blockchain.BlockchainClient
import co.chainring.core.model.Address
import co.chainring.core.model.TxHash
import co.chainring.core.model.db.BlockEntity
import co.chainring.core.model.db.BlockHash
import co.chainring.core.model.db.BlockTable
import co.chainring.core.model.db.DepositEntity
import co.chainring.core.model.db.SymbolEntity
import co.chainring.core.model.db.WalletEntity
import co.chainring.core.utils.rangeTo
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.web3j.crypto.Keys
import org.web3j.protocol.core.methods.response.EthLog
import org.web3j.protocol.core.methods.response.Log
import org.web3j.tx.Contract
import java.math.BigInteger
import kotlin.concurrent.thread

class BlockProcessor(
    private val blockchainClient: BlockchainClient,
    private val pollingIntervalInMs: Long =
        System.getenv("BLOCKCHAIN_BLOCK_PROCESSOR_POLLING_INTERVAL_MS")?.toLongOrNull()
            ?: 1000L,
) {
    val logger = KotlinLogging.logger {}
    private val chainId = blockchainClient.chainId
    private var workerThread: Thread? = null

    fun stop() {
        workerThread?.let {
            it.interrupt()
            it.join(100)
        }
    }

    fun start() {
        logger.debug { "Starting block processor, chainId=$chainId" }

        workerThread = thread(start = true, name = "block-processor-$chainId", isDaemon = true) {
            while (true) {
                try {
                    val blocksToProcess = transaction {
                        (
                            getLastProcessedBlock()?.number?.let { it + BigInteger.ONE }
                                ?: DepositEntity.maxBlockNumber(chainId)?.let { it + BigInteger.ONE }
                                ?: blockchainClient.getBlockNumber()
                            ).rangeTo(blockchainClient.getBlockNumber())
                    }
                    for (blockNumber in blocksToProcess) {
                        transaction {
                            processBlock(blockNumber)
                        }
                    }
                    Thread.sleep(pollingIntervalInMs)
                } catch (ie: InterruptedException) {
                    logger.warn { "Exiting block processor thread, chainId=$chainId" }
                    return@thread
                } catch (e: Exception) {
                    logger.error(e) { "Unhandled exception in block processor, chainId=$chainId" }
                }
            }
        }
    }

    private val blocksNumberToCheckOnForkDetected =
        BigInteger.valueOf((System.getenv("BLOCKCHAIN_DEPOSIT_HANDLER_NUM_CONFIRMATIONS")?.toLongOrNull() ?: 1) + 1)

    private fun processBlock(blockNumber: BigInteger) {
        logger.info { "Processing block $blockNumber, chainId=$chainId" }

        val blockFromRpcNode = blockchainClient.getBlock(blockNumber, withFullTxObjects = false)
        val blockHash = BlockHash(blockFromRpcNode.hash)
        val blockParentHash = BlockHash(blockFromRpcNode.parentHash)
        val lastProcessedBlock = getLastProcessedBlock()

        if (lastProcessedBlock == null || blockParentHash == lastProcessedBlock.guid.value) {
            BlockEntity.create(blockHash, blockNumber, blockParentHash, chainId)

            val getLogsResult = blockchainClient.getExchangeContractLogs(blockNumber)

            if (getLogsResult.hasError()) {
                throw RuntimeException("Failed to get logs, block=$blockNumber, chainId=$chainId, error code: ${getLogsResult.error.code}, error message: ${getLogsResult.error.message}")
            }

            @Suppress("UNCHECKED_CAST")
            (getLogsResult.logs as List<EthLog.LogObject>).forEach { logResult ->
                processLog(logResult.get())
            }
        } else {
            logger.error { "It looks like we forked, looking for split point starting at $blockNumber, chainId=$chainId" }

            val blocksToRollback = BlockEntity
                .getRecentBlocksUpToNumber(
                    blockNumber - blocksNumberToCheckOnForkDetected,
                    chainId,
                )
                .filter { processedBlock ->
                    val blockHashFromRpcNode = BlockHash(blockchainClient.getBlock(blockFromRpcNode.number, withFullTxObjects = false).hash)
                    processedBlock.id.value != blockHashFromRpcNode
                }

            val blockNumbersToRollback = blocksToRollback.map { it.number }

            logger.info { "Rolling back blocks ${blockNumbersToRollback.joinToString(", ")}, chainId=$chainId" }

            if (DepositEntity.countConfirmedOrCompleted(blockNumbersToRollback, chainId) > 0) {
                logger.error { "Failed to rollback due to finalized deposits in blocks to rollback, chainId=$chainId" }
            } else {
                DepositEntity.markAsFailedByBlockNumbers(blockNumbersToRollback, chainId, error = "Fork rollback")
                blocksToRollback.forEach(BlockEntity::delete)
            }
        }
    }

    private fun processLog(log: Log) {
        if (Contract.staticExtractEventParameters(Exchange.DEPOSIT_EVENT, log) != null) {
            val depositEventResponse = Exchange.getDepositEventFromLog(log)
            logger.debug { "Received deposit event (from: ${depositEventResponse.from}, amount: ${depositEventResponse.amount}, token: ${depositEventResponse.token}, txHash: ${depositEventResponse.log.transactionHash}), chainId: $chainId" }

            val blockNumber = depositEventResponse.log.blockNumber
            val txHash = TxHash(depositEventResponse.log.transactionHash)

            val deposit = DepositEntity.findByTxHash(txHash)
            if (deposit == null) {
                DepositEntity.createOrUpdate(
                    wallet = WalletEntity.getOrCreate(Address(Keys.toChecksumAddress(depositEventResponse.from))),
                    symbol = SymbolEntity.forChainAndContractAddress(
                        chainId,
                        Address(Keys.toChecksumAddress(depositEventResponse.token)).takeIf { it != Address.zero },
                    ),
                    amount = depositEventResponse.amount,
                    blockNumber = blockNumber,
                    transactionHash = txHash,
                )
            } else {
                if (deposit.blockNumber != blockNumber) {
                    deposit.blockNumber = blockNumber
                    deposit.updatedAt = Clock.System.now()
                }
                logger.debug { "Skipping already recorded deposit (tx hash: $txHash)" }
            }
        }
    }

    private fun getLastProcessedBlock(): BlockEntity? =
        BlockTable
            .selectAll()
            .where { BlockTable.chainId.eq(chainId) }
            .orderBy(Pair(BlockTable.number, SortOrder.DESC))
            .limit(1)
            .map { BlockEntity.wrapRow(it) }
            .firstOrNull()
}
