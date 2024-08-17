package xyz.funkybit.apps.ring

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.transactions.transaction
import org.web3j.abi.DefaultFunctionEncoder
import xyz.funkybit.apps.api.model.websocket.MyTradesUpdated
import xyz.funkybit.core.blockchain.BlockchainClient
import xyz.funkybit.core.blockchain.DefaultBlockParam
import xyz.funkybit.core.evm.Adjustment
import xyz.funkybit.core.evm.BatchSettlement
import xyz.funkybit.core.evm.ECHelper.sha3
import xyz.funkybit.core.evm.TokenAdjustmentList
import xyz.funkybit.core.evm.WalletTradeList
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.db.BalanceChange
import xyz.funkybit.core.model.db.BalanceEntity
import xyz.funkybit.core.model.db.BalanceType
import xyz.funkybit.core.model.db.BlockchainTransactionData
import xyz.funkybit.core.model.db.BlockchainTransactionEntity
import xyz.funkybit.core.model.db.BroadcasterNotification
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.ChainSettlementBatchEntity
import xyz.funkybit.core.model.db.MarketEntity
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.OrderExecutionEntity
import xyz.funkybit.core.model.db.OrderSide
import xyz.funkybit.core.model.db.SettlementBatchEntity
import xyz.funkybit.core.model.db.SettlementBatchStatus
import xyz.funkybit.core.model.db.SettlementStatus
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.SymbolId
import xyz.funkybit.core.model.db.TradeEntity
import xyz.funkybit.core.model.db.WalletEntity
import xyz.funkybit.core.model.db.publishBroadcasterNotifications
import xyz.funkybit.core.sequencer.SequencerClient
import xyz.funkybit.core.sequencer.toSequencerId
import xyz.funkybit.core.utils.toHex
import xyz.funkybit.core.utils.toHexBytes
import xyz.funkybit.core.utils.tryAcquireAdvisoryLock
import xyz.funkybit.sequencer.core.sumBigIntegers
import xyz.funkybit.sequencer.proto.SequencerError
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds

class SettlementCoordinator(
    blockchainClients: List<BlockchainClient>,
    private val sequencerClient: SequencerClient,
    private val activePollingIntervalInMs: Long = System.getenv("SETTLEMENT_COORDINATOR_ACTIVE_POLLING_INTERVAL_MS")?.toLongOrNull() ?: 100L,
    private val inactivePollingIntervalInMs: Long = System.getenv("SETTLEMENT_COORDINATOR_INACTIVE_POLLING_INTERVAL_MS")?.toLongOrNull() ?: 500L,
    private val failurePollingIntervalMs: Long = System.getenv("SETTLEMENT_COORDINATOR_FAILURE_POLLING_INTERVAL_MS")?.toLongOrNull() ?: 2000L,
    private val batchMinTrades: Int = System.getenv("TRADE_SETTLEMENT_BATCH_MIN_TRADES")?.toIntOrNull() ?: 1,
    private val batchMaxIntervalMs: Long = System.getenv("TRADE_SETTLEMENT_BATCH_MAX_WAIT_TIME_MS")?.toLongOrNull() ?: 100L,
) {
    private val marketMap = mutableMapOf<MarketId, MarketEntity>()
    private val symbolMap = mutableMapOf<SymbolId, SymbolEntity>()
    private val chainIds = blockchainClients.map { it.chainId }
    private val blockchainClientsByChainId = blockchainClients.associateBy { it.chainId }
    private val advisoryLockKey = Long.MAX_VALUE

    private var workerThread: Thread? = null
    val logger = KotlinLogging.logger {}

    fun start() {
        logger.debug { "Starting settlement coordinator" }
        workerThread = thread(start = true, name = "batch-settlement-coordinator", isDaemon = true) {
            logger.debug { "Batch Settlement coordinator thread starting" }
            while (true) {
                try {
                    val batchInProgress = transaction {
                        if (tryAcquireAdvisoryLock(advisoryLockKey)) {
                            processSettlementBatch()
                        } else {
                            false
                        }
                    }

                    Thread.sleep(
                        if (batchInProgress) activePollingIntervalInMs else inactivePollingIntervalInMs,
                    )
                } catch (ie: InterruptedException) {
                    logger.warn { "Exiting settlement coordinator" }
                    return@thread
                } catch (e: Exception) {
                    logger.error(e) { "Unhandled exception in settlement coordinator thread" }
                    Thread.sleep(failurePollingIntervalMs)
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

    private fun processSettlementBatch(): Boolean {
        val batch = SettlementBatchEntity.findInProgressBatch()
            ?: createNextBatch()
            ?: return false

        return when (batch.status) {
            SettlementBatchStatus.Preparing -> {
                if (batch.allChainSettlementsPreparedOrCompleted()) {
                    val failedTrades = TradeEntity.findFailedSettling(batch)
                    if (failedTrades.isNotEmpty()) {
                        // if any trades failed settling, complete them
                        // they will be marked as failed and sequencer will be called to revert the settlements
                        completeSettlement(failedTrades, batch.preparationTxBlockNumbers())

                        // rollback the batches
                        updateBatchToRollingBack(batch)
                    } else {
                        // no failed trades, go to submitting
                        batch.markAsSubmitting()
                        updateBatchToSubmitting(batch)
                    }
                }
                true
            }

            SettlementBatchStatus.RollingBack -> {
                if (batch.allChainSettlementsRolledBackOrCompleted()) {
                    updateBatchToPreparingOrCompleted(batch)
                }
                true
            }
            SettlementBatchStatus.Submitting -> {
                if (batch.allChainSettlementsSubmittedOrCompleted()) {
                    batch.markAsSubmitted()
                }
                true
            }
            SettlementBatchStatus.Submitted -> {
                // while submitted wait for all the chains to go to complete.
                if (batch.allChainSettlementsCompleted()) {
                    updateBatchToCompleted(batch)
                    false
                } else {
                    true
                }
            }

            else -> false
        }
    }

    private fun updateBatchToCompleted(batch: SettlementBatchEntity) {
        val tradesSettling = TradeEntity.findSettling()

        batch.markAsCompleted()
        completeSettlement(tradesSettling, batch.submissionTxBlockNumbers())
    }

    private fun createNextBatch(): SettlementBatchEntity? {
        val tradesToPrepare = TradeEntity.findPendingForNewSettlementBatch()
        if (tradesToPrepare.isEmpty()) {
            return null
        }

        // in order to manually rollback a non netting trade, set its status to 'PendingRollback'
        // this will revert balance changes from the trade in the sequencer
        val pendingRollbacks = tradesToPrepare.filter { it.settlementStatus == SettlementStatus.PendingRollback }
        if (pendingRollbacks.isNotEmpty()) {
            TradeEntity.markAsFailedSettling(pendingRollbacks.map { it.tradeHash }.toSet(), "Manually Rolled Back")
            pendingRollbacks.forEach { it.refresh(true) }
            completeSettlement(pendingRollbacks, chainBlockNumbers = emptyMap())
            return null
        }

        val now = Clock.System.now()
        val earliestTradeTimestamp = tradesToPrepare.minBy { it.createdAt }.createdAt
        if (tradesToPrepare.size < batchMinTrades && earliestTradeTimestamp + batchMaxIntervalMs.milliseconds > now) {
            logger.debug { "Skipping create trade settlement batch. ${tradesToPrepare.size} pending trades, max age ${(now - earliestTradeTimestamp).inWholeMilliseconds}ms" }
            return null
        }

        return SettlementBatchEntity.create().also {
            TradeEntity.markAsSettling(tradesToPrepare.map { it.guid.value }, it)
            getBatchSettlements(tradesToPrepare).forEach { (chainId, batchSettlement) ->
                ChainSettlementBatchEntity.create(
                    chainId = chainId,
                    settlementBatch = it,
                    createBlockchainTransactionRecord(chainId, batchSettlement, calculateBatchHash(batchSettlement)),
                )
            }
        }
    }

    private fun updateBatchToPreparingOrCompleted(batch: SettlementBatchEntity) {
        val tradesSettling = TradeEntity.findSettling()
        if (tradesSettling.isEmpty()) {
            batch.markAsCompleted()
            return
        }

        if (batch.status != SettlementBatchStatus.Preparing) {
            batch.markAsPreparing()
        }
        val batchSettlementsByChainId = getBatchSettlements(tradesSettling)
        batch.chainBatches.forEach { chainSettlementBatch ->
            val chainId = chainSettlementBatch.chainId.value
            val batchSettlement = batchSettlementsByChainId[chainId]
            if (batchSettlement != null) {
                chainSettlementBatch.markAsPreparing(
                    createBlockchainTransactionRecord(chainId, batchSettlement, calculateBatchHash(batchSettlement)),
                )
            } else {
                // no remaining for this chain - mark as complete
                chainSettlementBatch.markAsCompleted()
            }
        }
    }

    private fun updateBatchToRollingBack(batch: SettlementBatchEntity) {
        batch.markAsRollingBack()
        batch.chainBatches.filter { it.status == SettlementBatchStatus.Prepared }.forEach {
            val chainId = it.chainId.value
            val blockchainClient = blockchainClientsByChainId.getValue(chainId)
            it.markAsRollingBack(
                BlockchainTransactionEntity.create(
                    chainId = chainId,
                    transactionData = BlockchainTransactionData(
                        blockchainClient.encodeRollbackBatchFunctionCall(),
                        blockchainClient.exchangeContractAddress,
                        BigInteger.ZERO,
                    ),
                    null,
                ),
            )
        }
    }

    private fun updateBatchToSubmitting(batch: SettlementBatchEntity) {
        logger.debug { "submitting for ${batch.guid}" }
        val tradesSettling = batch.settlingTrades()
        if (tradesSettling.isEmpty()) {
            // this should never happen , so log something
            logger.error { "Reached submit and no trades available" }
            batch.markAsCompleted()
            return
        }

        val batchSettlementsByChainId = getBatchSettlements(tradesSettling)
        batch.chainBatches.forEach { chainSettlementBatch ->
            val chainId = chainSettlementBatch.chainId.value
            val batchSettlement = batchSettlementsByChainId[chainId]
            if (batchSettlement != null) {
                chainSettlementBatch.markAsSubmitting(
                    createBlockchainTransactionRecord(chainId, batchSettlement, chainSettlementBatch.preparationTx.batchHash, isSubmit = true),
                )
            } else {
                // no remaining for this chain - mark as complete
                chainSettlementBatch.markAsCompleted()
            }
        }
    }

    private fun getBatchSettlements(trades: List<TradeEntity>): Map<ChainId, BatchSettlement> {
        val executions = OrderExecutionEntity.findForTrades(trades)
        return chainIds.mapNotNull { chainId ->
            val wallets = mutableSetOf<Address>()
            val tradeGuids = mutableMapOf<Address, MutableSet<ByteArray>>()
            val balanceAdjustments = mutableMapOf<SymbolId, MutableMap<Address, BigInteger>>()
            val feeAmounts = mutableMapOf<SymbolId, BigInteger>()
            val netAmounts = mutableMapOf<SymbolId, BigInteger>()
            executions.filter { isForChain(it.trade.marketGuid.value, chainId) }.forEach {
                val market = getMarket(it.trade.marketGuid.value)
                val walletAddress = it.order.wallet.address
                wallets.add(walletAddress)
                tradeGuids.getOrPut(walletAddress) { mutableSetOf() }.add(it.trade.tradeHash.toHexBytes())
                val baseSymbolEntity = symbolMap.getValue(market.baseSymbolGuid.value)
                val quoteSymbolEntity = symbolMap.getValue(market.quoteSymbolGuid.value)
                val baseSymbolId = baseSymbolEntity.guid.value
                val quoteSymbolId = quoteSymbolEntity.guid.value
                val baseSymbolChainId = baseSymbolEntity.chainId.value
                val quoteSymbolChainId = quoteSymbolEntity.chainId.value
                when (it.order.side) {
                    OrderSide.Buy -> {
                        if (baseSymbolChainId == chainId) {
                            balanceAdjustments.getOrPut(baseSymbolId) { mutableMapOf() }.merge(walletAddress, it.trade.amount, ::sumBigIntegers)
                            netAmounts.merge(baseSymbolId, it.trade.amount, ::sumBigIntegers)
                        }
                        if (quoteSymbolChainId == chainId) {
                            val notionalWithFee = notionalWithFee(
                                it.trade.amount,
                                it.trade.price,
                                baseSymbolEntity.decimals.toInt(),
                                quoteSymbolEntity.decimals.toInt(),
                                it.feeAmount,
                            )
                            balanceAdjustments.getOrPut(quoteSymbolId) { mutableMapOf() }.merge(walletAddress, notionalWithFee.negate(), ::sumBigIntegers)
                            netAmounts.merge(quoteSymbolId, notionalWithFee.negate(), ::sumBigIntegers)
                        }
                    }

                    OrderSide.Sell -> {
                        if (baseSymbolChainId == chainId) {
                            balanceAdjustments.getOrPut(baseSymbolId) { mutableMapOf() }.merge(walletAddress, it.trade.amount.negate(), ::sumBigIntegers)
                            netAmounts.merge(baseSymbolId, it.trade.amount.negate(), ::sumBigIntegers)
                        }
                        if (quoteSymbolChainId == chainId) {
                            val notionalWithFee = notionalWithFee(
                                it.trade.amount,
                                it.trade.price,
                                baseSymbolEntity.decimals.toInt(),
                                quoteSymbolEntity.decimals.toInt(),
                                it.feeAmount.negate(),
                            )
                            balanceAdjustments.getOrPut(quoteSymbolId) { mutableMapOf() }.merge(walletAddress, notionalWithFee, ::sumBigIntegers)
                            netAmounts.merge(quoteSymbolId, notionalWithFee, ::sumBigIntegers)
                        }
                    }
                }
                if (quoteSymbolChainId == chainId) {
                    feeAmounts.merge(quoteSymbolId, it.feeAmount, ::sumBigIntegers)
                    netAmounts.merge(quoteSymbolId, it.feeAmount, ::sumBigIntegers)
                }
            }
            if (netAmounts.any { it.value != BigInteger.ZERO }) {
                logger.error {
                    "some values are not netting to zero - " +
                        "net=<$netAmounts> " +
                        "trades=${trades.map { it.guid.value }}" +
                        "adj=<$balanceAdjustments> " +
                        "fees=<$feeAmounts>, "
                }
                if (trades.size > 1) {
                    trades.forEach {
                        try {
                            getBatchSettlements(listOf(it))
                        } catch (_: Exception) {}
                    }
                }
                throw Exception("trades did not net to 0")
            }
            if (wallets.isNotEmpty()) {
                val walletAddresses = wallets.toList()
                chainId to BatchSettlement(
                    walletAddresses.map { it.toString() },
                    walletAddresses.map { walletAddress ->
                        WalletTradeList(
                            tradeGuids.getValue(walletAddress).toList(),
                        )
                    },
                    (balanceAdjustments.keys).map { symbolId ->
                        TokenAdjustmentList(
                            token = (symbolMap.getValue(symbolId).contractAddress ?: EvmAddress.zero).toString(),
                            increments = balanceAdjustments.getValue(symbolId).filter { it.value > BigInteger.ZERO }
                                .map { Adjustment(walletAddresses.indexOf(it.key).toBigInteger(), it.value) },
                            decrements = balanceAdjustments.getValue(symbolId).filter { it.value < BigInteger.ZERO }
                                .map { Adjustment(walletAddresses.indexOf(it.key).toBigInteger(), it.value.abs()) },
                            feeAmount = feeAmounts.getOrDefault(symbolId, BigInteger.ZERO),
                        )
                    },
                )
            } else {
                null
            }
        }.toMap()
    }
    private fun createBlockchainTransactionRecord(chainId: ChainId, batchSettlement: BatchSettlement, batchHash: String?, isSubmit: Boolean = false): BlockchainTransactionEntity {
        val blockchainClient = blockchainClientsByChainId.getValue(chainId)
        val transactionData = BlockchainTransactionData(
            data = if (isSubmit) {
                blockchainClient.encodeSubmitSettlementBatchFunctionCall(batchSettlement)
            } else {
                blockchainClient.encodePrepareSettlementBatchFunctionCall(batchSettlement)
            },
            to = blockchainClient.exchangeContractAddress,
            value = BigInteger.ZERO,
        )
        return BlockchainTransactionEntity.create(
            chainId = chainId,
            transactionData = transactionData,
            batchHash = batchHash,
        )
    }

    private fun calculateBatchHash(batchSettlement: BatchSettlement): String {
        return sha3(DefaultFunctionEncoder().encodeParameters(listOf(batchSettlement)).toHexBytes()).toHex(false)
    }

    private fun notionalWithFee(amount: BigInteger, price: BigDecimal, baseDecimals: Int, quoteDecimals: Int, feeAmount: BigInteger): BigInteger =
        (amount.toBigDecimal() * price).movePointRight(quoteDecimals - baseDecimals).toBigInteger() + feeAmount

    private fun getMarket(marketId: MarketId): MarketEntity {
        return marketMap.getOrPut(marketId) {
            MarketEntity[marketId]
        }.also {
            symbolMap[it.baseSymbolGuid.value] = it.baseSymbol
            symbolMap[it.quoteSymbolGuid.value] = it.quoteSymbol
        }
    }

    private fun isForChain(marketId: MarketId, chainId: ChainId) = getMarket(marketId).let {
        symbolMap.getValue(it.baseSymbolGuid.value).chainId.value == chainId ||
            symbolMap.getValue(it.quoteSymbolGuid.value).chainId.value == chainId
    }

    private fun completeSettlement(trades: List<TradeEntity>, chainBlockNumbers: Map<ChainId, BigInteger?>) {
        val broadcasterNotifications = mutableListOf<BroadcasterNotification>()

        trades.forEach { tradeEntity ->
            val executions = tradeEntity.executions

            if (tradeEntity.settlementStatus == SettlementStatus.FailedSettling) {
                logger.error { "settlement failed for ${tradeEntity.guid.value} - error is <${tradeEntity.error}>" }

                tradeEntity.fail()

                val buyExecution = executions.first { it.order.side == OrderSide.Buy }
                val buyOrder = buyExecution.order

                val sellExecution = executions.first { it.order.side == OrderSide.Sell }
                val sellOrder = sellExecution.order

                val market = tradeEntity.market

                val sequencerResponse = runBlocking {
                    sequencerClient.failSettlement(
                        buyWallet = buyOrder.wallet.address.toSequencerId().value,
                        sellWallet = sellOrder.wallet.address.toSequencerId().value,
                        marketId = tradeEntity.marketGuid.value,
                        buyOrderId = buyOrder.guid.value,
                        sellOrderId = sellOrder.guid.value,
                        amount = tradeEntity.amount,
                        levelIx = tradeEntity.price.divideToIntegralValue(market.tickSize).toInt(),
                        buyerFee = buyExecution.feeAmount,
                        sellerFee = sellExecution.feeAmount,
                    )
                }
                if (sequencerResponse.error == SequencerError.None) {
                    logger.debug { "Successfully notified sequencer" }
                } else {
                    logger.error { "Sequencer failed with error ${sequencerResponse.error} - failed settlements" }
                }
            } else {
                logger.debug { "settlement completed for ${tradeEntity.guid}" }
                tradeEntity.settle()

                // update the onchain balances
                val wallets = executions.map { it.order.wallet }
                val market = getMarket(tradeEntity.marketGuid.value)
                val symbols = listOf(market.baseSymbolGuid.value, market.quoteSymbolGuid.value)
                val baseSymbolChainId = symbolMap.getValue(market.baseSymbolGuid.value).chainId.value
                val quoteSymbolChainId = symbolMap.getValue(market.quoteSymbolGuid.value).chainId.value
                if (baseSymbolChainId == quoteSymbolChainId) {
                    updateBalances(wallets, symbols, baseSymbolChainId, chainBlockNumbers[baseSymbolChainId])
                } else {
                    updateBalances(wallets, listOf(market.baseSymbolGuid.value), baseSymbolChainId, chainBlockNumbers[baseSymbolChainId])
                    updateBalances(wallets, listOf(market.quoteSymbolGuid.value), quoteSymbolChainId, chainBlockNumbers[quoteSymbolChainId])
                }

                wallets.forEach { wallet ->
                    broadcasterNotifications.add(BroadcasterNotification.walletBalances(wallet))
                }
            }
        }

        trades
            .flatMap { it.executions }
            .groupBy { it.order.wallet.address }
            .forEach { (walletAddress, executions) ->
                broadcasterNotifications.add(
                    BroadcasterNotification(
                        MyTradesUpdated(executions.map { it.toTradeResponse() }),
                        recipient = walletAddress,
                    ),
                )
            }

        publishBroadcasterNotifications(broadcasterNotifications)
    }

    private fun updateBalances(wallets: List<WalletEntity>, symbolIds: List<SymbolId>, chainId: ChainId, blockNumber: BigInteger?) {
        val finalExchangeBalances = runBlocking {
            blockchainClientsByChainId.getValue(chainId).getExchangeBalances(
                wallets.map { it.address },
                symbolIds.map { symbolMap.getValue(it).contractAddress ?: EvmAddress.zero },
                blockNumber?.let { DefaultBlockParam.BlockNumber(it) } ?: DefaultBlockParam.Latest,
            )
        }
        logger.debug { "finalExchangeBalances = $finalExchangeBalances" }
        BalanceEntity.updateBalances(
            wallets.map { wallet ->
                symbolIds.map { symbolId ->
                    val symbolInfo = symbolMap.getValue(symbolId)
                    BalanceChange.Replace(
                        walletId = wallet.guid.value,
                        symbolId = symbolId,
                        amount = finalExchangeBalances.getValue(wallet.address).getValue(
                            symbolInfo.contractAddress ?: EvmAddress.zero,
                        ),
                    )
                }
            }.flatten(),
            BalanceType.Exchange,
        )
    }
}
