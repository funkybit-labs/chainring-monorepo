package co.chainring.integrationtests.utils

import co.chainring.apps.api.model.websocket.Balances
import co.chainring.apps.api.model.websocket.IncomingWSMessage
import co.chainring.apps.api.model.websocket.OrderBook
import co.chainring.apps.api.model.websocket.OrderCreated
import co.chainring.apps.api.model.websocket.OrderUpdated
import co.chainring.apps.api.model.websocket.Orders
import co.chainring.apps.api.model.websocket.OutgoingWSMessage
import co.chainring.apps.api.model.websocket.Prices
import co.chainring.apps.api.model.websocket.Publishable
import co.chainring.apps.api.model.websocket.SubscriptionTopic
import co.chainring.apps.api.model.websocket.TradeCreated
import co.chainring.apps.api.model.websocket.TradeUpdated
import co.chainring.apps.api.model.websocket.Trades
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OHLCDuration
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.http4k.client.WebsocketClient
import org.http4k.core.Uri
import org.http4k.websocket.WsClient
import org.http4k.websocket.WsMessage
import org.junit.jupiter.api.Assertions.assertEquals
import kotlin.test.assertIs

fun WebsocketClient.blocking(auth: String?): WsClient =
    blocking(Uri.of(apiServerRootUrl.replace("http:", "ws:").replace("https:", "wss:") + "/connect" + (auth?.let { "?auth=$auth" } ?: "")))

fun WsClient.send(message: IncomingWSMessage) {
    send(WsMessage(Json.encodeToString(message)))
}

fun WsClient.subscribeToOrderBook(marketId: MarketId) {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.OrderBook(marketId)))
}

fun WsClient.subscribeToPrices(marketId: MarketId, duration: OHLCDuration = OHLCDuration.P5M) {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.Prices(marketId, duration)))
}

fun WsClient.subscribeToOrders() {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.Orders))
}

fun WsClient.subscribeToTrades() {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.Trades))
}

fun WsClient.subscribeToBalances() {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.Balances))
}

fun WsClient.unsubscribe(topic: SubscriptionTopic) {
    send(IncomingWSMessage.Unsubscribe(topic))
}

fun WsClient.receivedDecoded(): Sequence<OutgoingWSMessage> =
    received().map {
        Json.decodeFromString<OutgoingWSMessage>(it.bodyString())
    }

inline fun <reified M : Publishable> WsClient.assertMessageReceived(topic: SubscriptionTopic, dataAssertions: (M) -> Unit = {}): M {
    val message = receivedDecoded().first() as OutgoingWSMessage.Publish
    assertEquals(topic, message.topic)

    assertIs<M>(message.data)
    val data = message.data as M
    dataAssertions(data)

    return data
}

fun WsClient.assertOrdersMessageReceived(assertions: (Orders) -> Unit = {}): Orders =
    assertMessageReceived<Orders>(SubscriptionTopic.Orders, assertions)

fun WsClient.assertOrderCreatedMessageReceived(assertions: (OrderCreated) -> Unit = {}): OrderCreated =
    assertMessageReceived<OrderCreated>(SubscriptionTopic.Orders, assertions)

fun WsClient.assertOrderUpdatedMessageReceived(assertions: (OrderUpdated) -> Unit = {}): OrderUpdated =
    assertMessageReceived<OrderUpdated>(SubscriptionTopic.Orders, assertions)

fun WsClient.assertTradesMessageReceived(assertions: (Trades) -> Unit = {}): Trades =
    assertMessageReceived<Trades>(SubscriptionTopic.Trades, assertions)

fun WsClient.assertTradeCreatedMessageReceived(assertions: (TradeCreated) -> Unit = {}): TradeCreated =
    assertMessageReceived<TradeCreated>(SubscriptionTopic.Trades, assertions)

fun WsClient.assertTradeUpdatedMessageReceived(assertions: (TradeUpdated) -> Unit = {}): TradeUpdated =
    assertMessageReceived<TradeUpdated>(SubscriptionTopic.Trades, assertions)

fun WsClient.assertBalancesMessageReceived(assertions: (Balances) -> Unit = {}): Balances =
    assertMessageReceived<Balances>(SubscriptionTopic.Balances, assertions)

fun WsClient.assertPricesMessageReceived(marketId: MarketId, duration: OHLCDuration = OHLCDuration.P5M, assertions: (Prices) -> Unit = {}): Prices =
    assertMessageReceived<Prices>(SubscriptionTopic.Prices(marketId, duration), assertions)

fun WsClient.assertOrderBookMessageReceived(marketId: MarketId, assertions: (OrderBook) -> Unit = {}): OrderBook =
    assertMessageReceived<OrderBook>(SubscriptionTopic.OrderBook(marketId), assertions)

fun WsClient.assertOrderBookMessageReceived(marketId: MarketId, expected: OrderBook): OrderBook =
    assertMessageReceived<OrderBook>(SubscriptionTopic.OrderBook(marketId)) { msg ->
        assertEquals(expected, msg)
    }
