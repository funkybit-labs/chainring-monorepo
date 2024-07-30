package co.chainring.apps.api.model
import co.chainring.apps.api.model.websocket.OrderBook
import co.chainring.core.model.db.MarketEntity
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderBookSnapshot
import kotlinx.serialization.Serializable

@Serializable
data class GetOrderBookApiResponse(
    val marketId: MarketId,
    val bids: List<OrderBook.Entry>,
    val asks: List<OrderBook.Entry>,
    val last: OrderBook.LastTrade,
) {
    constructor(market: MarketEntity, snapshot: OrderBookSnapshot) : this(
        market.id.value,
        bids = snapshot.bids.map(OrderBook::Entry),
        asks = snapshot.asks.map(OrderBook::Entry),
        last = OrderBook.LastTrade(snapshot.last),
    )
}
