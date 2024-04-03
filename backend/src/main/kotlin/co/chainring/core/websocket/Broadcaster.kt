package co.chainring.core.websocket

import co.chainring.apps.api.model.BigDecimalJson
import co.chainring.apps.api.model.Trade
import co.chainring.apps.api.model.websocket.LastTrade
import co.chainring.apps.api.model.websocket.LastTradeDirection
import co.chainring.apps.api.model.websocket.OHLC
import co.chainring.apps.api.model.websocket.OrderBook
import co.chainring.apps.api.model.websocket.OrderBookEntry
import co.chainring.apps.api.model.websocket.OrderCreated
import co.chainring.apps.api.model.websocket.OrderUpdated
import co.chainring.apps.api.model.websocket.Orders
import co.chainring.apps.api.model.websocket.OutgoingWSMessage
import co.chainring.apps.api.model.websocket.Prices
import co.chainring.apps.api.model.websocket.Publishable
import co.chainring.apps.api.model.websocket.SubscriptionTopic
import co.chainring.apps.api.model.websocket.Trades
import co.chainring.core.model.Address
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderEntity
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.TradeId
import co.chainring.core.utils.Timer
import co.chainring.core.utils.toFundamentalUnits
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.http4k.websocket.Websocket
import org.http4k.websocket.WsMessage
import org.jetbrains.exposed.sql.transactions.transaction
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

private val logger = KotlinLogging.logger {}

typealias Principal = Address

data class ConnectedClient(
    val websocket: Websocket,
    val principal: Principal?,
) : Websocket by websocket {
    fun send(message: OutgoingWSMessage) {
        logger.debug { "Sending $message to $principal" }
        websocket.send(WsMessage(Json.encodeToString(message)))
    }
}

typealias Subscriptions = CopyOnWriteArrayList<ConnectedClient>
typealias TopicSubscriptions = ConcurrentHashMap<SubscriptionTopic, Subscriptions>

class Broadcaster {
    private val subscriptions = TopicSubscriptions()
    private val subscriptionsByPrincipal = ConcurrentHashMap<Principal, TopicSubscriptions>()
    private val lastPricePublish = mutableMapOf<Pair<MarketId, ConnectedClient>, Instant>()
    private val rnd = Random(0)

    fun subscribe(topic: SubscriptionTopic, client: ConnectedClient) {
        subscriptions.getOrPut(topic) {
            Subscriptions()
        }.addIfAbsent(client)

        client.principal?.also { principal ->
            subscriptionsByPrincipal.getOrPut(principal) {
                TopicSubscriptions()
            }.getOrPut(topic) {
                Subscriptions()
            }.addIfAbsent(client)
        }

        when (topic) {
            is SubscriptionTopic.OrderBook -> sendOrderBook(topic.marketId, client)
            is SubscriptionTopic.Prices -> sendPrices(topic.marketId, client)
            is SubscriptionTopic.Trades -> sendTrades(client)
            is SubscriptionTopic.Orders -> sendOrders(client)
        }
    }

    fun unsubscribe(topic: SubscriptionTopic, client: ConnectedClient) {
        subscriptions[topic]?.remove(client)
        if (topic is SubscriptionTopic.Prices) {
            lastPricePublish.remove(Pair(topic.marketId, client))
        }
        client.principal?.also { principal ->
            subscriptionsByPrincipal[principal]?.get(topic)?.remove(client)
        }
    }

    fun unsubscribe(client: ConnectedClient) {
        subscriptions.keys.forEach { topic ->
            unsubscribe(topic, client)
        }
        client.principal?.also { principal ->
            subscriptionsByPrincipal.remove(principal)
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
        subscriptions.forEach { (topic, clients) ->
            when (topic) {
                is SubscriptionTopic.OrderBook -> sendOrderBook(topic.marketId, clients)
                is SubscriptionTopic.Prices -> sendPrices(topic.marketId, clients)
                else -> {}
            }
        }
    }

    private val mockPrices = ConcurrentHashMap(
        mapOf(
            MarketId("BTC/ETH") to 17.2,
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

    private fun sendPrices(market: MarketId, clients: List<ConnectedClient>) {
        clients.forEach { sendPrices(market, it) }
    }

    private fun sendPrices(market: MarketId, client: ConnectedClient) {
        val key = Pair(market, client)
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
        client.send(OutgoingWSMessage.Publish(prices))
    }

    private fun sendTrades(client: ConnectedClient) {
        fun generateTrade(timestamp: Instant): Trade {
            val lastStutter = rnd.nextDouble(-0.5, 0.5) / 3.0
            val marketId = setOf(MarketId("BTC/ETH"), MarketId("USDC/DAI")).random()
            val (amount, price) = (
                when (marketId.value) {
                    "BTC/ETH" -> Pair(
                        BigDecimal(rnd.nextDouble(0.00001, 0.5)).setScale(5, RoundingMode.UP).toFundamentalUnits(18),
                        BigDecimal(17.5 + lastStutter).setScale(5, RoundingMode.UP).toFundamentalUnits(18),
                    )
                    "USDC/DAI" -> Pair(
                        BigDecimal(rnd.nextDouble(1000.00, 10000.00)).setScale(5, RoundingMode.UP).toFundamentalUnits(18),
                        BigDecimal(1.00 + lastStutter / 10).setScale(5, RoundingMode.UP).toFundamentalUnits(18),
                    )
                    else -> null
                }
                )!!

            return Trade(
                id = TradeId.generate(),
                timestamp = timestamp,
                orderId = OrderId.generate(),
                marketId = marketId,
                side = if (lastStutter > 0) OrderSide.Buy else OrderSide.Sell,
                amount = amount,
                price = price,
                feeAmount = BigInteger.ZERO,
                feeSymbol = Symbol(marketId.value.split("/")[0]),
            )
        }

        client.send(
            OutgoingWSMessage.Publish(
                Trades((1..100).map { i -> generateTrade(Clock.System.now().minus(i.minutes)) }),
            ),
        )
    }

    private fun sendOrderBook(marketId: MarketId, clients: List<ConnectedClient>) {
        clients.forEach { sendOrderBook(marketId, it) }
    }

    private fun sendOrderBook(marketId: MarketId, client: ConnectedClient) {
        when (marketId.value) {
            "BTC/ETH" -> {
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
            client.send(OutgoingWSMessage.Publish(orderBook))
        }
    }

    private fun sendOrders(client: ConnectedClient) {
        if (client.principal != null) {
            transaction {
                client.send(
                    OutgoingWSMessage.Publish(
                        Orders(
                            OrderEntity.listOrders(client.principal)
                                .map { it.toOrderResponse() },
                        ),
                    ),
                )
            }
        }
    }

    fun sendOrders(principal: Principal) {
        findClients(principal, SubscriptionTopic.Orders)
            .forEach { sendOrders(it) }
    }

    fun notify(principal: Principal, message: Publishable) {
        val topic = when (message) {
            is OrderBook -> SubscriptionTopic.OrderBook(message.marketId)
            is Orders, is OrderCreated, is OrderUpdated -> SubscriptionTopic.Orders
            is Prices -> SubscriptionTopic.Prices(message.market)
            is Trades -> SubscriptionTopic.Trades
        }

        findClients(principal, topic)
            .forEach { it.send(OutgoingWSMessage.Publish(message)) }
    }

    private fun findClients(principal: Principal, topic: SubscriptionTopic): List<ConnectedClient> =
        subscriptionsByPrincipal[principal]?.get(topic) ?: emptyList()
}
