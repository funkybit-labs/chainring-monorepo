package co.chainring.sequencer.core

import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.OrderDisposition
import co.chainring.sequencer.proto.order
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.min

enum class BookSide {
    Buy,
    Sell,
}

val noExecutions = listOf<Execution>()

data class OrderBookLevelFill(
    val remainingAmount: BigInteger,
    val executions: List<Execution>,
)

data class LevelOrder(
    var guid: OrderGuid,
    var wallet: WalletAddress,
    var quantity: BigInteger,
    var levelIx: Int,
) {
    fun update(order: Order) {
        this.guid = order.guid.toOrderGuid()
        this.wallet = order.wallet.toWalletAddress()
        this.quantity = order.amount.toBigInteger()
    }

    fun reset() {
        this.guid = OrderGuid.none
        this.wallet = WalletAddress.none
        this.quantity = BigInteger.ZERO
    }
}

class OrderBookLevel(levelIx: Int, var side: BookSide, val price: BigDecimal, val maxOrderCount: Int) {
    val orders = Array(maxOrderCount) { _ -> LevelOrder(0L.toOrderGuid(), 0L.toWalletAddress(), BigInteger.ZERO, levelIx) }
    var totalQuantity = BigInteger.ZERO
    var orderHead = 0
    var orderTail = 0

    fun addOrder(order: Order): Pair<OrderDisposition, LevelOrder?> {
        val nextTail = (orderTail + 1) % maxOrderCount
        return if (nextTail == orderHead) {
            OrderDisposition.Rejected to null
        } else {
            val levelOrder = orders[orderTail]
            levelOrder.update(order)
            totalQuantity += levelOrder.quantity
            orderTail = nextTail
            OrderDisposition.Accepted to levelOrder
        }
    }

    fun fillOrder(requestedAmount: BigInteger): OrderBookLevelFill {
        var ix = orderHead
        val executions = mutableListOf<Execution>()
        var remainingAmount = requestedAmount
        while (ix != orderTail && remainingAmount > BigInteger.ZERO) {
            val curOrder = orders[ix]
            if (remainingAmount >= curOrder.quantity) {
                executions.add(
                    Execution(
                        counterOrder = curOrder,
                        amount = curOrder.quantity,
                        price = this.price,
                        counterOrderExhausted = true,
                    ),
                )
                totalQuantity -= curOrder.quantity
                remainingAmount -= curOrder.quantity
                ix = (ix + 1) % maxOrderCount
            } else {
                executions.add(
                    Execution(
                        counterOrder = curOrder,
                        amount = remainingAmount,
                        price = this.price,
                        counterOrderExhausted = false,
                    ),
                )
                totalQuantity -= remainingAmount
                curOrder.quantity -= remainingAmount
                remainingAmount = BigInteger.ZERO
            }
        }
        // remove consumed orders
        orderHead = ix

        return OrderBookLevelFill(
            remainingAmount,
            executions,
        )
    }

    fun removeLevelOrder(levelOrder: LevelOrder) {
        val orderIx = orders.indexOf(levelOrder)
        totalQuantity -= levelOrder.quantity
        levelOrder.reset()
        if (orderIx < orderHead) {
            // copy from after orderIx to orderTail, and decrement orderTail
            if (orderIx < orderTail) {
                System.arraycopy(orders, orderIx + 1, orders, orderIx, orderTail - orderIx)
            }
            orderTail = (orderTail - 1) % maxOrderCount
        } else {
            if (orderIx > orderHead) {
                System.arraycopy(orders, orderHead, orders, orderHead + 1, orderIx - orderHead)
            }
            orderHead = (orderHead + 1) % maxOrderCount
        }
    }
}

data class Execution(
    val counterOrder: LevelOrder,
    val amount: BigInteger,
    val price: BigDecimal,
    val counterOrderExhausted: Boolean,
)

data class AddOrderResult(
    val disposition: OrderDisposition,
    val executions: List<Execution>,
)

// market price must be exactly halfway between two ticks
class OrderBook(val maxLevelCount: Int, maxOrderCount: Int, val tickSize: BigDecimal, var marketPrice: BigDecimal, val baseDecimals: Int, val quoteDecimals: Int) {
    private val halfTick = tickSize.setScale(tickSize.scale() + 1) / BigDecimal.valueOf(2)
    private val marketIx = min(maxLevelCount / 2, (marketPrice - halfTick).divideToIntegralValue(tickSize).toInt())
    val levels = Array(maxLevelCount) { n ->
        if (n < marketIx) {
            OrderBookLevel(
                n,
                BookSide.Buy,
                marketPrice.minus(tickSize.multiply((marketIx - n - 0.5).toBigDecimal())),
                maxOrderCount,
            )
        } else {
            OrderBookLevel(
                n,
                BookSide.Sell,
                marketPrice.plus(tickSize.multiply((n - marketIx + 0.5).toBigDecimal())),
                maxOrderCount,
            )
        }
    }

    // TODO - change these mutable maps to a HashMap that pre-allocates
    private val buyOrdersByWallet = mutableMapOf<WalletAddress, CopyOnWriteArrayList<LevelOrder>>()
    private val sellOrdersByWallet = mutableMapOf<WalletAddress, CopyOnWriteArrayList<LevelOrder>>()
    private var maxOfferIx = -1
    private var minBidIx = -1
    val ordersByGuid = mutableMapOf<OrderGuid, LevelOrder>()

    fun baseAssetsRequired(wallet: WalletAddress): BigInteger = sellOrdersByWallet[wallet]?.map { it.quantity }?.reduceOrNull(::sumBigIntegers) ?: BigInteger.ZERO

    fun quoteAssetsRequired(wallet: WalletAddress): BigInteger = buyOrdersByWallet[wallet]?.map {
        notional(it.quantity, levels[it.levelIx].price, baseDecimals, quoteDecimals)
    }?.reduceOrNull(::sumBigIntegers) ?: BigInteger.ZERO

    private fun handleMarketOrder(order: Order): AddOrderResult {
        val originalAmount = order.amount.toBigInteger()
        var remainingAmount = originalAmount
        val executions = mutableListOf<Execution>()
        val maxBidIx = (marketPrice.minus(halfTick) - levels[0].price).divideToIntegralValue(tickSize).toInt()
        val minOfferIx = maxBidIx + 1
        var index = if (order.type == Order.Type.MarketBuy) {
            minOfferIx
        } else {
            maxBidIx
        }

        while (index >= 0 && index <= levels.size) {
            val orderBookLevelFill = levels[index].fillOrder(remainingAmount)
            remainingAmount = orderBookLevelFill.remainingAmount
            executions.addAll(orderBookLevelFill.executions)
            if (remainingAmount == BigInteger.ZERO) {
                break
            }
            if (order.type == Order.Type.MarketBuy) {
                index += 1
                if (index > maxOfferIx) {
                    break
                }
            } else {
                index -= 1
                if (index < minBidIx) {
                    break
                }
            }
        }
        return if (remainingAmount < originalAmount) {
            // adjust market price to midpoint
            marketPrice = if (order.type == Order.Type.MarketBuy) {
                ((levels[min(index, maxLevelCount - 1)].price) + levels[maxBidIx].price).setScale(marketPrice.scale()) / BigDecimal.valueOf(2)
            } else {
                ((levels[max(index, 0)].price) + levels[minOfferIx].price).setScale(marketPrice.scale()) / BigDecimal.valueOf(2)
            }

            // remove from buy/sell
            executions.forEach {
                if (it.counterOrderExhausted) {
                    (if (order.type == Order.Type.MarketBuy) sellOrdersByWallet else buyOrdersByWallet)[it.counterOrder.wallet]?.remove(it.counterOrder)
                    ordersByGuid.remove(it.counterOrder.guid)
                }
            }

            if (remainingAmount > BigInteger.ZERO) {
                AddOrderResult(OrderDisposition.PartiallyFilled, executions)
            } else {
                AddOrderResult(OrderDisposition.Filled, executions)
            }
        } else {
            AddOrderResult(OrderDisposition.Rejected, noExecutions)
        }
    }

    fun removeOrder(guid: OrderGuid): Boolean {
        return ordersByGuid[guid]?.let { levelOrder ->
            val level = levels[levelOrder.levelIx]
            if (level.side == BookSide.Buy) {
                buyOrdersByWallet[levelOrder.wallet]?.remove(levelOrder)
            } else {
                sellOrdersByWallet[levelOrder.wallet]?.remove(levelOrder)
            }
            level.removeLevelOrder(levelOrder)
            ordersByGuid.remove(guid)
            true
        } ?: false
    }

    fun addOrder(order: Order): AddOrderResult {
        return if (order.type == Order.Type.LimitSell) {
            val orderPrice = order.price.toBigDecimal()

            if (orderPrice <= marketPrice) {
                AddOrderResult(OrderDisposition.CrossesMarket, noExecutions)
            } else {
                val levelIx = (orderPrice - levels[0].price).divideToIntegralValue(tickSize).toInt()
                if (levelIx > levels.lastIndex) {
                    AddOrderResult(OrderDisposition.Rejected, noExecutions)
                } else {
                    if (levelIx > maxOfferIx) {
                        maxOfferIx = levelIx
                    }
                    val(disposition, levelOrder) = levels[levelIx].addOrder(order)
                    if (disposition == OrderDisposition.Accepted) {
                        sellOrdersByWallet.getOrPut(levelOrder!!.wallet) { CopyOnWriteArrayList() }.add(levelOrder)
                        ordersByGuid[levelOrder.guid] = levelOrder
                    }
                    AddOrderResult(disposition, noExecutions)
                }
            }
        } else if (order.type == Order.Type.LimitBuy) {
            val orderPrice = order.price.toBigDecimal()

            if (orderPrice >= marketPrice) {
                AddOrderResult(OrderDisposition.CrossesMarket, noExecutions)
            } else {
                val levelIx = (orderPrice - levels[0].price).divideToIntegralValue(tickSize).toInt()
                if (levelIx < 0) {
                    AddOrderResult(OrderDisposition.Rejected, noExecutions)
                } else {
                    if (levelIx < minBidIx) {
                        minBidIx = levelIx
                    }
                    val(disposition, levelOrder) = levels[levelIx].addOrder(order)
                    if (disposition == OrderDisposition.Accepted) {
                        buyOrdersByWallet.getOrPut(levelOrder!!.wallet) { CopyOnWriteArrayList() }.add(levelOrder)
                        ordersByGuid[levelOrder.guid] = levelOrder
                    }
                    AddOrderResult(disposition, noExecutions)
                }
            }
        } else if (order.type == Order.Type.MarketBuy) {
            handleMarketOrder(order)
        } else if (order.type == Order.Type.MarketSell) {
            handleMarketOrder(order)
        } else {
            AddOrderResult(OrderDisposition.Rejected, noExecutions)
        }
    }

    // calculate how much liquidity is available for a market buy, and what the final clearing price would be
    fun clearingPriceAndQuantityForMarketBuy(amount: BigInteger): Pair<BigDecimal, BigInteger> {
        var index = (marketPrice.plus(halfTick) - levels[0].price).divideToIntegralValue(tickSize).toInt()

        var remainingAmount = amount
        var totalPriceUnits = BigDecimal.ZERO

        while (index <= levels.size) {
            val quantityAtLevel = levels[index].totalQuantity.min(remainingAmount)
            totalPriceUnits += quantityAtLevel.toBigDecimal() * levels[index].price
            remainingAmount -= quantityAtLevel
            if (remainingAmount == BigInteger.ZERO) {
                break
            }
            index += 1
            if (index > maxOfferIx) {
                break
            }
        }
        val availableQuantity = amount - remainingAmount
        return Pair(totalPriceUnits / availableQuantity.toBigDecimal(), availableQuantity)
    }

    // returns baseAsset and quoteAsset reserved by order
    fun assetsReservedForOrder(levelOrder: LevelOrder): Pair<BigInteger, BigInteger> {
        val level = levels[levelOrder.levelIx]
        return if (level.side == BookSide.Buy) {
            BigInteger.ZERO to notional(levelOrder.quantity, level.price, baseDecimals, quoteDecimals)
        } else {
            levelOrder.quantity to BigInteger.ZERO
        }
    }

    // this will only change an order's price and quantity
    // if the price change would alter the book side, no change is made
    fun changeOrder(orderChange: Order): OrderDisposition? {
        return ordersByGuid[orderChange.guid.toOrderGuid()]?.let { order ->
            val level = levels[order.levelIx]
            val newPrice = orderChange.price.toBigDecimal()
            if (newPrice == level.price) {
                val newQuantity = orderChange.amount.toBigInteger()
                level.totalQuantity += (newQuantity - order.quantity)
                order.quantity = newQuantity
                OrderDisposition.Accepted
            } else if (level.side == BookSide.Buy && newPrice < marketPrice || level.side == BookSide.Sell && newPrice > marketPrice) {
                val wallet = order.wallet.value
                removeOrder(order.guid)
                addOrder(
                    order {
                        this.guid = orderChange.guid
                        this.type = if (level.side == BookSide.Buy) Order.Type.LimitBuy else Order.Type.LimitSell
                        this.wallet = wallet
                        this.amount = orderChange.amount
                        this.price = orderChange.price
                    },
                )
                OrderDisposition.Accepted
            } else {
                null
            }
        }
    }
}
