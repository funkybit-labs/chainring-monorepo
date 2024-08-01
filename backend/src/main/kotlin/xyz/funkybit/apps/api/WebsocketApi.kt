package xyz.funkybit.apps.api

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Instant
import org.http4k.format.KotlinxSerialization.auto
import org.http4k.routing.RoutingWsHandler
import org.http4k.routing.ws.bind
import org.http4k.websocket.Websocket
import org.http4k.websocket.WsMessage
import org.http4k.websocket.WsResponse
import org.http4k.websocket.WsStatus
import xyz.funkybit.apps.api.middleware.AuthResult
import xyz.funkybit.apps.api.middleware.validateAuthToken
import xyz.funkybit.apps.api.model.websocket.IncomingWSMessage
import xyz.funkybit.core.websocket.Broadcaster
import xyz.funkybit.core.websocket.ConnectedClient

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
                    else -> when (val result = validateAuthToken(auth)) {
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
