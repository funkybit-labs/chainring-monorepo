package co.chainring.apps.api

import co.chainring.apps.api.middleware.AuthResult
import co.chainring.apps.api.middleware.validateDidToken
import co.chainring.apps.api.model.websocket.IncomingWSMessage
import co.chainring.core.websocket.Broadcaster
import co.chainring.core.websocket.ConnectedClient
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Instant
import org.http4k.format.KotlinxSerialization.auto
import org.http4k.routing.RoutingWsHandler
import org.http4k.routing.ws.bind
import org.http4k.websocket.Websocket
import org.http4k.websocket.WsMessage
import org.http4k.websocket.WsResponse
import org.http4k.websocket.WsStatus

val wsUnauthorized = WsStatus(code = 3000, description = "Unauthorized")

class WebsocketApi(private val broadcaster: Broadcaster) {
    private val logger = KotlinLogging.logger {}

    private val messageLens = WsMessage.auto<IncomingWSMessage>().toLens()

    fun connect(): RoutingWsHandler {
        return "/connect" bind { request ->
            WsResponse { websocket: Websocket ->
                logger.debug { "Websocket client connected" }

                val connectedClient = when (val auth = request.query("auth")) {
                    null -> ConnectedClient(websocket, null, Instant.DISTANT_FUTURE)
                    else -> when (val result = validateDidToken(auth)) {
                        is AuthResult.Success -> ConnectedClient(websocket, result.address, result.expiresAt)
                        else -> {
                            websocket.close(wsUnauthorized)
                            ConnectedClient(websocket, null, Instant.DISTANT_FUTURE)
                        }
                    }
                }

                connectedClient.onError { t ->
                    logger.warn(t) { "Websocket error" }
                    websocket.close()
                }

                connectedClient.onClose { status ->
                    logger.debug { "Websocket client disconnected, status: $status" }
                    broadcaster.unsubscribe(connectedClient)
                }

                connectedClient.onMessage { wsMessage ->
                    when (val message = messageLens(wsMessage)) {
                        is IncomingWSMessage.Subscribe -> {
                            broadcaster.subscribe(message.topic, connectedClient)
                        }

                        is IncomingWSMessage.Unsubscribe -> {
                            broadcaster.unsubscribe(message.topic, connectedClient)
                        }
                    }
                }
            }
        }
    }
}
