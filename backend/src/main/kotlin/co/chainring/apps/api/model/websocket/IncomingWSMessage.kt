package co.chainring.apps.api.model.websocket

import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OHLCDuration
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
sealed class IncomingWSMessage {
    @Serializable
    @SerialName("Subscribe")
    data class Subscribe(
        val topic: SubscriptionTopic,
    ) : IncomingWSMessage()

    @Serializable
    @SerialName("Unsubscribe")
    data class Unsubscribe(
        val topic: SubscriptionTopic,
    ) : IncomingWSMessage()
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
sealed class SubscriptionTopic {
    @Serializable
    @SerialName("OrderBook")
    data class OrderBook(val marketId: MarketId) : SubscriptionTopic()

    @Serializable
    @SerialName("Prices")
    data class Prices(val marketId: MarketId, val duration: OHLCDuration) : SubscriptionTopic()

    @Serializable
    @SerialName("MyTrades")
    data object MyTrades : SubscriptionTopic()

    @Serializable
    @SerialName("MarketTrades")
    data class MarketTrades(val marketId: MarketId) : SubscriptionTopic()

    @Serializable
    @SerialName("MyOrders")
    data object MyOrders : SubscriptionTopic()

    @Serializable
    @SerialName("Balances")
    data object Balances : SubscriptionTopic()

    @Serializable
    @SerialName("Limits")
    data object Limits : SubscriptionTopic()
}
