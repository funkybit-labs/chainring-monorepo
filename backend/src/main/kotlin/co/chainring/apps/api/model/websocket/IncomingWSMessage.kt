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
    @SerialName("Trades")
    data object Trades : SubscriptionTopic()

    @Serializable
    @SerialName("Orders")
    data object Orders : SubscriptionTopic()

    @Serializable
    @SerialName("Balances")
    data object Balances : SubscriptionTopic()
}
