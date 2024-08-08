package xyz.funkybit.integrationtests.utils

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.WebSocket
import org.http4k.client.WebsocketClient
import org.http4k.core.Uri
import org.http4k.websocket.WsClient
import org.http4k.websocket.WsMessage
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import xyz.funkybit.apps.api.model.CreateOrderApiRequest
import xyz.funkybit.apps.api.model.CreateOrderApiResponse
import xyz.funkybit.apps.api.model.Market
import xyz.funkybit.apps.api.model.MarketLimits
import xyz.funkybit.apps.api.model.Order
import xyz.funkybit.apps.api.model.websocket.Balances
import xyz.funkybit.apps.api.model.websocket.IncomingWSMessage
import xyz.funkybit.apps.api.model.websocket.Limits
import xyz.funkybit.apps.api.model.websocket.MarketTradesCreated
import xyz.funkybit.apps.api.model.websocket.MyOrderCreated
import xyz.funkybit.apps.api.model.websocket.MyOrderUpdated
import xyz.funkybit.apps.api.model.websocket.MyOrders
import xyz.funkybit.apps.api.model.websocket.MyTrades
import xyz.funkybit.apps.api.model.websocket.MyTradesCreated
import xyz.funkybit.apps.api.model.websocket.MyTradesUpdated
import xyz.funkybit.apps.api.model.websocket.OrderBook
import xyz.funkybit.apps.api.model.websocket.OrderBookDiff
import xyz.funkybit.apps.api.model.websocket.OutgoingWSMessage
import xyz.funkybit.apps.api.model.websocket.Prices
import xyz.funkybit.apps.api.model.websocket.Publishable
import xyz.funkybit.apps.api.model.websocket.SubscriptionTopic
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.OHLCDuration
import xyz.funkybit.core.model.db.OrderEntity
import xyz.funkybit.core.model.db.OrderId
import xyz.funkybit.core.model.db.OrderSide
import xyz.funkybit.core.model.db.SettlementStatus
import xyz.funkybit.core.utils.fromFundamentalUnits
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertIs

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

fun WsClient.subscribeToIncrementalOrderBook(marketId: MarketId) {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.IncrementalOrderBook(marketId)))
}

fun WsClient.subscribeToPrices(marketId: MarketId, duration: OHLCDuration = OHLCDuration.P5M) {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.Prices(marketId, duration)))
}

fun WsClient.subscribeToMyOrders() {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.MyOrders))
}

fun WsClient.subscribeToMyTrades() {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.MyTrades))
}

fun WsClient.subscribeToMarketTrades(marketId: MarketId) {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.MarketTrades(marketId)))
}

fun WsClient.subscribeToBalances() {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.Balances))
}

fun WsClient.subscribeToLimits() {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.Limits))
}

fun WsClient.unsubscribe(topic: SubscriptionTopic) {
    send(IncomingWSMessage.Unsubscribe(topic))
}

fun WsClient.receivedDecoded(): Sequence<OutgoingWSMessage> =
    received().map {
        Json.decodeFromString<OutgoingWSMessage>(it.bodyString())
    }

fun WebSocket.send(message: IncomingWSMessage) {
    send(Json.encodeToString(message))
}

fun WebSocket.subscribe(topic: SubscriptionTopic) {
    send(IncomingWSMessage.Subscribe(topic))
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

fun WsClient.assertMyOrdersMessageReceived(assertions: (MyOrders) -> Unit = {}): MyOrders =
    assertMessageReceived<MyOrders>(SubscriptionTopic.MyOrders, assertions)

fun WsClient.assertMyOrderCreatedMessageReceived(assertions: (MyOrderCreated) -> Unit = {}): MyOrderCreated =
    assertMessageReceived<MyOrderCreated>(SubscriptionTopic.MyOrders, assertions)

fun WsClient.assertMyLimitOrderCreatedMessageReceived(expected: CreateOrderApiResponse): MyOrderCreated =
    assertMessageReceived<MyOrderCreated>(SubscriptionTopic.MyOrders) { msg ->
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

fun WsClient.assertMyMarketOrderCreatedMessageReceived(expected: CreateOrderApiResponse): MyOrderCreated =
    assertMessageReceived<MyOrderCreated>(SubscriptionTopic.MyOrders) { msg ->
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

fun WsClient.assertMyOrderUpdatedMessageReceived(assertions: (MyOrderUpdated) -> Unit = {}): MyOrderUpdated =
    assertMessageReceived<MyOrderUpdated>(SubscriptionTopic.MyOrders, assertions)

fun WsClient.assertMyTradesMessageReceived(assertions: (MyTrades) -> Unit = {}): MyTrades =
    assertMessageReceived<MyTrades>(SubscriptionTopic.MyTrades, assertions)

fun WsClient.assertMyTradesCreatedMessageReceived(assertions: (MyTradesCreated) -> Unit = {}): MyTradesCreated =
    assertMessageReceived<MyTradesCreated>(SubscriptionTopic.MyTrades, assertions)

fun WsClient.assertMarketTradesCreatedMessageReceived(marketId: MarketId, assertions: (MarketTradesCreated) -> Unit = {}): MarketTradesCreated =
    assertMessageReceived<MarketTradesCreated>(SubscriptionTopic.MarketTrades(marketId), assertions)

data class MyExpectedTrade(
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

fun WsClient.assertMyTradesCreatedMessageReceived(expectedTrades: List<MyExpectedTrade>): MyTradesCreated =
    assertMessageReceived<MyTradesCreated>(SubscriptionTopic.MyTrades) { msg ->
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

fun WsClient.assertMyTradesUpdatedMessageReceived(assertions: (MyTradesUpdated) -> Unit = {}): MyTradesUpdated =
    assertMessageReceived<MyTradesUpdated>(SubscriptionTopic.MyTrades, assertions)

fun assertContainsMyTradesUpdatedMessage(messages: List<OutgoingWSMessage.Publish>, assertions: (MyTradesUpdated) -> Unit = {}) =
    assertContainsMessage<MyTradesUpdated>(messages, SubscriptionTopic.MyTrades, assertions)

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

fun WsClient.assertOrderBookDiffMessageReceived(marketId: MarketId, expected: OrderBookDiff): OrderBookDiff =
    assertMessageReceived<OrderBookDiff>(SubscriptionTopic.IncrementalOrderBook(marketId)) { msg ->
        assertEquals(expected, msg)
    }

fun WsClient.assertLimitsMessageReceived(assertions: (Limits) -> Unit = {}): Limits =
    assertMessageReceived<Limits>(SubscriptionTopic.Limits, assertions)

fun WsClient.assertLimitsMessageReceived(expected: List<MarketLimits>): Limits =
    assertLimitsMessageReceived { msg ->
        assertEquals(expected, msg.limits)
    }

fun WsClient.assertLimitsMessageReceived(market: Market, base: BigDecimal, quote: BigDecimal): Limits =
    assertLimitsMessageReceived { msg ->
        msg.limits.first { it.marketId == market.id }.also { marketLimits ->
            assertEquals(base.setScale(market.baseDecimals), marketLimits.base.fromFundamentalUnits(market.baseDecimals))
            assertEquals(quote.setScale(market.quoteDecimals), marketLimits.quote.fromFundamentalUnits(market.quoteDecimals))
        }
    }

fun WsClient.assertLimitsMessageReceived(market: Market, base: AssetAmount, quote: AssetAmount): Limits {
    assertEquals(market.baseSymbol.value, base.symbol.name)
    assertEquals(market.quoteSymbol.value, quote.symbol.name)
    return assertLimitsMessageReceived(market, base.amount, quote.amount)
}

fun assertContainsLimitsMessage(messages: List<OutgoingWSMessage.Publish>, market: Market, base: BigDecimal, quote: BigDecimal) =
    assertContainsMessage<Limits>(messages, SubscriptionTopic.Limits) { msg ->
        msg.limits.first { it.marketId == market.id }.also { marketLimits ->
            assertEquals(base.setScale(market.baseDecimals), marketLimits.base.fromFundamentalUnits(market.baseDecimals))
            assertEquals(quote.setScale(market.quoteDecimals), marketLimits.quote.fromFundamentalUnits(market.quoteDecimals))
        }
    }

fun assertContainsLimitsMessage(messages: List<OutgoingWSMessage.Publish>, market: Market, base: AssetAmount, quote: AssetAmount) =
    assertContainsLimitsMessage(messages, market, base.amount, quote.amount)
