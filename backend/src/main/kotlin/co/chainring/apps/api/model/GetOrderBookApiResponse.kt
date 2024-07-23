package co.chainring.apps.api.model
import co.chainring.apps.api.model.websocket.LastTrade
import co.chainring.apps.api.model.websocket.OrderBook
import co.chainring.core.model.db.MarketId
import kotlinx.serialization.Serializable

@Serializable
data class GetOrderBookApiResponse(
    val marketId: MarketId,
    val buy: List<Entry>,
    val sell: List<Entry>,
    val last: LastTrade,
) {
    @Serializable
    data class Entry(
        val price: String,
        val size: BigDecimalJson,
    )

    constructor(orderBook: OrderBook) : this(
        orderBook.marketId,
        buy = orderBook.buy.map { Entry(it.price, it.size) },
        sell = orderBook.sell.map { Entry(it.price, it.size) },
        last = orderBook.last,
    )
}
