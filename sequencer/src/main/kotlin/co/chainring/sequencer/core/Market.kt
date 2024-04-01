package co.chainring.sequencer.core

import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.Order.OrderType
import co.chainring.sequencer.proto.OrderChanged
import co.chainring.sequencer.proto.TradeCreated
import co.chainring.sequencer.proto.orderChanged
import co.chainring.sequencer.proto.tradeCreated
import java.math.BigDecimal

data class MarketAddOrders(
    val ordersChanged: List<OrderChanged>,
    val trades: List<TradeCreated>,
)

class Market(
    val marketId: String,
    val tickSize: BigDecimal,
    var lastPrice: BigDecimal,
    maxLevels: Int,
    maxOrdersPerLevel: Int,
) {
    fun addOrders(ordersToAddList: List<Order>): MarketAddOrders {
        val ordersChanged = mutableListOf<OrderChanged>()
        val trades = mutableListOf<TradeCreated>()
        ordersToAddList.forEach { order ->
            val orderResult = orderBook.addOrder(order, lastPrice)
            ordersChanged.add(
                orderChanged {
                    this.guid = order.guid
                    this.disposition = orderResult.disposition
                },
            )
            trades.addAll(
                orderResult.executions.map { execution ->
                    tradeCreated {
                        if (order.orderType == OrderType.MarketBuy) {
                            buyGuid = order.guid
                            sellGuid = execution.counterGuid
                        } else {
                            buyGuid = execution.counterGuid
                            sellGuid = order.guid
                        }
                        amount = execution.amount.toIntegerValue()
                        price = execution.price.toDecimalValue()
                    }
                },
            )
            if (orderResult.executions.isNotEmpty()) {
                lastPrice = orderResult.executions.last().price
            }
        }
        return MarketAddOrders(ordersChanged, trades)
    }

    val orderBook = OrderBook(maxLevels, maxOrdersPerLevel, tickSize, lastPrice)
}
