package co.chainring.core.websocket

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
import java.math.BigDecimal
import java.math.BigInteger
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
        fun stutter() = rnd.nextLong(-500000, 500000)
        val lastStutter = stutter() / 3.0
        val orderBook = OrderBook(
            marketId = marketId,
            last = LastTrade(String.format("%.2f", (17.5 + lastStutter)), if (lastStutter > 0) LastTradeDirection.Up else LastTradeDirection.Down),
            buy = listOf(
                OrderBookEntry("17.75", BigInteger.valueOf(2300000 + stutter())),
                OrderBookEntry("18.00", BigInteger.valueOf(2800000 + stutter())),
                OrderBookEntry("18.25", BigInteger.valueOf(5000000 + stutter())),
                OrderBookEntry("18.50", BigInteger.valueOf(10100000 + stutter())),
                OrderBookEntry("18.75", BigInteger.valueOf(9500000 + stutter())),
                OrderBookEntry("19.00", BigInteger.valueOf(12400000 + stutter())),
                OrderBookEntry("19.50", BigInteger.valueOf(14200000 + stutter())),
                OrderBookEntry("20.00", BigInteger.valueOf(15300000 + stutter())),
                OrderBookEntry("20.50", BigInteger.valueOf(19000000 + stutter())),
            ),
            sell = listOf(
                OrderBookEntry("17.25", BigInteger.valueOf(2100000 + stutter())),
                OrderBookEntry("17.00", BigInteger.valueOf(5000000 + stutter())),
                OrderBookEntry("16.75", BigInteger.valueOf(5400000 + stutter())),
                OrderBookEntry("16.50", BigInteger.valueOf(7500000 + stutter())),
                OrderBookEntry("16.25", BigInteger.valueOf(1010000 + stutter())),
                OrderBookEntry("16.00", BigInteger.valueOf(7500000 + stutter())),
                OrderBookEntry("15.50", BigInteger.valueOf(12400000 + stutter())),
                OrderBookEntry("15.00", BigInteger.valueOf(11300000 + stutter())),
                OrderBookEntry("14.50", BigInteger.valueOf(14000000 + stutter())),
                OrderBookEntry("14.00", BigInteger.valueOf(19500000 + stutter())),
            ),
        )
        val response: OutgoingWSMessage = OutgoingWSMessage.Publish(orderBook)
        websocket.send(WsMessage(Json.encodeToString(response)))
    }

    private fun BigDecimal.toFundamentalUnits(decimals: Int): BigInteger {
        return (this * BigDecimal("1e$decimals")).toBigInteger()
    }
}
