package co.chainring.integrationtests.utils

import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.CreateOrderApiResponse
import co.chainring.apps.api.model.Market
import co.chainring.apps.api.model.Order
import co.chainring.apps.api.model.UpdateOrderApiResponse
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
import co.chainring.core.model.db.OrderEntity
import co.chainring.core.model.db.SettlementStatus
import co.chainring.core.utils.fromFundamentalUnits
import org.http4k.websocket.WsClient
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertIs

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
        assertEquals(expected.order.amount, msg.order.amount)
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

fun WsClient.assertLimitOrderUpdatedMessageReceived(expected: UpdateOrderApiResponse): OrderUpdated =
    assertMessageReceived<OrderUpdated>(SubscriptionTopic.Orders) { msg ->
        assertEquals(expected.order.orderId, msg.order.id)
        assertEquals(expected.order.amount, msg.order.amount)
        assertEquals(expected.order.side, msg.order.side)
        assertEquals(expected.order.marketId, msg.order.marketId)
        assertEquals(0, expected.order.price.compareTo((msg.order as Order.Limit).price))
        assertNotNull(msg.order.timing.createdAt)
        assertNotNull(msg.order.timing.updatedAt)
        transaction {
            val orderEntity = OrderEntity[expected.order.orderId]
            assertEquals(BigInteger(expected.order.nonce, 16), BigInteger(orderEntity.nonce, 16))
            assertEquals(expected.order.signature.value, orderEntity.signature)
        }
    }

fun WsClient.assertMarketOrderCreatedMessageReceived(expected: CreateOrderApiResponse): OrderCreated =
    assertMessageReceived<OrderCreated>(SubscriptionTopic.Orders) { msg ->
        assertIs<Order.Market>(msg.order)
        assertEquals(expected.orderId, msg.order.id)
        assertEquals(expected.order.amount, msg.order.amount)
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

fun WsClient.assertTradeCreatedMessageReceived(assertions: (TradeCreated) -> Unit = {}): TradeCreated =
    assertMessageReceived<TradeCreated>(SubscriptionTopic.Trades, assertions)

fun WsClient.assertTradeCreatedMessageReceived(order: CreateOrderApiResponse, price: BigDecimal, amount: AssetAmount, fee: AssetAmount, settlementStatus: SettlementStatus): TradeCreated =
    assertMessageReceived<TradeCreated>(SubscriptionTopic.Trades) { msg ->
        assertEquals(order.orderId, msg.trade.orderId)
        assertEquals(order.order.marketId, msg.trade.marketId)
        assertEquals(order.order.side, msg.trade.side)
        assertEquals(0, price.compareTo(msg.trade.price), "Price does not match Expected: $price Actual: ${msg.trade.price}")
        assertEquals(amount.amount, msg.trade.amount.fromFundamentalUnits(amount.symbol.decimals), "Amount does not match")
        assertEquals(fee.amount, msg.trade.feeAmount.fromFundamentalUnits(fee.symbol.decimals), "Fee does not match")
        assertEquals(fee.symbol.name, msg.trade.feeSymbol.value)
        assertEquals(settlementStatus, msg.trade.settlementStatus)
    }

fun WsClient.assertTradeCreatedMessageReceived(order: UpdateOrderApiResponse, price: BigDecimal, amount: AssetAmount, fee: AssetAmount, settlementStatus: SettlementStatus): TradeCreated =
    assertMessageReceived<TradeCreated>(SubscriptionTopic.Trades) { msg ->
        assertEquals(order.order.orderId, msg.trade.orderId)
        assertEquals(order.order.marketId, msg.trade.marketId)
        assertEquals(order.order.side, msg.trade.side)
        assertEquals(0, price.compareTo(msg.trade.price), "Price does not match")
        assertEquals(amount.amount, msg.trade.amount.fromFundamentalUnits(amount.symbol.decimals), "Amount does not match")
        assertEquals(fee.amount, msg.trade.feeAmount.fromFundamentalUnits(fee.symbol.decimals), "Fee does not match")
        assertEquals(fee.symbol.name, msg.trade.feeSymbol.value)
        assertEquals(settlementStatus, msg.trade.settlementStatus)
    }

fun WsClient.assertTradeUpdatedMessageReceived(assertions: (TradeUpdated) -> Unit = {}): TradeUpdated =
    assertMessageReceived<TradeUpdated>(SubscriptionTopic.Trades, assertions)

fun assertContainsTradeUpdatedMessage(messages: List<OutgoingWSMessage.Publish>, assertions: (TradeUpdated) -> Unit = {}) =
    assertContainsMessage<TradeUpdated>(messages, SubscriptionTopic.Trades, assertions)

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
