package co.chainring.sequencer.core

import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.OrderDisposition
import java.math.BigDecimal
import java.math.BigInteger

enum class BookSide {
    Buy,
    Sell,
}

val noExecutions = listOf<Execution>()

data class OrderBookLevelFill(
    val remainingAmount: BigInteger,
    val executions: List<Execution>,
)

class OrderBookLevel(var side: BookSide, val price: BigDecimal, val maxOrderCount: Int) {
    private val orderGuids = LongArray(maxOrderCount)
    private val orderWallets = LongArray(maxOrderCount)
    private val orderQuantities = Array<BigInteger>(maxOrderCount) { _ -> BigInteger.ZERO }
    var orderCount = 0

    fun addOrder(order: Order): OrderDisposition {
        return if (orderCount == maxOrderCount) {
            OrderDisposition.Rejected
        } else {
            orderGuids[orderCount] = order.guid
            orderWallets[orderCount] = order.wallet
            orderQuantities[orderCount] = order.amount.toBigInteger()
            orderCount += 1
            OrderDisposition.Accepted
        }
    }

    fun fillOrder(requestedAmount: BigInteger): OrderBookLevelFill {
        var ix = 0
        val executions = mutableListOf<Execution>()
        var remainingAmount = requestedAmount
        while (ix < orderCount && remainingAmount > BigInteger.ZERO) {
            if (remainingAmount >= orderQuantities[ix]) {
                executions.add(
                    Execution(
                        counterGuid = orderGuids[ix],
                        amount = orderQuantities[ix],
                        price = this.price,
                    ),
                )
                remainingAmount -= orderQuantities[ix]
                ix += 1
            } else {
                executions.add(
                    Execution(
                        counterGuid = orderGuids[ix],
                        amount = remainingAmount,
                        price = this.price,
                    ),
                )
                orderQuantities[ix] -= remainingAmount
                remainingAmount = BigInteger.ZERO
            }
        }
        // remove consumed orders
        if (ix > 0) {
            System.arraycopy(orderWallets, ix, orderWallets, 0, orderCount - ix)
            orderCount -= ix
        }

        return OrderBookLevelFill(
            remainingAmount,
            executions,
        )
    }
}

data class Execution(
    val counterGuid: Long,
    val amount: BigInteger,
    val price: BigDecimal,
)

data class OrderBookAdd(
    val disposition: OrderDisposition,
    val executions: List<Execution>,
)

// starting price must be exactly halfway between two ticks
class OrderBook(maxLevelCount: Int, maxOrderCount: Int, val tickSize: BigDecimal, startingPrice: BigDecimal) {
    private val halfTick = tickSize.setScale(tickSize.scale() + 1) / BigDecimal.valueOf(2)
    private val marketIx = maxLevelCount / 2
    private val levels = Array(maxLevelCount) { n ->
        if (n < marketIx) {
            OrderBookLevel(
                BookSide.Buy,
                startingPrice.minus(tickSize.multiply((marketIx - n - 0.5).toBigDecimal())),
                maxOrderCount,
            )
        } else {
            OrderBookLevel(
                BookSide.Sell,
                startingPrice.plus(tickSize.multiply((n - marketIx + 0.5).toBigDecimal())),
                maxOrderCount,
            )
        }
    }
    private var maxOfferIx = -1
    private var minBidIx = -1

    private fun handleMarketOrder(order: Order, lastPrice: BigDecimal): OrderBookAdd {
        val originalAmount = order.amount.toBigInteger()
        var remainingAmount = originalAmount
        val executions = mutableListOf<Execution>()
        var index = if (order.orderType == Order.OrderType.MarketBuy) {
            (lastPrice.plus(halfTick) - levels[0].price).divideToIntegralValue(tickSize).toInt()
        } else {
            (lastPrice.minus(halfTick) - levels[0].price).divideToIntegralValue(tickSize).toInt()
        }

        while (index >= 0 && index <= levels.size) {
            val orderBookLevelFill = levels[index].fillOrder(remainingAmount)
            remainingAmount = orderBookLevelFill.remainingAmount
            executions.addAll(orderBookLevelFill.executions)
            if (remainingAmount == BigInteger.ZERO) {
                break
            }
            if (order.orderType == Order.OrderType.MarketBuy) {
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
            if (remainingAmount > BigInteger.ZERO) {
                OrderBookAdd(OrderDisposition.PartiallyFilled, executions)
            } else {
                OrderBookAdd(OrderDisposition.Filled, executions)
            }
        } else {
            OrderBookAdd(OrderDisposition.Rejected, noExecutions)
        }
    }

    fun addOrder(order: Order, lastPrice: BigDecimal): OrderBookAdd {
        return if (order.orderType == Order.OrderType.LimitSell) {
            val orderPrice = order.price.toBigDecimal()

            if (orderPrice <= lastPrice) {
                OrderBookAdd(OrderDisposition.CrossesMarket, noExecutions)
            } else {
                val levelIx = (orderPrice - levels[0].price).divideToIntegralValue(tickSize).toInt()
                if (levelIx > levels.lastIndex) {
                    OrderBookAdd(OrderDisposition.Rejected, noExecutions)
                } else {
                    if (levelIx > maxOfferIx) {
                        maxOfferIx = levelIx
                    }
                    OrderBookAdd(levels[levelIx].addOrder(order), noExecutions)
                }
            }
        } else if (order.orderType == Order.OrderType.LimitBuy) {
            val orderPrice = order.price.toBigDecimal()

            if (orderPrice >= lastPrice) {
                OrderBookAdd(OrderDisposition.CrossesMarket, noExecutions)
            } else {
                val levelIx = (levels[levels.lastIndex].price - orderPrice).divideToIntegralValue(tickSize).toInt() - 1
                if (levelIx < 0) {
                    OrderBookAdd(OrderDisposition.Rejected, noExecutions)
                } else {
                    if (levelIx < minBidIx) {
                        minBidIx = levelIx
                    }
                    OrderBookAdd(levels[levelIx].addOrder(order), noExecutions)
                }
            }
        } else if (order.orderType == Order.OrderType.MarketBuy) {
            handleMarketOrder(order, lastPrice)
        } else if (order.orderType == Order.OrderType.MarketSell) {
            handleMarketOrder(order, lastPrice)
        } else {
            OrderBookAdd(OrderDisposition.Rejected, noExecutions)
        }
    }
}
