package co.chainring.core.websocket

import co.chainring.apps.api.model.BigDecimalJson
import co.chainring.apps.api.model.LastTrade
import co.chainring.apps.api.model.LastTradeDirection
import co.chainring.apps.api.model.OHLC
import co.chainring.apps.api.model.OrderBook
import co.chainring.apps.api.model.OrderBookEntry
import co.chainring.apps.api.model.OutgoingWSMessage
import co.chainring.apps.api.model.Prices
import co.chainring.apps.api.model.SubscriptionTopic
import co.chainring.apps.api.model.Trade
import co.chainring.apps.api.model.WsTrades
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.TradeId
import co.chainring.core.utils.Timer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.http4k.websocket.Websocket
import org.http4k.websocket.WsMessage
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

typealias Subscriptions = CopyOnWriteArrayList<Websocket>
typealias TopicSubscriptions = ConcurrentHashMap<SubscriptionTopic, Subscriptions>
typealias MarketSubscriptions = ConcurrentHashMap<MarketId, TopicSubscriptions>

class Broadcaster {
    private val subscriptions = MarketSubscriptions()
    private val lastPricePublish = mutableMapOf<Pair<MarketId, Websocket>, Instant>()
    private val logger = KotlinLogging.logger {}
    private val rnd = Random(0)

    fun subscribe(market: MarketId, topic: SubscriptionTopic, websocket: Websocket) {
        subscriptions.getOrPut(market) {
            TopicSubscriptions()
        }.getOrPut(topic) {
            Subscriptions()
        }.addIfAbsent(websocket)

        when (topic) {
            SubscriptionTopic.OrderBook -> sendOrderBook(market, websocket)
            SubscriptionTopic.Prices -> sendPrices(market, websocket)
            SubscriptionTopic.Trades -> sendTrades(market, websocket)
        }
    }

    fun unsubscribe(marketId: MarketId, topic: SubscriptionTopic, websocket: Websocket) {
        subscriptions[marketId]?.let { it[topic]?.remove(websocket) }
        lastPricePublish.remove(Pair(marketId, websocket))
    }

    fun unsubscribe(websocket: Websocket) {
        subscriptions.forEach { (market, topicSubscriptions) ->
            topicSubscriptions.values.forEach {
                it.remove(websocket)
            }
            lastPricePublish.remove(Pair(market, websocket))
        }
    }

    private var timer: Timer? = null

    fun start() {
        timer = Timer(logger)
        timer?.scheduleAtFixedRate(Duration.ofSeconds(1), stopOnError = true, this::publishData)
    }

    fun stop() {
        timer?.cancel()
    }

    private fun publishData() {
        subscriptions.forEach { (marketId, topicSubscriptions) ->
            topicSubscriptions[SubscriptionTopic.OrderBook]?.forEach { websocket ->
                sendOrderBook(marketId, websocket)
            }
            topicSubscriptions[SubscriptionTopic.Prices]?.forEach { websocket ->
                sendPrices(marketId, websocket)
            }
            topicSubscriptions[SubscriptionTopic.Trades]?.forEach { websocket ->
                sendTrades(marketId, websocket)
            }
        }
    }

    private val mockPrices = ConcurrentHashMap(
        mapOf(
            MarketId("ETH/USDC") to 17.2,
            MarketId("USDC/DAI") to 1.05,
        ),
    )

    private fun mockOHLC(marketId: MarketId, startTime: Instant, duration: kotlin.time.Duration, count: Long, full: Boolean): List<OHLC> {
        fun priceAdjust(range: Double, direction: Int) =
            1 + (rnd.nextDouble() * range) + when (direction) {
                0 -> -(range / 2)
                -1 -> -(2 * range)
                else -> 0.0
            }
        return (0 until count).map { i ->
            val curPrice = mockPrices[marketId]!!
            val nextPrice = curPrice * priceAdjust(if (full) 0.001 else 0.0001, 0)
            mockPrices[marketId] = nextPrice
            OHLC(
                start = startTime.plus((duration.inWholeSeconds * i).seconds),
                open = curPrice,
                high = max(curPrice, nextPrice) * priceAdjust(0.0001, 1),
                low = min(curPrice, nextPrice) * priceAdjust(0.0001, -1),
                close = nextPrice,
                durationMs = duration.inWholeMilliseconds,
            )
        }
    }

    private fun sendPrices(market: MarketId, websocket: Websocket) {
        val key = Pair(market, websocket)
        val fullDump = !lastPricePublish.containsKey(key)
        val now = Clock.System.now()
        val prices = if (fullDump) {
            lastPricePublish[key] = now
            Prices(
                market = market,
                ohlc = mockOHLC(market, now.minus(7.days), 5.minutes, 12 * 24 * 7, true),
                full = true,
            )
        } else {
            Prices(
                market = market,
                ohlc = mockOHLC(market, lastPricePublish[key]!!, 1.seconds, (now - lastPricePublish[key]!!).inWholeSeconds, false),
                full = false,
            ).also {
                lastPricePublish[key] = now
            }
        }
        val response: OutgoingWSMessage = OutgoingWSMessage.Publish(prices)
        websocket.send(WsMessage(Json.encodeToString(response)))
    }

    private fun sendTrades(marketId: MarketId, websocket: Websocket) {
        val lastStutter = rnd.nextDouble(-0.5, 0.5) / 3.0

        val trades = WsTrades(
            trades = listOf(
                Trade(
                    id = TradeId.generate(),
                    timestamp = Clock.System.now(),
                    orderId = OrderId.generate(),
                    marketId = marketId,
                    side = if (lastStutter > 0) OrderSide.Buy else OrderSide.Sell,
                    amount = BigDecimal(rnd.nextDouble(0.00001, 0.5)).setScale(5, RoundingMode.UP).toFundamentalUnits(18),
                    price = BigDecimal(17.5 + lastStutter).setScale(5, RoundingMode.UP).toFundamentalUnits(18),
                    feeAmount = BigInteger.ZERO,
                    feeSymbol = Symbol(marketId.value.split("/")[0]),
                ),
            ),
        )

        val response: OutgoingWSMessage = OutgoingWSMessage.Publish(trades)
        websocket.send(WsMessage(Json.encodeToString(response)))
    }

    private fun sendOrderBook(marketId: MarketId, websocket: Websocket) {
        when (marketId.value) {
            "ETH/USDC" -> {
                fun stutter() = rnd.nextDouble(-0.5, 0.5)
                val lastStutter = stutter() / 3.0

                OrderBook(
                    marketId = marketId,
                    last = LastTrade(
                        String.format("%.2f", (17.5 + lastStutter)),
                        if (lastStutter > 0) LastTradeDirection.Up else LastTradeDirection.Down,
                    ),
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
            }
            "USDC/DAI" -> {
                fun stutter() = rnd.nextDouble(-0.01, 0.01)
                val lastStutter = stutter()

                OrderBook(
                    marketId = marketId,
                    last = LastTrade(
                        String.format("%.2f", (1.03 + lastStutter)),
                        if (lastStutter > 0) LastTradeDirection.Up else LastTradeDirection.Down,
                    ),
                    buy = listOf(
                        OrderBookEntry("1.05", BigDecimalJson(100 + stutter())),
                        OrderBookEntry("1.06", BigDecimalJson(200 + stutter())),
                        OrderBookEntry("1.07", BigDecimalJson(10 + stutter())),
                        OrderBookEntry("1.08", BigDecimalJson(150 + stutter())),
                        OrderBookEntry("1.09", BigDecimalJson(20 + stutter())),
                    ),
                    sell = listOf(
                        OrderBookEntry("1.04", BigDecimalJson(120 + stutter())),
                        OrderBookEntry("1.03", BigDecimalJson(300 + stutter())),
                        OrderBookEntry("1.02", BigDecimalJson(100 + stutter())),
                        OrderBookEntry("1.01", BigDecimalJson(50 + stutter())),
                        OrderBookEntry("1.00", BigDecimalJson(30 + stutter())),
                    ),
                )
            }
            else -> null
        }?.also { orderBook ->
            val response: OutgoingWSMessage = OutgoingWSMessage.Publish(orderBook)
            websocket.send(WsMessage(Json.encodeToString(response)))
        }
    }

    fun BigDecimal.toFundamentalUnits(decimals: Int): BigInteger {
        return (this * BigDecimal("1e$decimals")).toBigInteger()
    }
}
