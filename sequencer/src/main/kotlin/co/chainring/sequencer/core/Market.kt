package co.chainring.sequencer.core

import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.OrderChanged
import co.chainring.sequencer.proto.TradeCreated
import co.chainring.sequencer.proto.orderChanged
import co.chainring.sequencer.proto.tradeCreated
import java.math.BigDecimal

@JvmInline
value class MarketId(val value: String) {
    override fun toString(): String = value
}

fun String.toMarketId() = MarketId(this)

class Market(
    val id: MarketId,
    tickSize: BigDecimal,
    marketPrice: BigDecimal,
    maxLevels: Int,
    maxOrdersPerLevel: Int,
) {
    data class AddOrdersResult(
        val ordersChanged: List<OrderChanged>,
        val createdTrades: List<TradeCreated>,
    )

    fun addOrders(ordersToAddList: List<Order>): AddOrdersResult {
        val ordersChanged = mutableListOf<OrderChanged>()
        val createdTrades = mutableListOf<TradeCreated>()
        ordersToAddList.forEach { order ->
            val orderResult = orderBook.addOrder(order)
            ordersChanged.add(
                orderChanged {
                    this.guid = order.guid
                    this.disposition = orderResult.disposition
                },
            )
            createdTrades.addAll(
                orderResult.executions.map { execution ->
                    tradeCreated {
                        if (order.type == Order.Type.MarketBuy) {
                            buyGuid = order.guid
                            sellGuid = execution.counterGuid.value
                        } else {
                            buyGuid = execution.counterGuid.value
                            sellGuid = order.guid
                        }
                        amount = execution.amount.toIntegerValue()
                        price = execution.price.toDecimalValue()
                    }
                },
            )
        }
        return AddOrdersResult(ordersChanged, createdTrades)
    }

    val orderBook = OrderBook(maxLevels, maxOrdersPerLevel, tickSize, marketPrice)
}
