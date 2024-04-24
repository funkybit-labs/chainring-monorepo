package co.chainring.apps.api.model.websocket

import co.chainring.apps.api.model.Balance
import co.chainring.apps.api.model.BigDecimalJson
import co.chainring.apps.api.model.Order
import co.chainring.apps.api.model.Trade
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OHLCDuration
import kotlinx.datetime.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
sealed class OutgoingWSMessage {
    @Serializable
    @SerialName("Publish")
    data class Publish(
        val topic: SubscriptionTopic,
        val data: Publishable,
    ) : OutgoingWSMessage()
}

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
sealed class Publishable

@Serializable
@SerialName("OrderBook")
data class OrderBook(
    val marketId: MarketId,
    val buy: List<OrderBookEntry>,
    val sell: List<OrderBookEntry>,
    val last: LastTrade,
) : Publishable()

@Serializable
data class OrderBookEntry(
    val price: String,
    val size: BigDecimalJson,
)

@Serializable
enum class LastTradeDirection {
    Up,
    Down,
    Unchanged,
}

@Serializable
data class LastTrade(
    val price: String,
    val direction: LastTradeDirection,
)

@Serializable
@SerialName("Prices")
data class Prices(
    val market: MarketId,
    val duration: OHLCDuration,
    val ohlc: List<OHLC>,
    val full: Boolean,
) : Publishable()

@Serializable
data class OHLC(
    val start: Instant,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val duration: OHLCDuration,
)

@Serializable
@SerialName("Trades")
data class Trades(
    val trades: List<Trade>,
) : Publishable()

@Serializable
@SerialName("TradeCreated")
data class TradeCreated(
    val trade: Trade,
) : Publishable()

@Serializable
@SerialName("TradeUpdated")
data class TradeUpdated(
    val trade: Trade,
) : Publishable()

@Serializable
@SerialName("Orders")
data class Orders(
    val orders: List<Order>,
) : Publishable()

@Serializable
@SerialName("Balances")
data class Balances(
    val balances: List<Balance>,
) : Publishable()

@Serializable
@SerialName("OrderCreated")
data class OrderCreated(
    val order: Order,
) : Publishable()

@Serializable
@SerialName("OrderUpdated")
data class OrderUpdated(
    val order: Order,
) : Publishable()
