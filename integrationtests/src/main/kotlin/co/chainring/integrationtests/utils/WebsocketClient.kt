package co.chainring.integrationtests.utils

import co.chainring.apps.api.model.websocket.Balances
import co.chainring.apps.api.model.websocket.Limits
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
import co.chainring.core.client.ws.receivedDecoded
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OHLCDuration
import org.http4k.websocket.WsClient
import org.junit.jupiter.api.Assertions.assertEquals
import kotlin.test.assertIs

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

fun WsClient.assertBalancesMessageReceived(expected: List<ExpectedBalance>): Balances =
    assertMessageReceived<Balances>(SubscriptionTopic.Balances) { msg ->
        assertBalances(expected, msg.balances)
    }

fun WsClient.assertPricesMessageReceived(marketId: MarketId, duration: OHLCDuration = OHLCDuration.P5M, assertions: (Prices) -> Unit = {}): Prices =
    assertMessageReceived<Prices>(SubscriptionTopic.Prices(marketId, duration), assertions)

fun WsClient.assertOrderBookMessageReceived(marketId: MarketId, assertions: (OrderBook) -> Unit = {}): OrderBook =
    assertMessageReceived<OrderBook>(SubscriptionTopic.OrderBook(marketId), assertions)

fun WsClient.assertOrderBookMessageReceived(marketId: MarketId, expected: OrderBook): OrderBook =
    assertMessageReceived<OrderBook>(SubscriptionTopic.OrderBook(marketId)) { msg ->
        assertEquals(expected, msg)
    }

fun WsClient.assertLimitsMessageReceived(marketId: MarketId, assertions: (Limits) -> Unit = {}): Limits =
    assertMessageReceived<Limits>(SubscriptionTopic.Limits(marketId), assertions)
