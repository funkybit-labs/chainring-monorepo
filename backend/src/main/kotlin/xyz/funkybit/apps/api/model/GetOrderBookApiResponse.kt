package xyz.funkybit.apps.api.model
import kotlinx.serialization.Serializable
import xyz.funkybit.apps.api.model.websocket.OrderBook
import xyz.funkybit.core.model.db.MarketEntity
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.OrderBookSnapshot

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
