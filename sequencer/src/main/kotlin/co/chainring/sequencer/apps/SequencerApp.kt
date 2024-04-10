package co.chainring.sequencer.apps

import co.chainring.sequencer.core.Asset
import co.chainring.sequencer.core.Market
import co.chainring.sequencer.core.MarketId
import co.chainring.sequencer.core.SequencerState
import co.chainring.sequencer.core.WalletAddress
import co.chainring.sequencer.core.notional
import co.chainring.sequencer.core.queueHome
import co.chainring.sequencer.core.sumBigIntegers
import co.chainring.sequencer.core.toAsset
import co.chainring.sequencer.core.toBigDecimal
import co.chainring.sequencer.core.toBigInteger
import co.chainring.sequencer.core.toIntegerValue
import co.chainring.sequencer.core.toOrderGuid
import co.chainring.sequencer.core.toWalletAddress
import co.chainring.sequencer.proto.BalanceChange
import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.OrderBatch
import co.chainring.sequencer.proto.OrderChanged
import co.chainring.sequencer.proto.SequencerError
import co.chainring.sequencer.proto.SequencerRequest
import co.chainring.sequencer.proto.SequencerResponse
import co.chainring.sequencer.proto.TradeCreated
import co.chainring.sequencer.proto.balanceChange
import co.chainring.sequencer.proto.marketCreated
import co.chainring.sequencer.proto.sequencerResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import net.openhft.chronicle.queue.TailerDirection
import net.openhft.chronicle.queue.TailerState
import net.openhft.chronicle.queue.impl.RollingChronicleQueue
import java.math.BigInteger
import java.nio.file.Path
import kotlin.concurrent.thread
import co.chainring.sequencer.core.inputQueue as defaultInputQueue
import co.chainring.sequencer.core.outputQueue as defaultOutputQueue

class SequencerApp(
    val inputQueue: RollingChronicleQueue = defaultInputQueue,
    val outputQueue: RollingChronicleQueue = defaultOutputQueue,
    val checkpointsPath: Path = Path.of(queueHome, "checkpoints"),
) : BaseApp() {
    override val logger = KotlinLogging.logger {}
    private var stop = false
    private lateinit var sequencerThread: Thread
    private var state = SequencerState()

    fun processRequest(request: SequencerRequest, sequence: Long = 0L, startTime: Long = 0L): SequencerResponse {
        return when (request.type) {
            SequencerRequest.Type.AddMarket -> {
                val market = request.addMarket!!
                val marketId = MarketId(market.marketId)
                var error: SequencerError? = null
                if (state.markets.containsKey(marketId)) {
                    error = SequencerError.MarketExists
                } else {
                    state.markets[marketId] = Market(
                        marketId,
                        market.tickSize.toBigDecimal(),
                        market.marketPrice.toBigDecimal(),
                        market.maxLevels,
                        market.maxOrdersPerLevel,
                        market.baseDecimals,
                        market.quoteDecimals,
                    )
                }
                sequencerResponse {
                    this.guid = market.guid
                    this.sequence = sequence
                    this.processingTime = System.nanoTime() - startTime
                    error?.let { this.error = it } ?: run {
                        this.marketsCreated.add(
                            marketCreated {
                                this.marketId = marketId.value
                                this.tickSize = market.tickSize
                            },
                        )
                    }
                }
            }
            SequencerRequest.Type.ApplyOrderBatch -> {
                var ordersChanged: List<OrderChanged> = emptyList()
                var trades: List<TradeCreated> = emptyList()
                var balanceChanges: List<BalanceChange> = emptyList()
                val orderBatch = request.orderBatch!!
                var error: SequencerError? = null
                val marketId = MarketId(orderBatch.marketId)
                val market = state.markets[marketId]
                if (market == null) {
                    error = SequencerError.UnknownMarket
                } else {
                    error = checkLimits(market, orderBatch)
                    if (error == null) {
                        val result = market.applyOrderBatch(orderBatch)
                        ordersChanged = result.ordersChanged
                        trades = result.createdTrades
                        balanceChanges = result.balanceChanges
                        // apply balance changes
                        balanceChanges.forEach {
                            state.balances.getOrPut(it.wallet.toWalletAddress()) {
                                mutableMapOf()
                            }.merge(
                                it.asset.toAsset(),
                                it.delta.toBigInteger(),
                            ) { a, b -> BigInteger.ZERO.max(a + b) }
                        }
                    }
                }
                sequencerResponse {
                    this.sequence = sequence
                    this.processingTime = System.nanoTime() - startTime
                    this.guid = orderBatch.guid
                    this.ordersChanged.addAll(ordersChanged)
                    this.tradesCreated.addAll(trades)
                    this.balancesChanged.addAll(balanceChanges)
                    error?.let {
                        this.error = it
                    }
                }
            }

            SequencerRequest.Type.ApplyBalanceBatch -> {
                val balanceBatch = request.balanceBatch!!
                val balancesChanged = mutableMapOf<Pair<WalletAddress, Asset>, BigInteger>()
                balanceBatch.depositsList.forEach { deposit ->
                    val wallet = deposit.wallet.toWalletAddress()
                    val asset = deposit.asset.toAsset()
                    val amount = deposit.amount.toBigInteger()
                    state.balances.getOrPut(wallet) { mutableMapOf() }.merge(asset, amount, ::sumBigIntegers)
                    balancesChanged.merge(Pair(wallet, asset), amount, ::sumBigIntegers)
                }

                balanceBatch.withdrawalsList.forEach { withdrawal ->
                    state.balances[withdrawal.wallet.toWalletAddress()]?.let { balanceByAsset ->
                        val asset = withdrawal.asset.toAsset()
                        val withdrawalAmount = withdrawal.amount.toBigInteger().min(
                            balanceByAsset[withdrawal.asset.toAsset()] ?: BigInteger.ZERO,
                        )
                        if (withdrawalAmount > BigInteger.ZERO) {
                            val wallet = withdrawal.wallet.toWalletAddress()
                            balanceByAsset.merge(asset, -withdrawalAmount, ::sumBigIntegers)
                            balancesChanged.merge(Pair(wallet, asset), -withdrawalAmount, ::sumBigIntegers)
                        }
                    }
                }

                sequencerResponse {
                    this.guid = balanceBatch.guid
                    this.sequence = sequence
                    this.processingTime = System.nanoTime() - startTime
                    this.balancesChanged.addAll(
                        balancesChanged.mapNotNull { (k, delta) ->
                            val (wallet, asset) = k
                            if (delta != BigInteger.ZERO) {
                                balanceChange {
                                    this.wallet = wallet.value
                                    this.asset = asset.value
                                    this.delta = delta.toIntegerValue()
                                }
                            } else {
                                null
                            }
                        },
                    )
                }
            }

            null, SequencerRequest.Type.UNRECOGNIZED -> {
                sequencerResponse {
                    this.sequence = sequence
                    this.processingTime = System.nanoTime() - startTime
                    this.guid = request.guid
                    this.error = SequencerError.UnknownRequest
                }
            }
        }
    }

    private fun checkLimits(market: Market, orderBatch: OrderBatch): SequencerError? {
        // compute cumulative assets required change from applying all orders in order batch
        val baseAssetsRequired = mutableMapOf<WalletAddress, BigInteger>()
        val quoteAssetsRequired = mutableMapOf<WalletAddress, BigInteger>()
        orderBatch.ordersToAddList.forEach { order ->
            when (order.type) {
                Order.Type.LimitSell, Order.Type.MarketSell -> {
                    baseAssetsRequired.merge(order.wallet.toWalletAddress(), order.amount.toBigInteger(), ::sumBigIntegers)
                }
                Order.Type.LimitBuy -> {
                    quoteAssetsRequired.merge(order.wallet.toWalletAddress(), notional(order.amount, order.price, market.baseDecimals, market.quoteDecimals), ::sumBigIntegers)
                }
                Order.Type.MarketBuy -> {
                    // the quote assets required for a market buy depends on what the clearing price would be
                    val(clearingPrice, availableQuantity) = market.orderBook.clearingPriceAndQuantityForMarketBuy(order.amount.toBigInteger())
                    quoteAssetsRequired.merge(order.wallet.toWalletAddress(), notional(availableQuantity, clearingPrice, market.baseDecimals, market.quoteDecimals), ::sumBigIntegers)
                }
                else -> {}
            }
        }
        orderBatch.ordersToChangeList.forEach { orderChange ->
            market.orderBook.ordersByGuid[orderChange.guid.toOrderGuid()]?.let { order ->
                val (oldBaseAssets, oldQuoteAssets) = market.orderBook.assetsReservedForOrder(order)
                if (oldBaseAssets > BigInteger.ZERO) { // LimitSell
                    if (orderChange.price.toBigDecimal() > market.orderBook.marketPrice) {
                        baseAssetsRequired.merge(
                            order.wallet,
                            orderChange.amount.toBigInteger() - order.quantity,
                            ::sumBigIntegers,
                        )
                    } else {
                        return SequencerError.ChangeCrossesMarket
                    }
                }
                if (oldQuoteAssets > BigInteger.ZERO) { // LimitBuy
                    if (orderChange.price.toBigDecimal() < market.orderBook.marketPrice) {
                        quoteAssetsRequired.merge(
                            order.wallet,
                            (
                                notional(
                                    orderChange.amount,
                                    orderChange.price,
                                    market.baseDecimals,
                                    market.quoteDecimals,
                                ) -
                                    notional(
                                        order.quantity,
                                        market.orderBook.levels[order.levelIx].price,
                                        market.baseDecimals,
                                        market.quoteDecimals,
                                    )
                                ),
                            ::sumBigIntegers,
                        )
                    } else {
                        return SequencerError.ChangeCrossesMarket
                    }
                }
            }
        }
        orderBatch.ordersToCancelList.forEach { guid ->
            market.orderBook.ordersByGuid[guid.toOrderGuid()]?.let { order ->
                val (baseAssets, quoteAssets) = market.orderBook.assetsReservedForOrder(order)
                if (baseAssets > BigInteger.ZERO) {
                    baseAssetsRequired.merge(order.wallet, -baseAssets, ::sumBigIntegers)
                }
                if (quoteAssets > BigInteger.ZERO) {
                    quoteAssetsRequired.merge(order.wallet, -quoteAssets, ::sumBigIntegers)
                }
            }
        }

        baseAssetsRequired.forEach { (wallet, required) ->
            if (required + market.orderBook.baseAssetsRequired(wallet) > (
                    state.balances[wallet]?.get(market.id.baseAsset())
                        ?: BigInteger.ZERO
                    )
            ) {
                return SequencerError.ExceedsLimit
            }
        }

        quoteAssetsRequired.forEach { (wallet, required) ->
            if (required + market.orderBook.quoteAssetsRequired(wallet) > (
                    state.balances[wallet]?.get(market.id.quoteAsset())
                        ?: BigInteger.ZERO
                    )
            ) {
                return SequencerError.ExceedsLimit
            }
        }

        return null
    }

    override fun start() {
        logger.info { "Starting Sequencer App" }

        stop = false
        sequencerThread = thread(start = true, name = "sequencer", isDaemon = false) {
            val inputTailer = inputQueue.createTailer("sequencer")
            val initialCycle = inputTailer.cycle()

            // moveToCycle moves tailer to the start of the cycle
            if (inputTailer.moveToCycle(initialCycle)) {
                // Restore from previous cycle's checkpoint unless we are in the first cycle
                if (initialCycle != inputQueue.firstCycle()) {
                    val prevCycle = inputQueue.nextCycle(initialCycle, TailerDirection.BACKWARD)
                    restoreFromCheckpoint(prevCycle)
                }
            }

            val lastSequenceNumberProcessedBeforeRestart = getLastSequenceNumberInOutputQueue()

            val outputAppender = outputQueue.acquireAppender()
            var tailerPrevState = inputTailer.state()
            while (!stop) {
                val tailerState = inputTailer.state()
                if (tailerState == TailerState.END_OF_CYCLE && tailerState != tailerPrevState) {
                    saveCheckpoint(inputTailer.cycle())
                }

                inputTailer.readingDocument().use { dc ->
                    if (dc.isPresent) {
                        val startTime = System.nanoTime()
                        dc.wire()?.read()?.bytes { bytes ->
                            val request = SequencerRequest.parseFrom(bytes.toByteArray())
                            val response = processRequest(request, dc.index(), startTime)
                            if (response.sequence > lastSequenceNumberProcessedBeforeRestart) {
                                outputAppender.writingDocument().use {
                                    it.wire()?.write()?.bytes(response.toByteArray())
                                }
                            }
                        }
                    }
                }

                tailerPrevState = tailerState
            }
        }

        logger.info { "Sequencer App started" }
    }

    private fun getLastSequenceNumberInOutputQueue(): Long =
        outputQueue.createTailer().let { outputTailer ->
            var result = -1L
            outputTailer.moveToIndex(outputQueue.lastIndex())
            outputTailer.readingDocument().use {
                if (it.isPresent) {
                    it.wire()?.read()?.bytes { bytes ->
                        result = SequencerResponse.parseFrom(bytes.toByteArray()).sequence
                    }
                }
            }
            result
        }

    private fun saveCheckpoint(currentCycle: Int) {
        val checkpointPath = Path.of(checkpointsPath.toString(), "$currentCycle.ckpt")
        logger.debug { "Saving checkpoint to $checkpointPath" }
        state.persist(checkpointPath)
        logger.debug { "Saved checkpoint" }
    }

    private fun restoreFromCheckpoint(atCycle: Int) {
        val checkpointPath = Path.of(checkpointsPath.toString(), "$atCycle.ckpt")
        logger.debug { "Restoring from checkpoint $checkpointPath" }
        state = SequencerState.load(checkpointPath)
        logger.debug { "Restored from checkpoint" }
    }

    override fun stop() {
        logger.info { "Stopping Sequencer App" }
        stop = true
        sequencerThread.join(100)
        sequencerThread.stop()
        logger.info { "Sequencer App stopped" }
    }
}
