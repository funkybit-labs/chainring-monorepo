package co.chainring.apps.api.model

import co.chainring.core.model.db.MarketId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
sealed class SubscriptionTopic {
    @Serializable
    @SerialName("OrderBook")
    data class OrderBook(val marketId: MarketId) : SubscriptionTopic()

    @Serializable
    @SerialName("Prices")
    data class Prices(val marketId: MarketId) : SubscriptionTopic()

    @Serializable
    @SerialName("Trades")
    data object Trades : SubscriptionTopic()
}

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

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
sealed class Publishable

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
sealed class OutgoingWSMessage {
    @Serializable
    @SerialName("Publish")
    data class Publish(
        val data: Publishable,
    ) : OutgoingWSMessage()
}
