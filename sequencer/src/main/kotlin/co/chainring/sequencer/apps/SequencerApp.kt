package co.chainring.sequencer.apps

import co.chainring.core.model.Symbol
import co.chainring.sequencer.core.Asset
import co.chainring.sequencer.core.FeeRate
import co.chainring.sequencer.core.FeeRates
import co.chainring.sequencer.core.LevelOrder
import co.chainring.sequencer.core.Market
import co.chainring.sequencer.core.MarketId
import co.chainring.sequencer.core.SequencerState
import co.chainring.sequencer.core.WalletAddress
import co.chainring.sequencer.core.notional
import co.chainring.sequencer.core.notionalPlusFee
import co.chainring.sequencer.core.sumBigIntegers
import co.chainring.sequencer.core.toAsset
import co.chainring.sequencer.core.toBigDecimal
import co.chainring.sequencer.core.toBigInteger
import co.chainring.sequencer.core.toDecimalValue
import co.chainring.sequencer.core.toIntegerValue
import co.chainring.sequencer.core.toOrderGuid
import co.chainring.sequencer.core.toWalletAddress
import co.chainring.sequencer.proto.BalanceChange
import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.OrderBatch
import co.chainring.sequencer.proto.OrderChangeRejected
import co.chainring.sequencer.proto.OrderChanged
import co.chainring.sequencer.proto.SequencerError
import co.chainring.sequencer.proto.SequencerRequest
import co.chainring.sequencer.proto.SequencerResponse
import co.chainring.sequencer.proto.TradeCreated
import co.chainring.sequencer.proto.balanceChange
import co.chainring.sequencer.proto.copy
import co.chainring.sequencer.proto.marketCreated
import co.chainring.sequencer.proto.sequencerResponse
import co.chainring.sequencer.proto.withdrawalCreated
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
import kotlin.system.exitProcess
import co.chainring.sequencer.core.inputQueue as defaultInputQueue
import co.chainring.sequencer.core.outputQueue as defaultOutputQueue

class SequencerApp(
    val inputQueue: RollingChronicleQueue = defaultInputQueue,
    val outputQueue: RollingChronicleQueue = defaultOutputQueue,
    val checkpointsPath: Path?,
    val inSandboxMode: Boolean = System.getenv("SANDBOX_MODE").toBoolean(),
    private val strictReplayValidation: Boolean = System.getenv("STRICT_REPLAY_VALIDATION").toBoolean(),
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
                    val currentMarket = state.markets.getValue(marketId)
                    if (market.tickSize.toBigDecimal() != currentMarket.tickSize ||
                        market.baseDecimals != currentMarket.baseDecimals ||
                        market.quoteDecimals != currentMarket.quoteDecimals
                    ) {
                        error = SequencerError.MarketExists
                    }
                } else {
                    val tickSize = market.tickSize.toBigDecimal()
                    val halfTick = tickSize.setScale(tickSize.scale() + 1) / BigDecimal.valueOf(2)
                    val marketPrice = market.marketPrice.toBigDecimal()
                    if (BigDecimal.ZERO.compareTo((marketPrice + halfTick).remainder(tickSize)) != 0) {
                        // initial market price is not exactly between ticks
                        error = SequencerError.InvalidPrice
                    } else {
                        state.markets[marketId] = Market(
                            id = marketId,
                            tickSize = tickSize,
                            initialMarketPrice = marketPrice,
                            maxLevels = market.maxLevels,
                            maxOrdersPerLevel = market.maxOrdersPerLevel,
                            baseDecimals = market.baseDecimals,
                            quoteDecimals = market.quoteDecimals,
                            minFee = if (market.hasMinFee()) market.minFee.toBigInteger() else BigInteger.ZERO,
                        )
                    }
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
                                this.baseDecimals = market.baseDecimals
                                this.quoteDecimals = market.quoteDecimals
                                state.markets[marketId]?.levels?.let { levels ->
                                    this.minAllowedBid = levels[0].price.toDecimalValue()
                                    this.maxAllowedOffer = levels[levels.lastIndex - 1].price.toDecimalValue()
                                }
                                this.minFee = if (market.hasMinFee()) market.minFee else BigInteger.ZERO.toIntegerValue()
                            },
                        )
                    }
                }
            }
            SequencerRequest.Type.SetFeeRates -> {
                var error: SequencerError? = null

                val feeRates = request.feeRates
                if (feeRates == null || !FeeRate.isValid(feeRates.maker) || !FeeRate.isValid(feeRates.taker)) {
                    error = SequencerError.InvalidFeeRate
                } else {
                    state.feeRates = FeeRates(
                        maker = FeeRate(feeRates.maker),
                        taker = FeeRate(feeRates.taker),
                    )
                }

                sequencerResponse {
                    this.guid = request.guid
                    this.sequence = sequence
                    this.processingTime = System.nanoTime() - startTime
                    error?.let { this.error = it } ?: run {
                        this.feeRatesSet = feeRates
                    }
                }
            }

            SequencerRequest.Type.SetWithdrawalFees -> {
                var error: SequencerError? = null

                val withdrawalFees = request.withdrawalFeesList
                if (withdrawalFees.isEmpty()) {
                    error = SequencerError.InvalidWithdrawalFee
                } else {
                    withdrawalFees.forEach {
                        state.withdrawalFees[Symbol(it.asset)] = it.value.toBigInteger()
                    }
                }

                sequencerResponse {
                    this.guid = request.guid
                    this.sequence = sequence
                    this.processingTime = System.nanoTime() - startTime
                    error?.let { this.error = it } ?: run {
                        this.withdrawalFeesSet.addAll(withdrawalFees)
                    }
                }
            }

            SequencerRequest.Type.SetMarketMinFees -> {
                var error: SequencerError? = null

                val marketMinFees = request.marketMinFeesList
                if (marketMinFees.isEmpty()) {
                    error = SequencerError.InvalidMarketMinFee
                } else {
                    marketMinFees.forEach {
                        state.markets[MarketId(it.marketId)]?.minFee = it.minFee.toBigInteger()
                    }
                }

                sequencerResponse {
                    this.guid = request.guid
                    this.sequence = sequence
                    this.processingTime = System.nanoTime() - startTime
                    error?.let { this.error = it } ?: run {
                        this.marketMinFeesSet.addAll(marketMinFees)
                    }
                }
            }

            SequencerRequest.Type.ApplyOrderBatch -> {
                var ordersChanged: List<OrderChanged> = emptyList()
                var ordersChangeRejected: List<OrderChangeRejected> = emptyList()
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
                    val adjustedOrderBatch = adjustBatchForPercentageMarketOrders(market, orderBatch)
                    error = checkLimits(market, adjustedOrderBatch)
                    if (error == null) {
                        val result = market.applyOrderBatch(adjustedOrderBatch, state.feeRates)
                        ordersChanged = result.ordersChanged
                        ordersChangeRejected = result.ordersChangeRejected
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
                    this.ordersChangeRejected.addAll(ordersChangeRejected)
                    market?.let {
                        this.bidOfferState = market.getBidOfferState()
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

                val withdrawalsCreated = mutableMapOf<String, BigInteger>()
                balanceBatch.withdrawalsList.forEach { withdrawal ->
                    val withdrawalFee = state.withdrawalFees[Symbol(withdrawal.asset)] ?: BigInteger.ZERO
                    state.balances[withdrawal.wallet.toWalletAddress()]?.let { balanceByAsset ->
                        val asset = withdrawal.asset.toAsset()
                        val requestedAmount = withdrawal.amount.toBigInteger()
                        val balance = balanceByAsset[withdrawal.asset.toAsset()] ?: BigInteger.ZERO
                        val withdrawalAmount = if (requestedAmount == BigInteger.ZERO) balance else requestedAmount
                        if (withdrawalAmount > withdrawalFee && withdrawalAmount <= balance) {
                            val wallet = withdrawal.wallet.toWalletAddress()
                            balanceByAsset.merge(asset, -withdrawalAmount, ::sumBigIntegers)
                            balancesChanged.merge(Pair(wallet, asset), -withdrawalAmount, ::sumBigIntegers)
                            withdrawalsCreated[withdrawal.externalGuid] = withdrawalFee
                        }
                    }
                }

                balanceBatch.failedWithdrawalsList.forEach { failedWithdrawal ->
                    val wallet = failedWithdrawal.wallet.toWalletAddress()
                    val asset = failedWithdrawal.asset.toAsset()
                    val amount = failedWithdrawal.amount.toBigInteger()
                    state.balances.getOrPut(wallet) { mutableMapOf() }.merge(asset, amount, ::sumBigIntegers)
                    balancesChanged.merge(Pair(wallet, asset), amount, ::sumBigIntegers)
                }

                balanceBatch.failedSettlementsList.forEach { failedSettlement ->
                    val marketId = MarketId(failedSettlement.marketId)
                    state.markets[marketId]?.let { market ->
                        val baseAsset = market.id.baseAsset()
                        val quoteAsset = market.id.quoteAsset()
                        val baseAmount = failedSettlement.trade.amount.toBigInteger()
                        val price = failedSettlement.trade.price.toBigDecimal()
                        val notional = notional(baseAmount, price, market.baseDecimals, market.quoteDecimals)

                        val sellWallet = failedSettlement.sellWallet.toWalletAddress()
                        val sellerBaseRefund = baseAmount
                        val sellerQuoteRefund = (notional - failedSettlement.trade.sellerFee.toBigInteger()).negate()

                        val buyWallet = failedSettlement.buyWallet.toWalletAddress()
                        val buyerBaseRefund = baseAmount.negate()
                        val buyerQuoteRefund = notional + failedSettlement.trade.buyerFee.toBigInteger()

                        state.balances.getOrPut(sellWallet) { mutableMapOf() }.merge(baseAsset, sellerBaseRefund, ::sumBigIntegers)
                        balancesChanged.merge(Pair(sellWallet, baseAsset), sellerBaseRefund, ::sumBigIntegers)

                        state.balances.getOrPut(sellWallet) { mutableMapOf() }.merge(quoteAsset, sellerQuoteRefund, ::sumBigIntegers)
                        balancesChanged.merge(Pair(sellWallet, quoteAsset), sellerQuoteRefund, ::sumBigIntegers)

                        state.balances.getOrPut(buyWallet) { mutableMapOf() }.merge(baseAsset, buyerBaseRefund, ::sumBigIntegers)
                        balancesChanged.merge(Pair(buyWallet, baseAsset), buyerBaseRefund, ::sumBigIntegers)

                        state.balances.getOrPut(buyWallet) { mutableMapOf() }.merge(quoteAsset, buyerQuoteRefund, ::sumBigIntegers)
                        balancesChanged.merge(Pair(buyWallet, quoteAsset), buyerQuoteRefund, ::sumBigIntegers)
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
                    this.withdrawalsCreated.addAll(
                        withdrawalsCreated.map {
                            withdrawalCreated {
                                this.externalGuid = it.key
                                this.fee = it.value.toIntegerValue()
                            }
                        },
                    )
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

    private fun adjustBatchForPercentageMarketOrders(market: Market, orderBatch: OrderBatch): OrderBatch {
        return if (orderBatch.ordersToAddList.any { isOrderWithPercentage(it) }) {
            orderBatch.copy {
                this.ordersToAdd.clear()
                this.ordersToAdd.addAll(
                    orderBatch.ordersToAddList.map { order ->
                        if (isOrderWithPercentage(order)) {
                            val walletAddress = orderBatch.wallet.toWalletAddress()
                            order.copy {
                                this.amount = if (order.type == Order.Type.MarketSell) {
                                    calculateAmountForPercentageSell(
                                        market,
                                        walletAddress,
                                        order.percentage,
                                    ).toIntegerValue()
                                } else {
                                    val (amount, maxAvailable) = calculateAmountForPercentageBuy(
                                        market,
                                        walletAddress,
                                        order.percentage,
                                    )
                                    maxAvailable?.let { this.maxAvailable = it.toIntegerValue() }
                                    amount.toIntegerValue()
                                }
                            }
                        } else {
                            order
                        }
                    },
                )
            }
        } else {
            orderBatch
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

    private fun isOrderWithPercentage(order: Order) = order.hasPercentage() && order.percentage != 0 && (order.type == Order.Type.MarketSell || order.type == Order.Type.MarketBuy)

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
                    val notionalAndFee = calculateLimitBuyOrderNotionalPlusFee(order, market)
                    quoteAssetsRequired.merge(orderBatch.wallet.toWalletAddress(), notionalAndFee, ::sumBigIntegers)
                }
                Order.Type.MarketBuy -> {
                    // the quote assets required for a market buy depends on what the clearing price would be
                    val (clearingPrice, availableQuantity) = market.clearingPriceAndQuantityForMarketBuy(order.amount.toBigInteger())
                    quoteAssetsRequired.merge(
                        orderBatch.wallet.toWalletAddress(),
                        notionalPlusFee(availableQuantity, clearingPrice, market.baseDecimals, market.quoteDecimals, state.feeRates.taker),
                        ::sumBigIntegers,
                    )
                }
                else -> {}
            }
        }
        orderBatch.ordersToChangeList.forEach { orderChange: Order ->
            market.ordersByGuid[orderChange.guid.toOrderGuid()]?.let { order: LevelOrder ->
                val (oldBaseAssets, oldQuoteAssets) = market.assetsReservedForOrder(order)
                if (oldBaseAssets > BigInteger.ZERO) { // LimitSell
                    baseAssetsRequired.merge(
                        order.wallet,
                        orderChange.amount.toBigInteger() - order.quantity,
                        ::sumBigIntegers,
                    )
                }
                if (oldQuoteAssets > BigInteger.ZERO) { // LimitBuy
                    val previousNotionalAndFee = notionalPlusFee(order.quantity, market.levels[order.levelIx].price, market.baseDecimals, market.quoteDecimals, state.feeRates.maker)
                    val notionalAndFee = calculateLimitBuyOrderNotionalPlusFee(orderChange, market)
                    quoteAssetsRequired.merge(order.wallet, notionalAndFee - previousNotionalAndFee, ::sumBigIntegers)
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
            val baseRequired = market.baseAssetsRequired(wallet)
            val baseBalance = state.balances[wallet]?.get(market.id.baseAsset()) ?: BigInteger.ZERO
            if (required + baseRequired > baseBalance) {
                logger.debug { "Wallet $wallet requires $required + $baseRequired = ${required + baseRequired} but only has $baseBalance" }
                return SequencerError.ExceedsLimit
            }
        }

        quoteAssetsRequired.forEach { (wallet, required) ->
            val quoteRequired = market.quoteAssetsRequired(wallet)
            val quoteBalance = state.balances[wallet]?.get(market.id.quoteAsset()) ?: BigInteger.ZERO
            if (required + quoteRequired > quoteBalance) {
                logger.debug { "Wallet $wallet requires $required + $quoteRequired = ${required + quoteRequired} but only has $quoteBalance" }
                return SequencerError.ExceedsLimit
            }
        }

        return null
    }

    private fun calculateAmountForPercentageSell(market: Market, wallet: WalletAddress, percent: Int): BigInteger {
        return market.calculateAmountForPercentageSell(
            wallet,
            state.balances[wallet]?.get(market.id.baseAsset()) ?: BigInteger.ZERO,
            percent,
        )
    }

    private fun calculateAmountForPercentageBuy(market: Market, wallet: WalletAddress, percent: Int): Pair<BigInteger, BigInteger?> {
        return market.calculateAmountForPercentageBuy(
            wallet,
            state.balances[wallet]?.get(market.id.quoteAsset()) ?: BigInteger.ZERO,
            percent,
            state.feeRates.taker.value.toBigInteger(),
        )
    }

    private fun calculateLimitBuyOrderNotionalPlusFee(order: Order, market: Market): BigInteger {
        return if (order.price.toBigDecimal() >= market.bestOffer) {
            // limit order crosses the market
            val orderPrice = order.price.toBigDecimal()
            val levelIx = market.levelIx(orderPrice)

            val (clearingPrice, availableQuantity) = market.clearingPriceAndQuantityForMarketBuy(order.amount.toBigInteger(), stopAtLevelIx = levelIx)
            val remainingQuantity = order.amount.toBigInteger() - availableQuantity

            val marketChunkNotional = notionalPlusFee(availableQuantity, clearingPrice, market.baseDecimals, market.quoteDecimals, state.feeRates.taker)
            val limitChunkNotional = notionalPlusFee(remainingQuantity, orderPrice, market.baseDecimals, market.quoteDecimals, state.feeRates.maker)

            marketChunkNotional + limitChunkNotional
        } else {
            notionalPlusFee(order.amount, order.price, market.baseDecimals, market.quoteDecimals, state.feeRates.maker)
        }
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
            var requestsProcessedSinceStarted: Long = 0

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

                            if (strictReplayValidation && response.sequence <= lastSequenceNumberProcessedBeforeRestart) {
                                // validate actual response matches expected while replaying requests
                                loadResponseFromOutputQueue(response.sequence)?.let {
                                    val expectedResponse = it.toBuilder().setProcessingTime(response.processingTime).build()
                                    if (request.type != SequencerRequest.Type.GetState && response != expectedResponse) {
                                        logger.error { "Actual response did not match expected, exiting. Sequence: ${dc.index()}, requests processed since start: $requestsProcessedSinceStarted, request: $request, expected response: $expectedResponse, actual response: $response" }
                                        exitProcess(1)
                                    }
                                }
                                if (requestsProcessedSinceStarted.toInt() % 1000 == 0) {
                                    logger.info { "Replayed and validated $requestsProcessedSinceStarted requests" }
                                }
                            }

                            requestsProcessedSinceStarted += 1
                            if (response.sequence == lastSequenceNumberProcessedBeforeRestart) {
                                logger.info { "Caught up after re-processing $requestsProcessedSinceStarted requests" }
                            }
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

    private lateinit var outputTailer: ExcerptTailer
    private fun loadResponseFromOutputQueue(sequence: Long): SequencerResponse? {
        if (!::outputTailer.isInitialized) {
            outputTailer = outputQueue.createTailer()
        }

        var result: SequencerResponse? = null
        outputTailer.moveToIndex(sequence)
        outputTailer.readingDocument().use {
            if (it.isPresent) {
                it.wire()?.read()?.bytes { bytes ->
                    result = SequencerResponse.parseFrom(bytes.toByteArray())
                }
            }
        }
        return result
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

    fun blockUntilShutdown() {
        while (!stop) {
            Thread.sleep(20)
        }
        sequencerThread.join()
    }
}
