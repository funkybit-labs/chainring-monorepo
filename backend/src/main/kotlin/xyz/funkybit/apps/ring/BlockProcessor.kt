package xyz.funkybit.apps.ring

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.web3j.crypto.Keys
import org.web3j.protocol.core.methods.response.EthBlock
import org.web3j.protocol.core.methods.response.EthLog
import org.web3j.protocol.core.methods.response.Log
import org.web3j.tx.Contract
import xyz.funkybit.apps.api.model.Withdrawal
import xyz.funkybit.contracts.generated.Exchange
import xyz.funkybit.core.blockchain.BlockchainClient
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.EvmSignature
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.db.BlockEntity
import xyz.funkybit.core.model.db.BlockTable
import xyz.funkybit.core.model.db.ChainEntity
import xyz.funkybit.core.model.db.DepositEntity
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.WalletEntity
import xyz.funkybit.core.model.db.WithdrawalEntity
import xyz.funkybit.core.sequencer.SequencerClient
import xyz.funkybit.core.sequencer.toSequencerId
import xyz.funkybit.core.services.LinkedSignerService
import xyz.funkybit.core.utils.rangeTo
import xyz.funkybit.sequencer.core.Asset
import java.math.BigInteger
import kotlin.concurrent.thread

class BlockProcessor(
    private val blockchainClient: BlockchainClient,
    private val sequencerClient: SequencerClient,
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
                        if (ChainEntity.findById(blockchainClient.chainId) != null) {
                            (
                                getLastProcessedBlock()?.number?.let { it + BigInteger.ONE }
                                    ?: DepositEntity.maxBlockNumber(chainId)?.let { it + BigInteger.ONE }
                                    ?: blockchainClient.getBlockNumber()
                                ).rangeTo(blockchainClient.getBlockNumber())
                        } else {
                            listOf()
                        }
                    }

                    for (blockNumber in blocksToProcess) {
                        logger.info { "Processing block $blockNumber, chainId=$chainId" }

                        val blockFromRpcNode = blockchainClient.getBlock(blockNumber, withFullTxObjects = false)
                        val lastProcessedBlock = transaction { getLastProcessedBlock() }
                        if (lastProcessedBlock == null || blockFromRpcNode.parentHash == lastProcessedBlock.hash) {
                            transaction {
                                processBlock(blockFromRpcNode)
                            }
                        } else {
                            transaction {
                                handleFork(blockFromRpcNode, lastProcessedBlock)
                            }
                            break
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

    private fun processBlock(blockFromRpcNode: EthBlock.Block) {
        logger.info { "Storing block [number=${blockFromRpcNode.number},hash=${blockFromRpcNode.hash},parentHash=${blockFromRpcNode.parentHash}], chainId=$chainId" }
        BlockEntity.create(blockFromRpcNode, chainId)

        val getLogsResult = blockchainClient.getExchangeContractLogs(blockFromRpcNode.number)

        if (getLogsResult.hasError()) {
            throw RuntimeException("Failed to get logs, block=${blockFromRpcNode.number}, chainId=$chainId, error code: ${getLogsResult.error.code}, error message: ${getLogsResult.error.message}")
        }

        @Suppress("UNCHECKED_CAST")
        (getLogsResult.logs as List<EthLog.LogObject>).forEach { logResult ->
            processLog(logResult.get())
        }
    }

    private val blocksNumberToCheckOnForkDetected =
        BigInteger.valueOf((System.getenv("BLOCKCHAIN_DEPOSIT_HANDLER_NUM_CONFIRMATIONS")?.toIntOrNull() ?: BlockchainDepositHandler.DEFAULT_NUM_CONFIRMATIONS) + 1L)

    private fun handleFork(blockFromRpcNode: EthBlock.Block, lastProcessedBlock: BlockEntity) {
        logger.info { "Fork detected: for block [height=${blockFromRpcNode.number},hash=${blockFromRpcNode.hash},parentHash=${blockFromRpcNode.parentHash}] latest block found in db is [height=${lastProcessedBlock.number},hash=${lastProcessedBlock.guid.value},parentHash=${lastProcessedBlock.parentGuid.value}]. Looking for split point starting at ${blockFromRpcNode.number}, chainId=$chainId" }

        val blocksToRollback = BlockEntity
            .getRecentBlocksUpToNumber(
                blockFromRpcNode.number - blocksNumberToCheckOnForkDetected,
                chainId,
            )
            .filter { processedBlock ->
                processedBlock.hash != blockchainClient.getBlock(processedBlock.number, withFullTxObjects = false).hash
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

    private fun processLog(log: Log) {
        if (Contract.staticExtractEventParameters(Exchange.DEPOSIT_EVENT, log) != null) {
            val depositEventResponse = Exchange.getDepositEventFromLog(log)
            logger.debug { "Received deposit event (from: ${depositEventResponse.from}, amount: ${depositEventResponse.amount}, token: ${depositEventResponse.token}, txHash: ${depositEventResponse.log.transactionHash}), chainId: $chainId" }

            val blockNumber = depositEventResponse.log.blockNumber
            val txHash = TxHash(depositEventResponse.log.transactionHash)

            val deposit = DepositEntity.findByTxHash(txHash)
            if (deposit == null) {
                DepositEntity.createOrUpdate(
                    wallet = WalletEntity.getOrCreateWithUser(EvmAddress(Keys.toChecksumAddress(depositEventResponse.from))),
                    symbol = SymbolEntity.forChainAndContractAddress(
                        chainId,
                        EvmAddress(Keys.toChecksumAddress(depositEventResponse.token)).takeIf { it != EvmAddress.zero },
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

        if (Contract.staticExtractEventParameters(Exchange.WITHDRAWALREQUESTED_EVENT, log) != null) {
            val withdrawalEventResponse = Exchange.getWithdrawalRequestedEventFromLog(log)
            logger.debug { "Received sovereign withdrawal request event (from: ${withdrawalEventResponse.from}, amount: ${withdrawalEventResponse.amount}, token: ${withdrawalEventResponse.token}, txHash: ${withdrawalEventResponse.log.transactionHash}), chainId: $chainId" }

            val fromAddress = EvmAddress(Keys.toChecksumAddress(withdrawalEventResponse.from))
            val symbol = SymbolEntity.forChainAndContractAddress(
                chainId,
                EvmAddress(Keys.toChecksumAddress(withdrawalEventResponse.token)).takeIf { it != EvmAddress.zero },
            )
            val nonce = 0L
            val evmSignature = EvmSignature.emptySignature()

            val wallet = WalletEntity.getByAddress(fromAddress)
            val withdrawal = WithdrawalEntity.createPending(
                wallet = wallet,
                symbol = symbol,
                amount = withdrawalEventResponse.amount,
                nonce = nonce,
                signature = evmSignature,
            ).let {
                it.refresh(flush = true)
                Withdrawal.fromEntity(it)
            }

            runBlocking {
                sequencerClient.withdraw(
                    wallet.userGuid.value.toSequencerId(),
                    Asset(symbol.name),
                    withdrawalEventResponse.amount,
                    nonce = nonce.toBigInteger(),
                    evmSignature = evmSignature,
                    withdrawal.id,
                )
            }
        }

        if (Contract.staticExtractEventParameters(Exchange.LINKEDSIGNER_EVENT, log) != null) {
            val linkedSignerEventResponse = Exchange.getLinkedSignerEventFromLog(log)
            logger.debug { "Received linked signer (wallet: ${linkedSignerEventResponse.sender}, signer: ${linkedSignerEventResponse.linkedSigner}, txHash: ${linkedSignerEventResponse.log.transactionHash}), chainId: $chainId" }

            val linkedSignerAddress = EvmAddress(Keys.toChecksumAddress(linkedSignerEventResponse.linkedSigner))
            val walletAddress = EvmAddress(Keys.toChecksumAddress(linkedSignerEventResponse.sender))
            LinkedSignerService.createOrUpdateWalletLinkedSigner(walletAddress, chainId, linkedSignerAddress)
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
