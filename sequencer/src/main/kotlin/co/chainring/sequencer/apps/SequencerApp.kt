package co.chainring.sequencer.apps

import co.chainring.sequencer.core.Asset
import co.chainring.sequencer.core.Market
import co.chainring.sequencer.core.MarketId
import co.chainring.sequencer.core.SequencerState
import co.chainring.sequencer.core.WalletAddress
import co.chainring.sequencer.core.notional
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
import net.openhft.chronicle.queue.ExcerptTailer
import net.openhft.chronicle.queue.TailerDirection
import net.openhft.chronicle.queue.TailerState
import net.openhft.chronicle.queue.impl.RollingChronicleQueue
import java.lang.Thread.UncaughtExceptionHandler
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Path
import kotlin.concurrent.thread
import co.chainring.sequencer.core.inputQueue as defaultInputQueue
import co.chainring.sequencer.core.outputQueue as defaultOutputQueue

class SequencerApp(
    val inputQueue: RollingChronicleQueue = defaultInputQueue,
    val outputQueue: RollingChronicleQueue = defaultOutputQueue,
    val checkpointsPath: Path?,
    val inSandboxMode: Boolean = System.getenv("SANDBOX_MODE").toBoolean(),
) : BaseApp() {
    override val logger = KotlinLogging.logger {}
    private var stop = false
    private lateinit var sequencerThread: Thread
    private val state = SequencerState()

    fun processRequest(request: SequencerRequest, sequence: Long = 0L, startTime: Long = 0L): SequencerResponse {
        return when (request.type) {
            SequencerRequest.Type.AddMarket -> {
                val market = request.addMarket!!
                val marketId = MarketId(market.marketId)
                var error: SequencerError? = null
                if (state.markets.containsKey(marketId)) {
                    error = SequencerError.MarketExists
                } else {
                    val tickSize = market.tickSize.toBigDecimal()
                    val halfTick = tickSize.setScale(tickSize.scale() + 1).divide(BigDecimal.valueOf(2))
                    val marketPrice = market.marketPrice.toBigDecimal()
                    state.markets[marketId] = Market(
                        marketId,
                        tickSize,
                        marketPrice,
                        marketPrice - halfTick,
                        marketPrice + halfTick,
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
                val walletsAndAssetsWithBalanceChanges: MutableSet<Pair<WalletAddress, Asset>> = mutableSetOf()
                val orderBatch = request.orderBatch!!
                val error: SequencerError?
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
                            walletsAndAssetsWithBalanceChanges.add(Pair(it.wallet.toWalletAddress(), it.asset.toAsset()))
                        }
                        // apply consumption changes
                        result.consumptionChanges.forEach {
                            if (it.delta != BigInteger.ZERO) {
                                state.consumed.getOrPut(it.walletAddress) {
                                    mutableMapOf()
                                }.getOrPut(it.asset) {
                                    mutableMapOf()
                                }.merge(marketId, it.delta, ::sumBigIntegers)
                            }
                        }
                    }
                }
                sequencerResponse {
                    this.sequence = sequence
                    this.processingTime = System.nanoTime() - startTime
                    this.guid = orderBatch.guid
                    this.ordersChanged.addAll(ordersChanged)
                    this.ordersChanged.addAll(autoReduce(walletsAndAssetsWithBalanceChanges))
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
                    this.ordersChanged.addAll(autoReduce(balancesChanged.keys))
                }
            }
            SequencerRequest.Type.Reset -> {
                if (inSandboxMode) {
                    state.clear()
                    sequencerResponse {
                        this.sequence = sequence
                        this.processingTime = System.nanoTime() - startTime
                        this.guid = request.guid
                    }
                } else {
                    sequencerResponse {
                        this.sequence = sequence
                        this.processingTime = System.nanoTime() - startTime
                        this.guid = request.guid
                        this.error = SequencerError.UnknownRequest
                    }
                }
            }
            SequencerRequest.Type.GetState -> {
                if (inSandboxMode) {
                    sequencerResponse {
                        this.sequence = sequence
                        this.processingTime = System.nanoTime() - startTime
                        this.guid = request.guid
                        this.stateDump = state.getDump()
                    }
                } else {
                    sequencerResponse {
                        this.sequence = sequence
                        this.processingTime = System.nanoTime() - startTime
                        this.guid = request.guid
                        this.error = SequencerError.UnknownRequest
                    }
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

    private fun autoReduce(walletsAndAssets: Collection<Pair<WalletAddress, Asset>>): List<OrderChanged> {
        return walletsAndAssets.flatMap { (walletAddress, asset) ->
            state.consumed[walletAddress]?.get(asset)?.flatMap { (marketId, amount) ->
                val balance = state.balances[walletAddress]?.get(asset) ?: BigInteger.ZERO
                if (amount > balance) {
                    state.markets[marketId]?.autoReduce(walletAddress, asset, balance) ?: emptyList()
                } else {
                    emptyList()
                }
            } ?: emptyList()
        }
    }

    private fun checkLimits(market: Market, orderBatch: OrderBatch): SequencerError? {
        // compute cumulative assets required change from applying all orders in order batch
        val baseAssetsRequired = mutableMapOf<WalletAddress, BigInteger>()
        val quoteAssetsRequired = mutableMapOf<WalletAddress, BigInteger>()
        orderBatch.ordersToAddList.forEach { order ->
            when (order.type) {
                Order.Type.LimitSell, Order.Type.MarketSell -> {
                    baseAssetsRequired.merge(orderBatch.wallet.toWalletAddress(), order.amount.toBigInteger(), ::sumBigIntegers)
                }
                Order.Type.LimitBuy -> {
                    quoteAssetsRequired.merge(orderBatch.wallet.toWalletAddress(), notional(order.amount, order.price, market.baseDecimals, market.quoteDecimals), ::sumBigIntegers)
                }
                Order.Type.MarketBuy -> {
                    // the quote assets required for a market buy depends on what the clearing price would be
                    val(clearingPrice, availableQuantity) = market.clearingPriceAndQuantityForMarketBuy(order.amount.toBigInteger())
                    quoteAssetsRequired.merge(orderBatch.wallet.toWalletAddress(), notional(availableQuantity, clearingPrice, market.baseDecimals, market.quoteDecimals), ::sumBigIntegers)
                }
                else -> {}
            }
        }
        orderBatch.ordersToChangeList.forEach { orderChange ->
            market.ordersByGuid[orderChange.guid.toOrderGuid()]?.let { order ->
                val (oldBaseAssets, oldQuoteAssets) = market.assetsReservedForOrder(order)
                if (oldBaseAssets > BigInteger.ZERO) { // LimitSell
                    if (orderChange.price.toBigDecimal() > market.bestBid) {
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
                    if (orderChange.price.toBigDecimal() < market.bestOffer) {
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
                                        market.levels[order.levelIx].price,
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
        orderBatch.ordersToCancelList.forEach { cancelOrder ->
            market.ordersByGuid[cancelOrder.guid.toOrderGuid()]?.let { order ->
                val (baseAssets, quoteAssets) = market.assetsReservedForOrder(order)
                if (baseAssets > BigInteger.ZERO) {
                    baseAssetsRequired.merge(order.wallet, -baseAssets, ::sumBigIntegers)
                }
                if (quoteAssets > BigInteger.ZERO) {
                    quoteAssetsRequired.merge(order.wallet, -quoteAssets, ::sumBigIntegers)
                }
            }
        }

        baseAssetsRequired.forEach { (wallet, required) ->
            if (required + market.baseAssetsRequired(wallet) > (
                    state.balances[wallet]?.get(market.id.baseAsset())
                        ?: BigInteger.ZERO
                    )
            ) {
                logger.debug { "Wallet $wallet requires $required + ${market.baseAssetsRequired(wallet)} = ${required + market.baseAssetsRequired(wallet)} but only has ${state.balances[wallet]?.get(market.id.baseAsset())}" }
                return SequencerError.ExceedsLimit
            }
        }

        quoteAssetsRequired.forEach { (wallet, required) ->
            if (required + market.quoteAssetsRequired(wallet) > (
                    state.balances[wallet]?.get(market.id.quoteAsset())
                        ?: BigInteger.ZERO
                    )
            ) {
                logger.debug { "Wallet $wallet requires $required + ${market.quoteAssetsRequired(wallet)} = ${required + market.quoteAssetsRequired(wallet)} but only has ${state.balances[wallet]?.get(market.id.quoteAsset())}" }
                return SequencerError.ExceedsLimit
            }
        }

        return null
    }

    override fun start() {
        logger.info { "Starting${if (inSandboxMode) " in sandbox mode" else ""}" }

        stop = false
        sequencerThread = thread(start = false, name = "sequencer", isDaemon = false) {
            val inputTailer = inputQueue.createTailer("sequencer")

            if (checkpointsPath != null) {
                restoreFromLatestValidCheckpoint(inputTailer, checkpointsPath)
            }

            val lastSequenceNumberProcessedBeforeRestart = getLastSequenceNumberInOutputQueue()

            val outputAppender = outputQueue.acquireAppender()
            var tailerPrevState = inputTailer.state()
            while (!stop) {
                val tailerState = inputTailer.state()
                if (tailerState == TailerState.END_OF_CYCLE && tailerState != tailerPrevState) {
                    if (checkpointsPath != null) {
                        saveCheckpoint(checkpointsPath, inputTailer.cycle())
                    }
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
        sequencerThread.uncaughtExceptionHandler = UncaughtExceptionHandler { _, throwable ->
            logger.error(throwable) { "Error in sequencer main thread" }
            stop()
        }
        sequencerThread.start()

        logger.info { "Started" }
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

    private fun saveCheckpoint(checkpointsPath: Path, currentCycle: Int) {
        val checkpointPath = Path.of(checkpointsPath.toString(), currentCycle.toString())
        logger.debug { "Saving checkpoint to $checkpointPath" }
        state.persist(checkpointPath)
        logger.debug { "Saved checkpoint" }
    }

    private fun restoreFromLatestValidCheckpoint(inputTailer: ExcerptTailer, checkpointsPath: Path) {
        var currentCycle = inputTailer.cycle()

        while (true) {
            // moveToCycle moves tailer to the start of the cycle
            if (inputTailer.moveToCycle(currentCycle)) {
                // restore from previous cycle's checkpoint unless we are in the first cycle
                if (currentCycle == inputQueue.firstCycle()) {
                    break
                } else {
                    val prevCycle = inputQueue.nextCycle(currentCycle, TailerDirection.BACKWARD)
                    try {
                        val checkpointPath = Path.of(checkpointsPath.toString(), prevCycle.toString())
                        logger.debug { "Restoring from checkpoint $checkpointPath" }
                        state.load(checkpointPath)
                        logger.debug { "Restored from checkpoint" }
                        break
                    } catch (e: Throwable) {
                        // go back one cycle and try again
                        logger.warn(e) { "Failed to recover from checkpoint $prevCycle" }
                        state.clear()
                        currentCycle = prevCycle
                    }
                }
            } else {
                break
            }
        }
    }

    override fun stop() {
        logger.info { "Stopping" }
        stop = true
        sequencerThread.join(100)
        sequencerThread.stop()
        logger.info { "Stopped" }
    }
}
