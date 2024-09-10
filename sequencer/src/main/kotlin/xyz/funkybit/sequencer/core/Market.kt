package xyz.funkybit.sequencer.core

import io.github.oshai.kotlinlogging.KotlinLogging
import xyz.funkybit.core.model.Percentage
import xyz.funkybit.sequencer.core.datastructure.AVLTree
import xyz.funkybit.sequencer.core.datastructure.ObjectPool
import xyz.funkybit.sequencer.proto.BalanceChange
import xyz.funkybit.sequencer.proto.BidOfferState
import xyz.funkybit.sequencer.proto.MarketCheckpoint
import xyz.funkybit.sequencer.proto.Order
import xyz.funkybit.sequencer.proto.OrderBatch
import xyz.funkybit.sequencer.proto.OrderChangeRejected
import xyz.funkybit.sequencer.proto.OrderChanged
import xyz.funkybit.sequencer.proto.OrderDisposition
import xyz.funkybit.sequencer.proto.TradeCreated
import xyz.funkybit.sequencer.proto.bidOfferState
import xyz.funkybit.sequencer.proto.copy
import xyz.funkybit.sequencer.proto.marketCheckpoint
import xyz.funkybit.sequencer.proto.orderChangeRejected
import xyz.funkybit.sequencer.proto.orderChanged
import xyz.funkybit.sequencer.proto.tradeCreated
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.CopyOnWriteArrayList

data class Market(
    val id: MarketId,
    val tickSize: BigDecimal,
    val maxOrdersPerLevel: Int,
    val baseDecimals: Int,
    val quoteDecimals: Int,
    var minFee: BigInteger = BigInteger.ZERO,
) {

    private val logger = KotlinLogging.logger { }

    fun price(levelIx: Int): BigDecimal = tickSize.multiply(levelIx.toBigDecimal())

    val levels = AVLTree<OrderBookLevel>()

    private val levelPool = ObjectPool(
        create = { OrderBookLevel.empty(maxOrdersPerLevel) },
        reset = { it.reset() },
        initialSize = 1000,
    )

    var maxOfferIx: Int = -1
        private set
    var bestOfferIx: Int = -1
        private set
    var bestBidIx: Int = -1
        private set
    var minBidIx: Int = -1
        private set

    // TODO - change these mutable maps to a HashMap that pre-allocates
    val buyOrdersByAccount = mutableMapOf<AccountGuid, CopyOnWriteArrayList<LevelOrder>>()
    val sellOrdersByAccount = mutableMapOf<AccountGuid, CopyOnWriteArrayList<LevelOrder>>()
    val ordersByGuid = mutableMapOf<OrderGuid, LevelOrder>()

    data class ConsumptionChange(
        val account: AccountGuid,
        val asset: Asset,
        val delta: BigInteger,
    )

    data class AddOrdersResult(
        val ordersChanged: List<OrderChanged>,
        val createdTrades: List<TradeCreated>,
        val balanceChanges: List<BalanceChange>,
        val consumptionChanges: List<ConsumptionChange>,
        val ordersChangeRejected: List<OrderChangeRejected>,
    )

    private fun sumBigIntegerPair(a: Pair<BigInteger, BigInteger>, b: Pair<BigInteger, BigInteger>) = Pair(a.first + b.first, a.second + b.second)

    fun applyOrderBatch(orderBatch: OrderBatch, feeRates: FeeRates): AddOrdersResult {
        val ordersChanged = mutableListOf<OrderChanged>()
        val ordersChangeRejected = mutableListOf<OrderChangeRejected>()
        val createdTrades = mutableListOf<TradeCreated>()
        val balanceChanges = mutableMapOf<Pair<AccountGuid, Asset>, BigInteger>()
        val consumptionChanges = mutableMapOf<AccountGuid, Pair<BigInteger, BigInteger>>()
        orderBatch.ordersToCancelList.forEach { cancelOrder ->
            val validationResult = validateOrderForAccount(orderBatch.account, cancelOrder.guid)
            if (validationResult == OrderChangeRejected.Reason.None) {
                removeOrder(cancelOrder.guid.toOrderGuid())?.let { result ->
                    ordersChanged.add(
                        orderChanged {
                            this.guid = cancelOrder.guid
                            this.disposition = OrderDisposition.Canceled
                        },
                    )
                    consumptionChanges.merge(
                        result.account,
                        Pair(-result.baseAssetAmount, -result.quoteAssetAmount),
                        ::sumBigIntegerPair,
                    )
                }
            } else {
                ordersChangeRejected.add(
                    orderChangeRejected {
                        this.guid = cancelOrder.guid
                        this.reason = validationResult
                    },
                )
            }
        }
        orderBatch.ordersToAddList.forEach { order ->
            val orderResult = addOrder(orderBatch.account, order, feeRates)
            ordersChanged.add(
                orderChanged {
                    this.guid = order.guid
                    this.disposition = orderResult.disposition
                    if (order.hasPercentage() && order.percentage > 0) {
                        this.newQuantity = order.amount
                    }
                },
            )
            if (orderResult.disposition == OrderDisposition.Accepted || orderResult.disposition == OrderDisposition.PartiallyFilled) {
                // immediately filled limit order's amount should not count to consumption
                val filledAmount = orderResult.executions.sumOf { it.amount }

                val feeRateInBps = when (orderResult.disposition) {
                    OrderDisposition.Accepted -> feeRates.maker
                    OrderDisposition.PartiallyFilled -> feeRates.taker
                    else -> throw RuntimeException("Unexpected order disposition")
                }

                when (order.type) {
                    Order.Type.LimitBuy -> consumptionChanges.merge(
                        orderBatch.account.toAccountGuid(),
                        Pair(
                            BigInteger.ZERO,
                            notionalPlusFee(order.amount.toBigInteger() - filledAmount, price(order.levelIx), baseDecimals, quoteDecimals, feeRateInBps),
                        ),
                        ::sumBigIntegerPair,
                    )

                    Order.Type.LimitSell -> consumptionChanges.merge(
                        orderBatch.account.toAccountGuid(),
                        Pair(order.amount.toBigInteger() - filledAmount, BigInteger.ZERO),
                        ::sumBigIntegerPair,
                    )

                    else -> {}
                }
            }
            // remainingAvailable is only set for market buy with 100% max and no quote assets reserved for other orders.
            // On the last trade for this order, any balance changes accumulated on previous trades for the quote asset will
            // be decremented and the remaining available will be passed in. Any remaining dust after applying
            // the balances changes from last trade will then be swept into the buyer fee of the last trade.
            val remainingAvailable = if (order.hasMaxAvailable()) order.maxAvailable.toBigInteger() else null
            orderResult.executions.forEachIndexed { index, execution ->
                processExecution(
                    account = orderBatch.account.toAccountGuid(),
                    takerOrder = order,
                    execution = execution,
                    createdTrades = createdTrades,
                    ordersChanged = ordersChanged,
                    balanceChanges = balanceChanges,
                    consumptionChanges = consumptionChanges,
                    feeRates = feeRates,
                    remainingAvailable = if (remainingAvailable != null && index + 1 == orderResult.executions.size) {
                        remainingAvailable + (balanceChanges[Pair(orderBatch.account.toAccountGuid(), id.quoteAsset())] ?: BigInteger.ZERO)
                    } else {
                        null
                    },
                )
            }
        }
        return AddOrdersResult(
            ordersChanged,
            createdTrades,
            balanceChanges.asBalanceChangesList(),
            consumptionChanges.flatMap {
                listOf(
                    ConsumptionChange(
                        account = it.key,
                        asset = this.id.baseAsset(),
                        delta = it.value.first,
                    ),
                    ConsumptionChange(
                        account = it.key,
                        asset = this.id.quoteAsset(),
                        delta = it.value.second,
                    ),
                )
            },
            ordersChangeRejected,
        )
    }

    fun getBidOfferState(): BidOfferState {
        if (bestBidIx >= bestOfferIx) {
            logState()
        }
        return bidOfferState {
            this.maxOfferIx = this@Market.maxOfferIx
            this.bestOfferIx = this@Market.bestOfferIx
            this.bestBidIx = this@Market.bestBidIx
            this.minBidIx = this@Market.minBidIx
        }
    }

    private fun processExecution(
        account: AccountGuid,
        takerOrder: Order,
        execution: Execution,
        createdTrades: MutableList<TradeCreated>,
        ordersChanged: MutableList<OrderChanged>,
        balanceChanges: MutableMap<Pair<AccountGuid, Asset>, BigInteger>,
        consumptionChanges: MutableMap<AccountGuid, Pair<BigInteger, BigInteger>>,
        feeRates: FeeRates,
        remainingAvailable: BigInteger?,
    ) {
        val notional = notional(execution.amount, execution.price, baseDecimals, quoteDecimals)

        val base = id.baseAsset()
        val quote = id.quoteAsset()

        val buyOrderGuid: Long
        val buyer: AccountGuid
        var buyerFee: BigInteger

        val sellOrderGuid: Long
        val seller: AccountGuid
        val sellerFee: BigInteger

        if (takerOrder.type == Order.Type.MarketBuy || takerOrder.type == Order.Type.LimitBuy) {
            buyOrderGuid = takerOrder.guid
            buyer = account
            buyerFee = notionalFee(notional, feeRates.taker)

            // remainingAvailable should only be non null for Market Buy with max (100%) and no quoteAsset already allocated to other orders in this market
            if (remainingAvailable != null && takerOrder.type == Order.Type.MarketBuy && takerOrder.hasPercentage() && takerOrder.percentage == 100) {
                val dust = remainingAvailable - (notional + buyerFee)
                // if the remaining after we apply this order is below the dust threshold, move the dust to the fee.
                // otherwise don't apply (this can happen if there was not enough liquidity in this market to fill the market buy)
                if (dust <= buyerFee) {
                    logger.debug { "Order ${takerOrder.guid}: Increasing buyer fee by $dust" }
                    buyerFee += dust
                }
            }

            sellOrderGuid = execution.counterOrder.guid.value
            seller = execution.counterOrder.account
            sellerFee = notionalFee(notional, execution.counterOrder.feeRate)

            consumptionChanges.merge(seller, Pair(-execution.amount, BigInteger.ZERO), ::sumBigIntegerPair)
        } else {
            buyOrderGuid = execution.counterOrder.guid.value
            buyer = execution.counterOrder.account
            buyerFee = notionalFee(notional, execution.counterOrder.feeRate)

            sellOrderGuid = takerOrder.guid
            seller = account
            sellerFee = notionalFee(notional, feeRates.taker)

            consumptionChanges.merge(buyer, Pair(BigInteger.ZERO, -(notional + buyerFee)), ::sumBigIntegerPair)
        }

        createdTrades.add(
            tradeCreated {
                this.buyOrderGuid = buyOrderGuid
                this.buyerFee = buyerFee.toIntegerValue()
                this.sellOrderGuid = sellOrderGuid
                this.sellerFee = sellerFee.toIntegerValue()
                amount = execution.amount.toIntegerValue()
                levelIx = execution.levelIx
                this.marketId = id.value
            },
        )

        ordersChanged.add(
            orderChanged {
                this.guid = execution.counterOrder.guid.value
                if (execution.counterOrderExhausted) {
                    this.disposition = OrderDisposition.Filled
                } else {
                    this.disposition = OrderDisposition.PartiallyFilled
                    this.newQuantity = execution.counterOrder.quantity.toIntegerValue()
                }
            },
        )

        balanceChanges.merge(Pair(buyer, quote), -(notional + buyerFee), ::sumBigIntegers)
        balanceChanges.merge(Pair(seller, base), -execution.amount, ::sumBigIntegers)
        balanceChanges.merge(Pair(buyer, base), execution.amount, ::sumBigIntegers)
        balanceChanges.merge(Pair(seller, quote), notional - sellerFee, ::sumBigIntegers)
    }

    fun autoReduce(account: AccountGuid, asset: Asset, limit: BigInteger): List<OrderChanged> {
        var total = BigInteger.ZERO
        return if (asset == id.baseAsset()) {
            sellOrdersByAccount[account]?.let { sellOrders ->
                sellOrders.sortedBy { it.level.ix }.mapNotNull { levelOrder ->
                    if (levelOrder.quantity <= limit - total) {
                        total += levelOrder.quantity
                        null
                    } else {
                        levelOrder.quantity = limit - total
                        total += levelOrder.quantity
                        orderChanged {
                            this.guid = levelOrder.guid.value
                            this.disposition = OrderDisposition.AutoReduced
                            this.newQuantity = levelOrder.quantity.toIntegerValue()
                        }
                    }
                }
            } ?: emptyList()
        } else {
            buyOrdersByAccount[account]?.let { buyOrders ->
                buyOrders.sortedByDescending { it.level.ix }.mapNotNull { levelOrder ->
                    val price = levelOrder.level.price
                    val notionalAmount = notionalPlusFee(levelOrder.quantity, price, baseDecimals, quoteDecimals, levelOrder.feeRate)
                    if (notionalAmount + total <= limit) {
                        total += notionalAmount
                        null
                    } else {
                        // invert the notional calculation using the remaining notional amount
                        val remainingNotionalPlusFee = (limit - total)

                        // Reduce remainingNotionalPlusFee by the expected fee
                        // Example calculation: when remainingNotionalPlusFee is 204 and fee is 2% we should end up with remainingNotional=200
                        // Formula is: remainingNotional = (204 / (100 + 2)) * 2
                        val feeRateInPercents = levelOrder.feeRate.inPercents().toBigDecimal()
                        val fee = ((remainingNotionalPlusFee.toBigDecimal() / (BigDecimal(100).setScale(10) + feeRateInPercents)) * feeRateInPercents).toBigInteger()
                        val remainingNotional = remainingNotionalPlusFee - fee

                        levelOrder.quantity = (remainingNotional.toBigDecimal() / price).movePointRight(baseDecimals - quoteDecimals).toBigInteger()
                        total += remainingNotionalPlusFee
                        orderChanged {
                            this.guid = levelOrder.guid.value
                            this.disposition = OrderDisposition.AutoReduced
                            this.newQuantity = levelOrder.quantity.toIntegerValue()
                        }
                    }
                }
            } ?: emptyList()
        }
    }

    fun baseAssetsRequired(account: AccountGuid): BigInteger =
        sellOrdersByAccount[account]?.map { it.quantity }?.reduceOrNull(::sumBigIntegers) ?: BigInteger.ZERO

    fun quoteAssetsRequired(account: AccountGuid): BigInteger =
        buyOrdersByAccount[account]?.map { order ->
            notionalPlusFee(order.quantity, order.level.price, baseDecimals, quoteDecimals, order.feeRate)
        }?.reduceOrNull(::sumBigIntegers) ?: BigInteger.ZERO

    private fun handleCrossingOrder(order: Order, stopAtLevelIx: Int? = null): AddOrderResult {
        val originalAmount = order.amount.toBigInteger()
        var remainingAmount = originalAmount
        val executions = mutableListOf<Execution>()
        val exhaustedLevels = mutableListOf<OrderBookLevel>()

        val isBuyOrder = order.type == Order.Type.MarketBuy || order.type == Order.Type.LimitBuy
        val isSellOrder = order.type == Order.Type.MarketSell || order.type == Order.Type.LimitSell

        if (isBuyOrder && bestOfferIx != -1 || isSellOrder && bestBidIx != -1) {
            var currentLevel = if (isBuyOrder) levels.get(bestOfferIx) else levels.get(bestBidIx)

            while (currentLevel != null) {
                val levelIx = currentLevel.ix

                if (stopAtLevelIx != null) {
                    // stopAtLevelIx is provided to handle crossing-market execution of limit orders
                    if (
                        isBuyOrder && currentLevel.ix > stopAtLevelIx ||
                        isSellOrder && currentLevel.ix < stopAtLevelIx
                    ) {
                        break
                    }
                }

                logger.debug { "handle crossing order $levelIx" }

                val orderBookLevelFill = currentLevel.fillOrder(remainingAmount)
                remainingAmount = orderBookLevelFill.remainingAmount
                executions.addAll(orderBookLevelFill.executions)

                // schedule removal for later, still might need to resolve prev or next level
                if (currentLevel.totalQuantity == BigInteger.ZERO) exhaustedLevels.add(currentLevel)

                if (remainingAmount == BigInteger.ZERO) break

                currentLevel = if (isBuyOrder) currentLevel.next() else currentLevel.prev()
            }

            if (isBuyOrder) {
                bestOfferIx = currentLevel?.let {
                    if (it.totalQuantity > BigInteger.ZERO) it.ix else it.next()?.ix
                } ?: -1
                // also reset maxOfferIx in case when sell side is fully exhausted
                if (bestOfferIx == -1) maxOfferIx = -1
            } else {
                bestBidIx = currentLevel?.let {
                    if (it.totalQuantity > BigInteger.ZERO) it.ix else it.prev()?.ix
                } ?: -1
                // also reset minBidIx in case when buy side is fully exhausted
                if (bestBidIx == -1) minBidIx = -1
            }

            exhaustedLevels.forEach {
                levels.remove(it.ix)
                levelPool.release(it)
            }
        }

        return if (remainingAmount < originalAmount) {
            // remove from buy/sell
            executions.forEach { execution ->
                if (execution.counterOrderExhausted) {
                    val ordersByAccount =
                        (if (order.type == Order.Type.MarketBuy || order.type == Order.Type.LimitBuy) sellOrdersByAccount else buyOrdersByAccount)

                    ordersByAccount[execution.counterOrder.account]?.let { orders ->
                        orders.remove(execution.counterOrder)
                        if (orders.isEmpty()) {
                            ordersByAccount.remove(execution.counterOrder.account)
                        }
                    }

                    ordersByGuid.remove(execution.counterOrder.guid)
                }
            }

            if (remainingAmount > BigInteger.ZERO) {
                AddOrderResult(OrderDisposition.PartiallyFilled, executions)
            } else {
                AddOrderResult(OrderDisposition.Filled, executions)
            }
        } else {
            if (order.type == Order.Type.LimitSell || order.type == Order.Type.LimitBuy) {
                AddOrderResult(OrderDisposition.Accepted, noExecutions)
            } else {
                logger.debug { "Order ${order.guid}: Market order rejected due to no match" }
                AddOrderResult(OrderDisposition.Rejected, noExecutions)
            }
        }
    }

    // if the order is found, returns account and how much of the base asset and quote asset it was consuming; null otherwise
    private fun removeOrder(guid: OrderGuid): RemoveOrderResult? {
        var ret: RemoveOrderResult? = null
        ordersByGuid[guid]?.let { levelOrder ->
            val level = levelOrder.level
            ret = if (level.side == BookSide.Buy) {
                buyOrdersByAccount[levelOrder.account]?.let {
                    it.remove(levelOrder)
                    if (it.isEmpty()) {
                        buyOrdersByAccount.remove(levelOrder.account)
                    }
                }
                RemoveOrderResult(levelOrder.account, BigInteger.ZERO, notionalPlusFee(levelOrder.quantity, level.price, baseDecimals, quoteDecimals, levelOrder.feeRate))
            } else {
                sellOrdersByAccount[levelOrder.account]?.let {
                    it.remove(levelOrder)
                    if (it.isEmpty()) {
                        sellOrdersByAccount.remove(levelOrder.account)
                    }
                }
                RemoveOrderResult(levelOrder.account, levelOrder.quantity, BigInteger.ZERO)
            }
            level.removeLevelOrder(levelOrder)
            // if we exhausted this level, we may need to adjust bid/offer values
            // and also remove level from the book
            if (level.totalQuantity == BigInteger.ZERO) {
                if (level.side == BookSide.Buy) {
                    if (level.ix == minBidIx) {
                        val nextLevel = level.next()
                        if (nextLevel == null || nextLevel.ix > bestBidIx) {
                            minBidIx = -1
                            bestBidIx = -1
                        } else {
                            minBidIx = nextLevel.ix
                        }
                    } else if (level.ix == bestBidIx) {
                        val prevLevel = level.prev()
                        if (prevLevel == null) {
                            minBidIx = -1
                            bestBidIx = -1
                        } else {
                            bestBidIx = prevLevel.ix
                        }
                    }
                } else {
                    if (level.ix == bestOfferIx) {
                        val nextLevel = level.next()
                        if (nextLevel == null) {
                            bestOfferIx = -1
                            maxOfferIx = -1
                        } else {
                            bestOfferIx = nextLevel.ix
                        }
                    } else if (level.ix == maxOfferIx) {
                        val prevLevel = level.prev()
                        if (prevLevel == null || prevLevel.ix < bestOfferIx) {
                            bestOfferIx = -1
                            maxOfferIx = -1
                        } else {
                            maxOfferIx = prevLevel.ix
                        }
                    }
                }
                levels.remove(level.ix)
                levelPool.release(level)
            }
            ordersByGuid.remove(guid)
        }
        return ret
    }

    fun addOrder(account: Long, order: Order, feeRates: FeeRates): AddOrderResult {
        return if (isBelowMinFee(order, feeRates)) {
            logger.debug { "Order ${order.guid} rejected since fee below min fee" }
            AddOrderResult(OrderDisposition.Rejected, noExecutions)
        } else if (order.type == Order.Type.LimitSell) {
            val levelIx = order.levelIx
            if (bestBidIx != -1 && levelIx <= bestBidIx) {
                // in case when crossing market execute as market sell order until `levelIx`
                val crossingOrderResult = handleCrossingOrder(order, stopAtLevelIx = levelIx)
                val filledAmount = crossingOrderResult.executions.sumOf { it.amount }
                val remainingAmount = order.amount.toBigInteger() - filledAmount

                if (remainingAmount > BigInteger.ZERO) {
                    if (levelIx > maxOfferIx) {
                        maxOfferIx = levelIx
                    }

                    // and then create limit order for the remaining amount
                    val adjustedOrder = order.copy { amount = remainingAmount.toIntegerValue() }
                    val disposition = createLimitSellOrder(levelIx, account, adjustedOrder, feeRates.maker)
                    val finalDisposition = if (crossingOrderResult.disposition == OrderDisposition.Accepted && disposition == OrderDisposition.Rejected) {
                        logger.debug { "Order ${order.guid}: remaining LimitSell amount rejected" }
                        OrderDisposition.Rejected
                    } else {
                        crossingOrderResult.disposition
                    }

                    AddOrderResult(
                        finalDisposition,
                        crossingOrderResult.executions,
                    )
                } else {
                    AddOrderResult(crossingOrderResult.disposition, crossingOrderResult.executions)
                }
            } else {
                if (levelIx > maxOfferIx) {
                    maxOfferIx = levelIx
                }
                // or just create a limit order
                val disposition = createLimitSellOrder(levelIx, account, order, feeRates.maker)
                AddOrderResult(disposition, noExecutions)
            }
        } else if (order.type == Order.Type.LimitBuy) {
            val levelIx = order.levelIx
            if (bestOfferIx != -1 && levelIx >= bestOfferIx) {
                // in case when crossing market execute as market buy order until `levelIx`
                val crossingOrderResult = handleCrossingOrder(order, stopAtLevelIx = levelIx)
                val filledAmount = crossingOrderResult.executions.sumOf { it.amount }
                val remainingAmount = order.amount.toBigInteger() - filledAmount

                if (remainingAmount > BigInteger.ZERO) {
                    if (levelIx < minBidIx || minBidIx == -1) {
                        minBidIx = levelIx
                    }

                    // and then create limit order for the remaining amount
                    val adjustedOrder = order.copy { amount = remainingAmount.toIntegerValue() }
                    val disposition = createLimitBuyOrder(levelIx, account, adjustedOrder, feeRates.maker)

                    val finalDisposition = if (crossingOrderResult.disposition == OrderDisposition.Accepted && disposition == OrderDisposition.Rejected) {
                        logger.debug { "Order ${order.guid}: remaining LimitBuy amount rejected" }
                        OrderDisposition.Rejected
                    } else {
                        crossingOrderResult.disposition
                    }

                    AddOrderResult(
                        finalDisposition,
                        crossingOrderResult.executions,
                    )
                } else {
                    AddOrderResult(crossingOrderResult.disposition, crossingOrderResult.executions)
                }
            } else {
                if (levelIx < minBidIx || minBidIx == -1) {
                    minBidIx = levelIx
                }

                // or just create a limit order
                val disposition = createLimitBuyOrder(levelIx, account, order, feeRates.maker)
                AddOrderResult(disposition, noExecutions)
            }
        } else if (order.type == Order.Type.MarketBuy) {
            handleCrossingOrder(order)
        } else if (order.type == Order.Type.MarketSell) {
            handleCrossingOrder(order)
        } else {
            logger.error { "Order ${order.guid}: Unknown order type ${order.type} rejected" }
            AddOrderResult(OrderDisposition.Rejected, noExecutions)
        }
    }

    fun isBelowMinFee(order: Order, feeRates: FeeRates): Boolean {
        val (levelIx, feeRate) = when (order.type) {
            Order.Type.MarketBuy -> {
                if (feeRates.taker.value == 0L) {
                    return false
                }
                Pair(bestOfferIx, feeRates.taker)
            }
            Order.Type.MarketSell -> {
                if (feeRates.taker.value == 0L) {
                    return false
                }
                Pair(bestBidIx, feeRates.taker)
            }
            else -> {
                if (feeRates.maker.value == 0L) {
                    return false
                }
                Pair(order.levelIx, feeRates.maker)
            }
        }

        // in case of empty book let it proceed, order will be rejected anyway
        if (levelIx == -1) return false

        return notionalFee(notional(order.amount.toBigInteger(), price(levelIx), baseDecimals, quoteDecimals), feeRate) < minFee
    }

    private fun createLimitBuyOrder(levelIx: Int, account: Long, order: Order, feeRate: FeeRate): OrderDisposition {
        val (disposition, levelOrder) = getOrCreateLevel(levelIx, BookSide.Buy).addOrder(account, order, feeRate)
        if (disposition == OrderDisposition.Accepted) {
            buyOrdersByAccount.getOrPut(levelOrder!!.account) { CopyOnWriteArrayList() }.add(levelOrder)
            ordersByGuid[levelOrder.guid] = levelOrder
            if (bestBidIx == -1 || levelIx > bestBidIx) {
                bestBidIx = levelIx
            }
        } else {
            logger.debug { "Limit Buy order rejected due to level exhaustion" }
        }
        return disposition
    }

    private fun createLimitSellOrder(levelIx: Int, account: Long, order: Order, feeRate: FeeRate): OrderDisposition {
        val (disposition, levelOrder) = getOrCreateLevel(levelIx, BookSide.Sell).addOrder(account, order, feeRate)
        if (disposition == OrderDisposition.Accepted) {
            sellOrdersByAccount.getOrPut(levelOrder!!.account) { CopyOnWriteArrayList() }.add(levelOrder)
            ordersByGuid[levelOrder.guid] = levelOrder
            if (bestOfferIx == -1 || levelIx < bestOfferIx) {
                bestOfferIx = levelIx
            }
        } else {
            logger.debug { "Limit Sell order rejected due to level exhaustion" }
        }
        return disposition
    }

    private fun getOrCreateLevel(levelIx: Int, side: BookSide): OrderBookLevel {
        return levels.get(levelIx)
            ?: levelPool
                .borrow { level -> level.init(levelIx, side, price(levelIx)) }
                .let { levels.add(it) }
    }

    // calculate how much liquidity is available for a market buy (until stopAtLevelIx), and what the final clearing price would be
    fun clearingPriceAndQuantityForMarketBuy(amount: BigInteger, stopAtLevelIx: Int? = null): Pair<BigDecimal, BigInteger> {
        var remainingAmount = amount
        var totalPriceUnits = BigDecimal.ZERO

        var currentLevel = if (bestOfferIx != -1) levels.get(bestOfferIx) else null
        while (currentLevel != null && (stopAtLevelIx == null || currentLevel.ix <= stopAtLevelIx)) {
            val quantityAtLevel = currentLevel.totalQuantity.min(remainingAmount)
            totalPriceUnits += quantityAtLevel.toBigDecimal().setScale(18) * currentLevel.price
            remainingAmount -= quantityAtLevel

            if (remainingAmount == BigInteger.ZERO) break

            currentLevel = currentLevel.next()
        }

        val availableQuantity = amount - remainingAmount
        val clearingPrice = if (availableQuantity == BigInteger.ZERO) BigDecimal.ZERO else totalPriceUnits / availableQuantity.toBigDecimal()

        return Pair(clearingPrice, availableQuantity)
    }

    fun quantityAndNotionalForMarketBuy(amount: BigInteger): Pair<BigInteger, BigInteger> {
        var remainingAmount = amount
        var notional = BigInteger.ZERO

        var currentLevel = if (bestOfferIx != -1) levels.get(bestOfferIx) else null
        while (currentLevel != null) {
            val quantityAtLevel = currentLevel.totalQuantity.min(remainingAmount)
            notional += notional(quantityAtLevel, currentLevel.price, baseDecimals, quoteDecimals)
            remainingAmount -= quantityAtLevel
            if (remainingAmount == BigInteger.ZERO) break

            currentLevel = currentLevel.next()
        }
        return Pair(amount - remainingAmount, notional)
    }

    fun quantityForMarketBuy(notional: BigInteger): BigInteger {
        var remainingNotional = notional
        var baseAmount = BigInteger.ZERO

        var currentLevel = if (bestOfferIx != -1) levels.get(bestOfferIx) else null
        while (currentLevel != null) {
            val quantityAtLevel = currentLevel.totalQuantity

            if (quantityAtLevel > BigInteger.ZERO) {
                val notionalAtLevel = remainingNotional.min(notional(quantityAtLevel, currentLevel.price, baseDecimals, quoteDecimals))

                if (notionalAtLevel == remainingNotional) {
                    return baseAmount + quantityFromNotionalAndPrice(
                        remainingNotional,
                        price(currentLevel.ix),
                        baseDecimals,
                        quoteDecimals,
                    )
                }

                baseAmount += quantityAtLevel
                remainingNotional -= notionalAtLevel
            }

            currentLevel = currentLevel.next()
        }

        return baseAmount
    }

    // calculate how much liquidity is available for a market sell order (until stopAtLevelIx)
    fun clearingQuantityForMarketSell(amount: BigInteger, stopAtLevelIx: Int? = null): BigInteger {
        var remainingAmount = amount

        var currentLevel = if (bestBidIx != -1) levels.get(bestBidIx) else null
        while (currentLevel != null && (stopAtLevelIx == null || currentLevel.ix >= stopAtLevelIx)) {
            val quantityAtLevel = currentLevel.totalQuantity.min(remainingAmount)
            remainingAmount -= quantityAtLevel

            if (remainingAmount == BigInteger.ZERO) break

            currentLevel = currentLevel.prev()
        }

        return amount - remainingAmount
    }

    fun quantityAndNotionalForMarketSell(amount: BigInteger): Pair<BigInteger, BigInteger> {
        var remainingAmount = amount
        var notionalReceived = BigInteger.ZERO
        var currentLevel = if (bestBidIx != -1) levels.get(bestBidIx) else null
        while (currentLevel != null) {
            val quantityAtLevel = currentLevel.totalQuantity.min(remainingAmount)
            notionalReceived += notional(quantityAtLevel, currentLevel.price, baseDecimals, quoteDecimals)
            remainingAmount -= quantityAtLevel
            if (remainingAmount == BigInteger.ZERO) {
                break
            }
            currentLevel = currentLevel.prev()
        }
        return Pair(amount - remainingAmount, notionalReceived)
    }

    fun quantityForMarketSell(notional: BigInteger): BigInteger {
        var remainingNotional = notional
        var baseAmount = BigInteger.ZERO

        var currentLevel = if (bestBidIx != -1) levels.get(bestBidIx) else null
        while (currentLevel != null) {
            val quantityAtLevel = currentLevel.totalQuantity
            if (quantityAtLevel > BigInteger.ZERO) {
                val notionalAtLevel =
                    remainingNotional.min(notional(quantityAtLevel, currentLevel.price, baseDecimals, quoteDecimals))
                if (notionalAtLevel == remainingNotional) {
                    return baseAmount + quantityFromNotionalAndPrice(
                        remainingNotional,
                        currentLevel.price,
                        baseDecimals,
                        quoteDecimals,
                    )
                }
                baseAmount += quantityAtLevel
                remainingNotional -= notionalAtLevel
            }
            currentLevel = currentLevel.prev()
        }
        return baseAmount
    }

    fun calculateAmountForPercentageSell(account: AccountGuid, assetBalance: BigInteger, percent: Int): BigInteger {
        val baseAssetLimit = BigInteger.ZERO.max(assetBalance - baseAssetsRequired(account))
        return clearingQuantityForMarketSell(baseAssetLimit) * percent.toBigInteger() / Percentage.MAX_VALUE.toBigInteger()
    }

    fun calculateAmountForPercentageBuy(account: AccountGuid, assetBalance: BigInteger, percent: Int, takerFeeRate: BigInteger): Pair<BigInteger, BigInteger?> {
        val quoteAssetsRequired = quoteAssetsRequired(account)
        val quoteAssetLimit = (BigInteger.ZERO.max(assetBalance - quoteAssetsRequired) * percent.toBigInteger()) / Percentage.MAX_VALUE.toBigInteger()
        val quoteAssetLimitAdjustedForFee = (quoteAssetLimit * FeeRate.MAX_VALUE.toBigInteger()) / (FeeRate.MAX_VALUE.toBigInteger() + takerFeeRate)
        return Pair(quantityForMarketBuy(quoteAssetLimitAdjustedForFee), if (quoteAssetsRequired == BigInteger.ZERO && percent == 100) assetBalance else null)
    }

    // returns baseAsset and quoteAsset reserved by order
    fun assetsReservedForOrder(levelOrder: LevelOrder): Pair<BigInteger, BigInteger> {
        val level = levelOrder.level
        return if (level.side == BookSide.Buy) {
            BigInteger.ZERO to notionalPlusFee(levelOrder.quantity, level.price, baseDecimals, quoteDecimals, levelOrder.feeRate)
        } else {
            levelOrder.quantity to BigInteger.ZERO
        }
    }

    private fun validateOrderForAccount(account: Long, orderGuid: Long): OrderChangeRejected.Reason {
        return ordersByGuid[orderGuid.toOrderGuid()]?.let { order ->
            if (account == order.account.value) {
                OrderChangeRejected.Reason.None
            } else {
                OrderChangeRejected.Reason.NotForAccount
            }
        } ?: OrderChangeRejected.Reason.DoesNotExist
    }

    // equals and hashCode are overridden because of levels are stored in array
    // see https://kotlinlang.org/docs/arrays.html#compare-arrays
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Market

        if (maxOrdersPerLevel != other.maxOrdersPerLevel) return false
        if (tickSize != other.tickSize) return false
        if (baseDecimals != other.baseDecimals) return false
        if (quoteDecimals != other.quoteDecimals) return false
        if (levels != other.levels) return false
        if (buyOrdersByAccount != other.buyOrdersByAccount) return false
        if (sellOrdersByAccount != other.sellOrdersByAccount) return false
        if (maxOfferIx != other.maxOfferIx) return false
        if (bestOfferIx != other.bestOfferIx) return false
        if (bestBidIx != other.bestBidIx) return false
        if (minBidIx != other.minBidIx) return false
        return ordersByGuid == other.ordersByGuid
    }

    override fun hashCode(): Int {
        var result = maxOrdersPerLevel
        result = 31 * result + tickSize.hashCode()
        result = 31 * result + baseDecimals
        result = 31 * result + quoteDecimals
        result = 31 * result + maxOfferIx
        result = 31 * result + bestOfferIx.hashCode()
        result = 31 * result + bestBidIx.hashCode()
        result = 31 * result + minBidIx
        result = 31 * result + levels.hashCode()
        result = 31 * result + buyOrdersByAccount.hashCode()
        result = 31 * result + sellOrdersByAccount.hashCode()
        result = 31 * result + ordersByGuid.hashCode()
        return result
    }

    fun toCheckpoint(): MarketCheckpoint {
        return marketCheckpoint {
            this.id = this@Market.id.value
            this.tickSize = this@Market.tickSize.toDecimalValue()
            this.maxOrdersPerLevel = this@Market.maxOrdersPerLevel
            this.baseDecimals = this@Market.baseDecimals
            this.quoteDecimals = this@Market.quoteDecimals
            this.minBidIx = this@Market.minBidIx
            this.bestBidIx = this@Market.bestBidIx
            this.bestOfferIx = this@Market.bestOfferIx
            this.maxOfferIx = this@Market.maxOfferIx
            this.minFee = this@Market.minFee.toIntegerValue()
            this@Market.levels.traverse { level ->
                this.levels.add(level.toCheckpoint())
            }
        }
    }

    private fun logState() {
        logger.debug { "maxOrdersPerLevel = ${this.maxOrdersPerLevel}" }
        logger.debug { "baseDecimals = ${this.baseDecimals}" }
        logger.debug { "minBidIx = ${this.minBidIx}" }
        logger.debug { "maxBidIx = $bestBidIx " }
        logger.debug { "minOfferIx = $bestOfferIx " }
        logger.debug { "maxOfferIx = ${this.maxOfferIx}" }
        logger.debug { "bestBidIx = ${this.bestBidIx}" }
        logger.debug { "bestOfferIx = ${this.bestOfferIx}" }
        logger.debug { "minFee = ${this.minFee}" }
        levels.traverse { level ->
            logger.debug { "   levelIx = ${level.ix} side = ${level.side}  maxOrderCount = ${level.maxOrderCount} totalQuantity = ${level.totalQuantity} " }
        }
    }

    companion object {
        fun fromCheckpoint(checkpoint: MarketCheckpoint): Market {
            val tickSize = checkpoint.tickSize.toBigDecimal()
            return Market(
                id = checkpoint.id.toMarketId(),
                tickSize = tickSize,
                maxOrdersPerLevel = checkpoint.maxOrdersPerLevel,
                baseDecimals = checkpoint.baseDecimals,
                quoteDecimals = checkpoint.quoteDecimals,
                minFee = if (checkpoint.hasMinFee()) checkpoint.minFee.toBigInteger() else BigInteger.ZERO,
            ).apply {
                maxOfferIx = checkpoint.maxOfferIx
                bestOfferIx = checkpoint.bestOfferIx
                bestBidIx = checkpoint.bestBidIx
                minBidIx = checkpoint.minBidIx

                checkpoint.levelsList.forEach { levelCheckpoint ->
                    val level = OrderBookLevel(
                        ix = levelCheckpoint.levelIx,
                        side = BookSide.Buy,
                        price = levelCheckpoint.price.toBigDecimal(),
                        maxOrderCount = checkpoint.maxOrdersPerLevel,
                    )
                    level.fromCheckpoint(levelCheckpoint)

                    this.levels.add(level)
                }

                levels.traverse { level ->
                    // inflate order cache respecting level's circular buffer
                    var currentIndex = level.orderHead
                    while (currentIndex != level.orderTail) {
                        val order = level.orders[currentIndex]
                        this.ordersByGuid[order.guid] = order

                        when (level.side) {
                            BookSide.Buy -> buyOrdersByAccount
                            BookSide.Sell -> sellOrdersByAccount
                        }.apply {
                            getOrPut(order.account) { CopyOnWriteArrayList<LevelOrder>() }.add(order)
                        }

                        currentIndex = (currentIndex + 1) % level.maxOrderCount
                    }
                }
            }
        }
    }
}
