package co.chainring.apps.api.model

import co.chainring.core.model.db.MarketId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

enum class SubscriptionTopic {
    OrderBook,
    Prices,
    Trades,
}

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
sealed class IncomingWSMessage {
    @Serializable
    @SerialName("Subscribe")
    data class Subscribe(
        val marketId: MarketId,
        val topic: SubscriptionTopic,
    ) : IncomingWSMessage()

    @Serializable
    @SerialName("Unsubscribe")
    data class Unsubscribe(
        val marketId: MarketId,
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
