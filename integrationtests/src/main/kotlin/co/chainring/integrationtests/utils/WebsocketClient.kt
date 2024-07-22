package co.chainring.integrationtests.utils

import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.CreateOrderApiResponse
import co.chainring.apps.api.model.Market
import co.chainring.apps.api.model.Order
import co.chainring.apps.api.model.websocket.Balances
import co.chainring.apps.api.model.websocket.IncomingWSMessage
import co.chainring.apps.api.model.websocket.Limits
import co.chainring.apps.api.model.websocket.OrderBook
import co.chainring.apps.api.model.websocket.OrderCreated
import co.chainring.apps.api.model.websocket.OrderUpdated
import co.chainring.apps.api.model.websocket.Orders
import co.chainring.apps.api.model.websocket.OutgoingWSMessage
import co.chainring.apps.api.model.websocket.Prices
import co.chainring.apps.api.model.websocket.Publishable
import co.chainring.apps.api.model.websocket.SubscriptionTopic
import co.chainring.apps.api.model.websocket.Trades
import co.chainring.apps.api.model.websocket.TradesCreated
import co.chainring.apps.api.model.websocket.TradesUpdated
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OHLCDuration
import co.chainring.core.model.db.OrderEntity
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.SettlementStatus
import co.chainring.core.utils.fromFundamentalUnits
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.WebSocket
import org.http4k.client.WebsocketClient
import org.http4k.core.Uri
import org.http4k.websocket.Websocket
import org.http4k.websocket.WsClient
import org.http4k.websocket.WsConsumer
import org.http4k.websocket.WsMessage
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.toJavaDuration

fun WebsocketClient.blocking(auth: String?): WsClient =
    blocking(
        uri = Uri.of(apiServerRootUrl.replace("http:", "ws:").replace("https:", "wss:") + "/connect" + (auth?.let { "?auth=$auth" } ?: "")),
    )

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

fun WsClient.subscribeToLimits(marketId: MarketId) {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.Limits(marketId)))
}

fun WsClient.unsubscribe(topic: SubscriptionTopic) {
    send(IncomingWSMessage.Unsubscribe(topic))
}

fun WsClient.receivedDecoded(): Sequence<OutgoingWSMessage> =
    received().map {
        Json.decodeFromString<OutgoingWSMessage>(it.bodyString())
    }

// non blocking client/methods
fun WebsocketClient.nonBlocking(auth: String?, timeout: Duration = Duration.ZERO, onConnect: WsConsumer): Websocket =
    nonBlocking(
        uri = Uri.of(apiServerRootUrl.replace("http:", "ws:").replace("https:", "wss:") + "/connect" + (auth?.let { "?auth=$auth" } ?: "")),
        timeout = timeout.toJavaDuration(),
        onConnect = onConnect,
    )

fun Websocket.send(message: IncomingWSMessage) {
    send(WsMessage(Json.encodeToString(message)))
}

fun WebSocket.send(message: IncomingWSMessage) {
    send(Json.encodeToString(message))
}

fun Websocket.subscribeToOrderBook(marketId: MarketId) {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.OrderBook(marketId)))
}

fun Websocket.subscribe(topic: SubscriptionTopic) {
    send(IncomingWSMessage.Subscribe(topic))
}

fun WebSocket.subscribe(topic: SubscriptionTopic) {
    send(IncomingWSMessage.Subscribe(topic))
}

fun Websocket.subscribeToPrices(marketId: MarketId, duration: OHLCDuration = OHLCDuration.P5M) {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.Prices(marketId, duration)))
}

fun Websocket.subscribeToOrders() {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.Orders))
}

fun Websocket.subscribeToTrades() {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.Trades))
}

fun Websocket.subscribeToBalances() {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.Balances))
}

fun Websocket.subscribeToLimits(marketId: MarketId) {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.Limits(marketId)))
}

fun Websocket.unsubscribe(topic: SubscriptionTopic) {
    send(IncomingWSMessage.Unsubscribe(topic))
}

fun WebSocket.unsubscribe(topic: SubscriptionTopic) {
    send(IncomingWSMessage.Unsubscribe(topic))
}
inline fun <reified M : Publishable> WsClient.assertMessageReceived(topic: SubscriptionTopic, dataAssertions: (M) -> Unit = {}): M {
    val message = receivedDecoded().first() as OutgoingWSMessage.Publish
    assertEquals(topic, message.topic, "Unexpected message $message")

    assertIs<M>(message.data)
    val data = message.data as M
    dataAssertions(data)

    return data
}

fun WsClient.assertMessagesReceived(count: Int, assertions: (List<OutgoingWSMessage.Publish>) -> Unit = {}) {
    val messages = receivedDecoded().take(count).toList().map { it as OutgoingWSMessage.Publish }
    assertions(messages)
}

inline fun <reified M : Publishable> assertContainsMessage(messages: List<OutgoingWSMessage.Publish>, topic: SubscriptionTopic, assertions: (M) -> Unit = {}) {
    val msg = messages.find { it.topic == topic && it.data is M }
    assertNotNull(msg, "Expected to contain a message of type ${M::class.java.name}")
    assertions(msg!!.data as M)
}

fun WsClient.assertOrdersMessageReceived(assertions: (Orders) -> Unit = {}): Orders =
    assertMessageReceived<Orders>(SubscriptionTopic.Orders, assertions)

fun WsClient.assertOrderCreatedMessageReceived(assertions: (OrderCreated) -> Unit = {}): OrderCreated =
    assertMessageReceived<OrderCreated>(SubscriptionTopic.Orders, assertions)

fun WsClient.assertLimitOrderCreatedMessageReceived(expected: CreateOrderApiResponse): OrderCreated =
    assertMessageReceived<OrderCreated>(SubscriptionTopic.Orders) { msg ->
        assertIs<Order.Limit>(msg.order)
        assertEquals(expected.orderId, msg.order.id)
        assertEquals(expected.order.amount.fixedAmount(), msg.order.amount)
        assertEquals(expected.order.side, msg.order.side)
        assertEquals(expected.order.marketId, msg.order.marketId)
        assertEquals(0, (expected.order as CreateOrderApiRequest.Limit).price.compareTo((msg.order as Order.Limit).price))
        assertNotNull(msg.order.timing.createdAt)
        transaction {
            val orderEntity = OrderEntity[expected.orderId]
            assertEquals(BigInteger(expected.order.nonce, 16), BigInteger(orderEntity.nonce, 16))
            assertEquals(expected.order.signature.value, orderEntity.signature)
        }
    }

fun WsClient.assertMarketOrderCreatedMessageReceived(expected: CreateOrderApiResponse): OrderCreated =
    assertMessageReceived<OrderCreated>(SubscriptionTopic.Orders) { msg ->
        assertIs<Order.Market>(msg.order)
        assertEquals(expected.orderId, msg.order.id)
        assertEquals(expected.order.amount.fixedAmount(), msg.order.amount)
        assertEquals(expected.order.side, msg.order.side)
        assertEquals(expected.order.marketId, msg.order.marketId)
        assertNotNull(msg.order.timing.createdAt)
        transaction {
            val orderEntity = OrderEntity[expected.orderId]
            assertEquals(BigInteger(expected.order.nonce, 16), BigInteger(orderEntity.nonce, 16))
            assertEquals(expected.order.signature.value, orderEntity.signature)
        }
    }

fun WsClient.assertOrderUpdatedMessageReceived(assertions: (OrderUpdated) -> Unit = {}): OrderUpdated =
    assertMessageReceived<OrderUpdated>(SubscriptionTopic.Orders, assertions)

fun WsClient.assertTradesMessageReceived(assertions: (Trades) -> Unit = {}): Trades =
    assertMessageReceived<Trades>(SubscriptionTopic.Trades, assertions)

fun WsClient.assertTradesCreatedMessageReceived(assertions: (TradesCreated) -> Unit = {}): TradesCreated =
    assertMessageReceived<TradesCreated>(SubscriptionTopic.Trades, assertions)

data class ExpectedTrade(
    val orderId: OrderId,
    val marketId: MarketId,
    val orderSide: OrderSide,
    val price: BigDecimal,
    val amount: AssetAmount,
    val fee: AssetAmount,
    val settlementStatus: SettlementStatus,
) {
    constructor(order: CreateOrderApiResponse, price: BigDecimal, amount: AssetAmount, fee: AssetAmount, settlementStatus: SettlementStatus) :
        this(order.orderId, order.order.marketId, order.order.side, price, amount, fee, settlementStatus)
}

fun WsClient.assertTradesCreatedMessageReceived(expectedTrades: List<ExpectedTrade>): TradesCreated =
    assertMessageReceived<TradesCreated>(SubscriptionTopic.Trades) { msg ->
        assertEquals(expectedTrades.size, msg.trades.size)
        expectedTrades.zip(msg.trades).forEach { (expectedTrade, trade) ->
            assertEquals(expectedTrade.orderId, trade.orderId)
            assertEquals(expectedTrade.marketId, trade.marketId)
            assertEquals(expectedTrade.orderSide, trade.side)
            assertEquals(0, expectedTrade.price.compareTo(trade.price), "Price does not match Expected: ${expectedTrade.price} Actual: ${trade.price}")
            assertEquals(expectedTrade.amount.amount, trade.amount.fromFundamentalUnits(expectedTrade.amount.symbol.decimals), "Amount does not match")
            assertEquals(expectedTrade.fee.amount, trade.feeAmount.fromFundamentalUnits(expectedTrade.fee.symbol.decimals), "Fee does not match")
            assertEquals(expectedTrade.fee.symbol.name, trade.feeSymbol.value)
            assertEquals(expectedTrade.settlementStatus, trade.settlementStatus)
        }
    }

fun WsClient.assertTradesUpdatedMessageReceived(assertions: (TradesUpdated) -> Unit = {}): TradesUpdated =
    assertMessageReceived<TradesUpdated>(SubscriptionTopic.Trades, assertions)

fun assertContainsTradesUpdatedMessage(messages: List<OutgoingWSMessage.Publish>, assertions: (TradesUpdated) -> Unit = {}) =
    assertContainsMessage<TradesUpdated>(messages, SubscriptionTopic.Trades, assertions)

fun WsClient.assertBalancesMessageReceived(assertions: (Balances) -> Unit = {}): Balances =
    assertMessageReceived<Balances>(SubscriptionTopic.Balances, assertions)

fun WsClient.assertBalancesMessageReceived(expected: List<ExpectedBalance>): Balances =
    assertMessageReceived<Balances>(SubscriptionTopic.Balances) { msg ->
        assertBalances(expected, msg.balances)
    }

fun assertContainsBalancesMessage(messages: List<OutgoingWSMessage.Publish>, expected: List<ExpectedBalance>) =
    assertContainsMessage<Balances>(messages, SubscriptionTopic.Balances) { msg ->
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

fun WsClient.assertLimitsMessageReceived(market: Market, base: BigDecimal, quote: BigDecimal): Limits =
    assertMessageReceived<Limits>(SubscriptionTopic.Limits(market.id)) { msg ->
        assertEquals(base.setScale(market.baseDecimals), msg.base.fromFundamentalUnits(market.baseDecimals))
        assertEquals(quote.setScale(market.quoteDecimals), msg.quote.fromFundamentalUnits(market.quoteDecimals))
    }

fun WsClient.assertLimitsMessageReceived(market: Market, base: AssetAmount, quote: AssetAmount): Limits {
    assertEquals(market.baseSymbol.value, base.symbol.name)
    assertEquals(market.quoteSymbol.value, quote.symbol.name)
    return assertLimitsMessageReceived(market, base.amount, quote.amount)
}

fun assertContainsLimitsMessage(messages: List<OutgoingWSMessage.Publish>, market: Market, base: BigDecimal, quote: BigDecimal) =
    assertContainsMessage<Limits>(messages, SubscriptionTopic.Limits(market.id)) { msg ->
        assertEquals(base.setScale(market.baseDecimals), msg.base.fromFundamentalUnits(market.baseDecimals))
        assertEquals(quote.setScale(market.quoteDecimals), msg.quote.fromFundamentalUnits(market.quoteDecimals))
    }

fun assertContainsLimitsMessage(messages: List<OutgoingWSMessage.Publish>, market: Market, base: AssetAmount, quote: AssetAmount) =
    assertContainsLimitsMessage(messages, market, base.amount, quote.amount)
