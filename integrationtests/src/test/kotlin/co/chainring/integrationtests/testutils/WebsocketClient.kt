package co.chainring.integrationtests.testutils

import co.chainring.apps.api.model.websocket.IncomingWSMessage
import co.chainring.apps.api.model.websocket.OutgoingWSMessage
import co.chainring.apps.api.model.websocket.SubscriptionTopic
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.http4k.client.WebsocketClient
import org.http4k.core.Uri
import org.http4k.websocket.WsClient
import org.http4k.websocket.WsMessage

fun WebsocketClient.blocking(auth: String?): WsClient =
    blocking(Uri.of(apiServerRootUrl.replace("http:", "ws:").replace("https:", "wss:") + "/connect" + (auth?.let { "?auth=$auth" } ?: "")))

fun WsClient.send(message: IncomingWSMessage) {
    send(WsMessage(Json.encodeToString(message)))
}

fun WsClient.subscribe(topic: SubscriptionTopic) {
    send(IncomingWSMessage.Subscribe(topic))
}

fun WsClient.receivedDecoded(): Sequence<OutgoingWSMessage> =
    received().map {
        Json.decodeFromString<OutgoingWSMessage>(it.bodyString())
    }

fun WsClient.waitForMessage(): OutgoingWSMessage.Publish {
    return receivedDecoded().first() as OutgoingWSMessage.Publish
}
