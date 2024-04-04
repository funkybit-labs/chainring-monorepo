package co.chainring.sequencer.core

import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.OrderDisposition
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
)

class OrderBookLevel(var side: BookSide, val price: BigDecimal, val maxOrderCount: Int) {
    val orders = Array(maxOrderCount) { _ -> LevelOrder(0L.toOrderGuid(), 0L.toWalletAddress(), BigInteger.ZERO) }
    var totalQuantity = BigInteger.ZERO
    var orderHead = 0
    var orderTail = 0

    fun addOrder(order: Order): Pair<OrderDisposition, Int> {
        val nextTail = (orderTail + 1) % maxOrderCount
        return if (nextTail == orderHead) {
            OrderDisposition.Rejected to 0
        } else {
            val amount = order.amount.toBigInteger()
            orders[orderTail].let {
                it.guid = order.guid.toOrderGuid()
                it.wallet = order.wallet.toWalletAddress()
                it.quantity = amount
            }
            totalQuantity += amount
            val orderIx = orderTail
            orderTail = nextTail
            OrderDisposition.Accepted to orderIx
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
                        counterGuid = curOrder.guid,
                        counterWallet = curOrder.wallet,
                        amount = curOrder.quantity,
                        price = this.price,
                        counterOrderExhausted = true,
                        counterOrderIx = (ix - orderHead) % maxOrderCount,
                    ),
                )
                totalQuantity -= curOrder.quantity
                remainingAmount -= curOrder.quantity
                ix = (ix + 1) % maxOrderCount
            } else {
                executions.add(
                    Execution(
                        counterGuid = curOrder.guid,
                        counterWallet = curOrder.wallet,
                        amount = remainingAmount,
                        price = this.price,
                        counterOrderExhausted = false,
                        counterOrderIx = (ix - orderHead) % maxOrderCount,
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
}

data class Execution(
    val counterGuid: OrderGuid,
    val counterWallet: WalletAddress,
    val amount: BigInteger,
    val price: BigDecimal,
    val counterOrderExhausted: Boolean,
    val counterOrderIx: Int,
)

data class AddOrderResult(
    val disposition: OrderDisposition,
    val executions: List<Execution>,
)

data class OrderIndex(
    val levelIx: Int,
    val orderIx: Int,
)

// market price must be exactly halfway between two ticks
class OrderBook(val maxLevelCount: Int, maxOrderCount: Int, val tickSize: BigDecimal, var marketPrice: BigDecimal, val baseDecimals: Int, val quoteDecimals: Int) {
    private val halfTick = tickSize.setScale(tickSize.scale() + 1) / BigDecimal.valueOf(2)
    private val marketIx = min(maxLevelCount / 2, (marketPrice - halfTick).divideToIntegralValue(tickSize).toInt())
    private val levels = Array(maxLevelCount) { n ->
        if (n < marketIx) {
            OrderBookLevel(
                BookSide.Buy,
                marketPrice.minus(tickSize.multiply((marketIx - n - 0.5).toBigDecimal())),
                maxOrderCount,
            )
        } else {
            OrderBookLevel(
                BookSide.Sell,
                marketPrice.plus(tickSize.multiply((n - marketIx + 0.5).toBigDecimal())),
                maxOrderCount,
            )
        }
    }
    private val buyOrdersByWallet = mutableMapOf<WalletAddress, CopyOnWriteArrayList<OrderIndex>>()
    private val sellOrdersByWallet = mutableMapOf<WalletAddress, CopyOnWriteArrayList<OrderIndex>>()
    private var maxOfferIx = -1
    private var minBidIx = -1

    fun baseAssetsRequired(wallet: WalletAddress): BigInteger = sellOrdersByWallet[wallet]?.map {
        val level = levels[it.levelIx]
        level.orders[it.orderIx].quantity
    }?.reduce(::sumBigIntegers) ?: BigInteger.ZERO

    fun quoteAssetsRequired(wallet: WalletAddress): BigInteger = buyOrdersByWallet[wallet]?.map {
        val level = levels[it.levelIx]
        val order = level.orders[it.orderIx]
        notional(order.quantity, level.price, baseDecimals, quoteDecimals)
    }?.reduce(::sumBigIntegers) ?: BigInteger.ZERO

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
                    (if (order.type == Order.Type.MarketBuy) sellOrdersByWallet else buyOrdersByWallet)[it.counterWallet]?.remove(
                        OrderIndex(
                            (it.price - levels[0].price).divideToIntegralValue(tickSize).toInt(),
                            it.counterOrderIx,
                        ),
                    )
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
                    val(disposition, orderIx) = levels[levelIx].addOrder(order)
                    if (disposition == OrderDisposition.Accepted) {
                        sellOrdersByWallet.getOrPut(order.wallet.toWalletAddress()) { CopyOnWriteArrayList() }.add(
                            OrderIndex(levelIx, orderIx),
                        )
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
                    val(disposition, orderIx) = levels[levelIx].addOrder(order)
                    if (disposition == OrderDisposition.Accepted) {
                        buyOrdersByWallet.getOrPut(order.wallet.toWalletAddress()) { CopyOnWriteArrayList() }.add(
                            OrderIndex(levelIx, orderIx),
                        )
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
}
