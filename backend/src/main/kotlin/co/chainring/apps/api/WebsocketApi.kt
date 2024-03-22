package co.chainring.apps.api

import co.chainring.apps.api.model.IncomingWSMessage
import co.chainring.core.websocket.Broadcaster
import io.github.oshai.kotlinlogging.KotlinLogging
import org.http4k.format.KotlinxSerialization.auto
import org.http4k.routing.RoutingWsHandler
import org.http4k.routing.ws.bind
import org.http4k.websocket.Websocket
import org.http4k.websocket.WsMessage
import org.http4k.websocket.WsResponse

class WebsocketApi(private val broadcaster: Broadcaster) {
    private val logger = KotlinLogging.logger {}

    private val messageLens = WsMessage.auto<IncomingWSMessage>().toLens()

    fun connect(): RoutingWsHandler {
        return "/connect" bind { _ ->
            WsResponse { ws: Websocket ->
                logger.debug { "Websocket client connected" }

                ws.onError { t ->
                    logger.warn(t) { "Websocket error" }
                    ws.close()
                }

                ws.onClose { status ->
                    logger.debug { "Websocket client disconnected, status: $status" }
                    broadcaster.unsubscribe(ws)
                }

                ws.onMessage { wsMessage ->
                    when (val message = messageLens(wsMessage)) {
                        is IncomingWSMessage.Subscribe -> {
                            broadcaster.subscribe(message.instrument, ws)
                        }
                        is IncomingWSMessage.Unsubscribe -> {
                            broadcaster.unsubscribe(message.instrument, ws)
                        }
                    }
                }
            }
        }
    }
}
