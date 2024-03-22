package co.chainring.core.websocket

import co.chainring.apps.api.model.BigDecimalJson
import co.chainring.apps.api.model.LastTrade
import co.chainring.apps.api.model.LastTradeDirection
import co.chainring.apps.api.model.OrderBook
import co.chainring.apps.api.model.OrderBookEntry
import co.chainring.apps.api.model.OutgoingWSMessage
import co.chainring.core.model.db.MarketId
import co.chainring.core.utils.Timer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.http4k.websocket.Websocket
import org.http4k.websocket.WsMessage
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

class Broadcaster {
    private val subscriptions = ConcurrentHashMap<MarketId, CopyOnWriteArrayList<Websocket>>()
    private val logger = KotlinLogging.logger {}
    private val rnd = Random(0)

    fun subscribe(instrument: MarketId, websocket: Websocket) {
        subscriptions.getOrPut(instrument) { CopyOnWriteArrayList<Websocket>() }.add(websocket)
        sendOrderBook(instrument, websocket)
    }

    fun unsubscribe(marketId: MarketId, websocket: Websocket) {
        subscriptions[marketId]?.remove(websocket)
    }

    fun unsubscribe(websocket: Websocket) {
        subscriptions.values.forEach {
            it.remove(websocket)
        }
    }

    private var timer: Timer? = null

    fun start() {
        timer = Timer(logger)
        timer?.scheduleAtFixedRate(Duration.ofSeconds(1), stopOnError = true, this::broadcastOrderBook)
    }

    fun stop() {
        timer?.cancel()
    }

    private fun broadcastOrderBook() {
        subscriptions.forEach { (instrument, websockets) ->
            websockets.forEach { websocket ->
                sendOrderBook(instrument, websocket)
            }
        }
    }

    private fun sendOrderBook(marketId: MarketId, websocket: Websocket) {
        fun stutter() = rnd.nextDouble(-0.5, 0.5)
        val lastStutter = stutter() / 3.0
        val orderBook = OrderBook(
            marketId = marketId,
            last = LastTrade(String.format("%.2f", (17.5 + lastStutter)), if (lastStutter > 0) LastTradeDirection.Up else LastTradeDirection.Down),
            buy = listOf(
                OrderBookEntry("17.75", BigDecimalJson(2.3 + stutter())),
                OrderBookEntry("18.00", BigDecimalJson(2.8 + stutter())),
                OrderBookEntry("18.25", BigDecimalJson(5 + stutter())),
                OrderBookEntry("18.50", BigDecimalJson(10.1 + stutter())),
                OrderBookEntry("18.75", BigDecimalJson(9.5 + stutter())),
                OrderBookEntry("19.00", BigDecimalJson(12.4 + stutter())),
                OrderBookEntry("19.50", BigDecimalJson(14.2 + stutter())),
                OrderBookEntry("20.00", BigDecimalJson(15.3 + stutter())),
                OrderBookEntry("20.50", BigDecimalJson(19 + stutter())),
            ),
            sell = listOf(
                OrderBookEntry("17.25", BigDecimalJson(2.1 + stutter())),
                OrderBookEntry("17.00", BigDecimalJson(5 + stutter())),
                OrderBookEntry("16.75", BigDecimalJson(5.4 + stutter())),
                OrderBookEntry("16.50", BigDecimalJson(7.5 + stutter())),
                OrderBookEntry("16.25", BigDecimalJson(10.1 + stutter())),
                OrderBookEntry("16.00", BigDecimalJson(7.5 + stutter())),
                OrderBookEntry("15.50", BigDecimalJson(12.4 + stutter())),
                OrderBookEntry("15.00", BigDecimalJson(11.3 + stutter())),
                OrderBookEntry("14.50", BigDecimalJson(14 + stutter())),
                OrderBookEntry("14.00", BigDecimalJson(19.5 + stutter())),
            ),
        )
        val response: OutgoingWSMessage = OutgoingWSMessage.Publish(orderBook)
        websocket.send(WsMessage(Json.encodeToString(response)))
    }
}
