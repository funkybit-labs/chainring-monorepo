package xyz.funkybit.core.websocket

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.http4k.websocket.Websocket
import org.http4k.websocket.WsMessage
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.apps.api.model.websocket.Balances
import xyz.funkybit.apps.api.model.websocket.Limits
import xyz.funkybit.apps.api.model.websocket.MarketTradesCreated
import xyz.funkybit.apps.api.model.websocket.MyOrderCreated
import xyz.funkybit.apps.api.model.websocket.MyOrderUpdated
import xyz.funkybit.apps.api.model.websocket.MyOrders
import xyz.funkybit.apps.api.model.websocket.MyTrades
import xyz.funkybit.apps.api.model.websocket.MyTradesCreated
import xyz.funkybit.apps.api.model.websocket.MyTradesUpdated
import xyz.funkybit.apps.api.model.websocket.OHLC
import xyz.funkybit.apps.api.model.websocket.OrderBook
import xyz.funkybit.apps.api.model.websocket.OrderBookDiff
import xyz.funkybit.apps.api.model.websocket.OutgoingWSMessage
import xyz.funkybit.apps.api.model.websocket.Prices
import xyz.funkybit.apps.api.model.websocket.SubscriptionTopic
import xyz.funkybit.apps.api.wsUnauthorized
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.db.BalanceEntity
import xyz.funkybit.core.model.db.BroadcasterJobEntity
import xyz.funkybit.core.model.db.BroadcasterJobId
import xyz.funkybit.core.model.db.BroadcasterNotification
import xyz.funkybit.core.model.db.LimitEntity
import xyz.funkybit.core.model.db.MarketEntity
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.OHLCDuration
import xyz.funkybit.core.model.db.OHLCEntity
import xyz.funkybit.core.model.db.OrderBookSnapshot
import xyz.funkybit.core.model.db.OrderEntity
import xyz.funkybit.core.model.db.OrderExecutionEntity
import xyz.funkybit.core.model.db.OrderStatus
import xyz.funkybit.core.model.db.WalletEntity
import xyz.funkybit.core.model.db.toOrderResponse
import xyz.funkybit.core.model.toChecksumAddress
import xyz.funkybit.core.utils.PgListener
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.hours

private val logger = KotlinLogging.logger {}

typealias Principal = Address

data class ConnectedClient(
    val websocket: Websocket,
    val principal: Principal?,
    val authorizedUntil: Instant,
) : Websocket by websocket {
    fun send(message: OutgoingWSMessage) {
        if (authorizedUntil >= Clock.System.now()) {
            websocket.send(WsMessage(Json.encodeToString(message)))
        } else {
            websocket.close(wsUnauthorized)
        }
    }
}

typealias Subscriptions = CopyOnWriteArrayList<ConnectedClient>
typealias TopicSubscriptions = ConcurrentHashMap<SubscriptionTopic, Subscriptions>

class Broadcaster(val db: Database) {
    private val subscriptions = TopicSubscriptions()
    private val subscriptionsByPrincipal = ConcurrentHashMap<Principal, TopicSubscriptions>()
    private val lastPricePublish = mutableMapOf<Pair<MarketId, ConnectedClient>, Instant>()
    private val orderBooksByMarket = ConcurrentHashMap<MarketId, OrderBook>()
    private val pricesByMarketAndPeriod = ConcurrentHashMap<SubscriptionTopic.Prices, MutableList<OHLC>>()
    private val pricesDailyChangeByMarket = ConcurrentHashMap<MarketId, BigDecimal>()

    private val pgListener = PgListener(
        db,
        threadName = "broadcaster-listener",
        channel = "broadcaster_ctl",
        onReconnect = {
            reloadPrices()
        },
        onNotifyLogic = { notification ->
            handleDbNotification(notification.parameter)
        },
    )

    fun subscribe(topic: SubscriptionTopic, client: ConnectedClient) {
        subscriptions.getOrPut(topic) {
            Subscriptions()
        }.addIfAbsent(client)

        client.principal?.also { principal ->
            subscriptionsByPrincipal.getOrPut(principal.toChecksumAddress()) {
                TopicSubscriptions()
            }.getOrPut(topic) {
                Subscriptions()
            }.addIfAbsent(client)
        }

        when (topic) {
            is SubscriptionTopic.OrderBook -> sendOrderBook(topic, client)
            is SubscriptionTopic.IncrementalOrderBook -> sendOrderBook(topic, client)
            is SubscriptionTopic.Prices -> sendPrices(topic, client)
            is SubscriptionTopic.MyTrades -> sendTrades(client)
            is SubscriptionTopic.MyOrders -> sendOrders(client)
            is SubscriptionTopic.Balances -> sendBalances(client)
            is SubscriptionTopic.Limits -> sendLimits(client)
            is SubscriptionTopic.MarketTrades -> {}
        }
    }

    fun unsubscribe(topic: SubscriptionTopic, client: ConnectedClient): Boolean {
        subscriptions[topic]?.remove(client)
        if (topic is SubscriptionTopic.Prices) {
            lastPricePublish.remove(Pair(topic.marketId, client))
        }
        return client.principal?.let { principal ->
            subscriptionsByPrincipal[principal]?.get(topic)?.remove(client)
            subscriptionsByPrincipal[principal]?.get(topic)?.isNotEmpty() ?: false
        } ?: false
    }

    fun unsubscribe(client: ConnectedClient) {
        val hasSubscriptions = subscriptions.keys.map { topic ->
            unsubscribe(topic, client)
        }.toSet().contains(true)

        client.principal?.also { principal ->
            if (!hasSubscriptions) {
                subscriptionsByPrincipal.remove(principal)
            }
        }
    }

    fun start() {
        pgListener.start()
        reloadPrices()
    }

    private fun reloadPrices() {
        transaction {
            // load historical prices once, later only updates will be delivered via notify
            MarketEntity.all().map { it.guid.value }.forEach { market ->
                OHLCDuration.entries.forEach { duration ->
                    // equivalent to 7 days of 5 minutes intervals
                    val startTime = Clock.System.now() - duration.interval() * 20 * 24 * 7

                    val ohlcEntities = OHLCEntity.fetchForMarketStartingFrom(market, duration, startTime = startTime)
                    pricesByMarketAndPeriod[SubscriptionTopic.Prices(market, duration)] = ohlcEntities.map { it.toWSResponse() }.toMutableList()
                }

                pricesByMarketAndPeriod[SubscriptionTopic.Prices(market, OHLCDuration.P1M)]?.let { ohlcs ->
                    ohlcs.lastOrNull()?.let { lastOhlc ->
                        val nowMinus24h = OHLCDuration.P1M.durationStart(Clock.System.now() - 24.hours)
                        OHLCEntity.findSingleByClosestStartTime(market, OHLCDuration.P1M, nowMinus24h)?.let { h24Ohlc ->
                            pricesDailyChangeByMarket[market] = (lastOhlc.close - h24Ohlc.close) / h24Ohlc.close
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        pgListener.stop()
    }

    private fun sendPrices(topic: SubscriptionTopic.Prices, client: ConnectedClient) {
        val key = Pair(topic.marketId, client)
        val now = Clock.System.now()

        val prices = when (val lastTimestamp = lastPricePublish[key]) {
            null -> Prices(
                market = topic.marketId,
                duration = topic.duration,
                // 3360 is equivalent to 7 days of 5 minutes sticks
                ohlc = pricesByMarketAndPeriod[topic]?.takeLast(3360) ?: listOf(),
                full = true,
                dailyChange = pricesDailyChangeByMarket[topic.marketId] ?: BigDecimal.ZERO,
            )
            else -> Prices(
                market = topic.marketId,
                duration = topic.duration,
                ohlc = pricesByMarketAndPeriod[topic]?.takeLastWhile { it.start + it.duration.interval() > lastTimestamp } ?: listOf(),
                full = false,
                dailyChange = pricesDailyChangeByMarket[topic.marketId] ?: BigDecimal.ZERO,
            )
        }

        client.send(OutgoingWSMessage.Publish(topic, prices)).also {
            lastPricePublish[key] = now
        }
    }

    private fun sendTrades(client: ConnectedClient) {
        if (client.principal != null) {
            transaction {
                client.send(
                    OutgoingWSMessage.Publish(
                        SubscriptionTopic.MyTrades,
                        MyTrades(
                            OrderExecutionEntity
                                .listLatestForWallet(
                                    WalletEntity.getOrCreate(client.principal),
                                    maxSequencerResponses = 100,
                                ).map(OrderExecutionEntity::toTradeResponse),
                        ),
                    ),
                )
            }
        }
    }

    private fun updatePrices(message: Prices) {
        val key = SubscriptionTopic.Prices(message.market, message.duration)
        pricesByMarketAndPeriod[key]?.let { existingEntries ->
            message.ohlc.sortedBy { it.start }.forEach { incoming ->
                val index = existingEntries.indexOfLast { it.start == incoming.start }
                if (index != -1) {
                    existingEntries[index] = incoming
                } else {
                    existingEntries.add(incoming)
                }
            }
        }
        pricesDailyChangeByMarket[key.marketId] = message.dailyChange
    }

    private fun getOrderBook(marketId: MarketId): OrderBook =
        orderBooksByMarket.getOrPut(marketId) {
            transaction {
                OrderBook(marketId, OrderBookSnapshot.get(marketId))
            }
        }

    private fun sendOrderBook(topic: SubscriptionTopic.OrderBook, client: ConnectedClient) {
        getOrderBook(topic.marketId).also { orderBook ->
            client.send(OutgoingWSMessage.Publish(topic, orderBook))
        }
    }

    private fun sendOrderBook(topic: SubscriptionTopic.IncrementalOrderBook, client: ConnectedClient) {
        getOrderBook(topic.marketId).also { orderBook ->
            client.send(OutgoingWSMessage.Publish(topic, orderBook))
        }
    }

    private fun sendOrders(client: ConnectedClient) {
        if (client.principal != null) {
            val combinedOrderResponses = transaction {
                val openOrders = OrderEntity.listWithExecutionsForWallet(
                    WalletEntity.getOrCreate(client.principal),
                    statuses = listOf(OrderStatus.Open, OrderStatus.Partial),
                )
                val recentOrders = OrderEntity.listWithExecutionsForWallet(
                    WalletEntity.getOrCreate(client.principal),
                    limit = 100,
                )

                (openOrders + recentOrders)
                    .distinctBy { it.first.id }
                    .sortedByDescending { it.first.createdAt }
                    .map(Pair<OrderEntity, List<OrderExecutionEntity>>::toOrderResponse)
            }

            client.send(
                OutgoingWSMessage.Publish(
                    SubscriptionTopic.MyOrders,
                    MyOrders(combinedOrderResponses),
                ),
            )
        }
    }

    private fun sendBalances(client: ConnectedClient) {
        if (client.principal != null) {
            transaction {
                client.send(
                    OutgoingWSMessage.Publish(
                        SubscriptionTopic.Balances,
                        Balances(
                            BalanceEntity.balancesAsApiResponse(WalletEntity.getOrCreate(client.principal)).balances,
                        ),
                    ),
                )
            }
        }
    }

    private fun sendLimits(client: ConnectedClient) {
        if (client.principal != null) {
            transaction {
                client.send(
                    OutgoingWSMessage.Publish(
                        SubscriptionTopic.Limits,
                        Limits(
                            LimitEntity.forWallet(
                                WalletEntity.getOrCreate(client.principal),
                            ),
                        ),
                    ),
                )
            }
        }
    }

    private fun handleDbNotification(payload: String) {
        logger.debug { "received db notification with payload $payload" }
        try {
            if (payload == "clear-cache") {
                clearCache()
            } else {
                val notifications = transaction {
                    BroadcasterJobEntity.findById(BroadcasterJobId(payload))?.notificationData ?: emptyList()
                }

                notifications.forEach(::notify)
            }
        } catch (e: Exception) {
            logger.error(e) { "Broadcaster: Unhandled exception" }
        }
    }

    private fun clearCache() {
        reloadPrices()
        orderBooksByMarket.clear()
    }

    private fun notify(notification: BroadcasterNotification) {
        val topic = when (notification.message) {
            is OrderBook -> {
                orderBooksByMarket.replace(notification.message.marketId, notification.message) // update cached value
                SubscriptionTopic.OrderBook(notification.message.marketId)
            }
            is OrderBookDiff -> SubscriptionTopic.IncrementalOrderBook(notification.message.marketId)
            is MyOrders, is MyOrderCreated, is MyOrderUpdated -> SubscriptionTopic.MyOrders
            is Prices -> {
                updatePrices(notification.message)
                SubscriptionTopic.Prices(notification.message.market, notification.message.duration)
            }
            is MyTrades, is MyTradesCreated, is MyTradesUpdated -> SubscriptionTopic.MyTrades
            is MarketTradesCreated -> SubscriptionTopic.MarketTrades(notification.message.marketId)
            is Balances -> SubscriptionTopic.Balances
            is Limits -> SubscriptionTopic.Limits
        }

        val clients = if (notification.recipient == null) {
            subscriptions.getOrPut(topic) { Subscriptions() }
        } else {
            findClients(notification.recipient, topic)
        }

        clients.forEach { client ->
            try {
                client.send(OutgoingWSMessage.Publish(topic, notification.message))
            } catch (e: Exception) {
                logger.warn(e) { "Error sending message. Recipient=${client.principal}, topic=$topic, message=${notification.message}" }
            }
        }
    }

    private fun findClients(principal: Principal, topic: SubscriptionTopic): List<ConnectedClient> =
        subscriptionsByPrincipal[principal]?.get(topic) ?: emptyList()
}
