package co.chainring.apps.api.model

import co.chainring.core.model.Instrument
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
        val instrument: Instrument,
    ) : IncomingWSMessage()

    @Serializable
    @SerialName("Unsubscribe")
    data class Unsubscribe(
        val instrument: Instrument,
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