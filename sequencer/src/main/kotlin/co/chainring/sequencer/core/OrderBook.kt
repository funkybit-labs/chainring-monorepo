package co.chainring.sequencer.core

import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.OrderDisposition
import java.math.BigDecimal
import java.math.BigInteger
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

@JvmInline
value class OrderGuid(val value: Long) {
    override fun toString(): String = value.toString()
}

fun Long.toOrderGuid() = OrderGuid(this)

@JvmInline
value class WalletAddress(val value: Long) {
    override fun toString(): String = value.toString()
}

fun Long.toWalletAddress() = WalletAddress(this)

data class LevelOrder(
    var guid: OrderGuid,
    var wallet: WalletAddress,
    var quantity: BigInteger,
)

class OrderBookLevel(var side: BookSide, val price: BigDecimal, val maxOrderCount: Int) {
    private val orders = Array(maxOrderCount) { _ -> LevelOrder(0L.toOrderGuid(), 0L.toWalletAddress(), BigInteger.ZERO) }
    var orderHead = 0
    var orderTail = 0

    fun addOrder(order: Order): OrderDisposition {
        val nextTail = (orderTail + 1) % maxOrderCount
        return if (nextTail == orderHead) {
            OrderDisposition.Rejected
        } else {
            orders[orderTail].let {
                it.guid = order.guid.toOrderGuid()
                it.wallet = order.wallet.toWalletAddress()
                it.quantity = order.amount.toBigInteger()
            }
            orderTail = nextTail
            OrderDisposition.Accepted
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
                        amount = curOrder.quantity,
                        price = this.price,
                    ),
                )
                remainingAmount -= curOrder.quantity
                ix = (ix + 1) % maxOrderCount
            } else {
                executions.add(
                    Execution(
                        counterGuid = curOrder.guid,
                        amount = remainingAmount,
                        price = this.price,
                    ),
                )
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
    val amount: BigInteger,
    val price: BigDecimal,
)

data class OrderBookAdd(
    val disposition: OrderDisposition,
    val executions: List<Execution>,
)

// market price must be exactly halfway between two ticks
class OrderBook(val maxLevelCount: Int, maxOrderCount: Int, val tickSize: BigDecimal, var marketPrice: BigDecimal) {
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
    private var maxOfferIx = -1
    private var minBidIx = -1

    private fun handleMarketOrder(order: Order): OrderBookAdd {
        val originalAmount = order.amount.toBigInteger()
        var remainingAmount = originalAmount
        val executions = mutableListOf<Execution>()
        val maxBidIx = (marketPrice.minus(halfTick) - levels[0].price).divideToIntegralValue(tickSize).toInt()
        val minOfferIx = (marketPrice.plus(halfTick) - levels[0].price).divideToIntegralValue(tickSize).toInt()
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

            if (remainingAmount > BigInteger.ZERO) {
                OrderBookAdd(OrderDisposition.PartiallyFilled, executions)
            } else {
                OrderBookAdd(OrderDisposition.Filled, executions)
            }
        } else {
            OrderBookAdd(OrderDisposition.Rejected, noExecutions)
        }
    }

    fun addOrder(order: Order): OrderBookAdd {
        return if (order.type == Order.Type.LimitSell) {
            val orderPrice = order.price.toBigDecimal()

            if (orderPrice <= marketPrice) {
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
        } else if (order.type == Order.Type.LimitBuy) {
            val orderPrice = order.price.toBigDecimal()

            if (orderPrice >= marketPrice) {
                OrderBookAdd(OrderDisposition.CrossesMarket, noExecutions)
            } else {
                val levelIx = (orderPrice - levels[0].price).divideToIntegralValue(tickSize).toInt()
                if (levelIx < 0) {
                    OrderBookAdd(OrderDisposition.Rejected, noExecutions)
                } else {
                    if (levelIx < minBidIx) {
                        minBidIx = levelIx
                    }
                    OrderBookAdd(levels[levelIx].addOrder(order), noExecutions)
                }
            }
        } else if (order.type == Order.Type.MarketBuy) {
            handleMarketOrder(order)
        } else if (order.type == Order.Type.MarketSell) {
            handleMarketOrder(order)
        } else {
            OrderBookAdd(OrderDisposition.Rejected, noExecutions)
        }
    }
}
