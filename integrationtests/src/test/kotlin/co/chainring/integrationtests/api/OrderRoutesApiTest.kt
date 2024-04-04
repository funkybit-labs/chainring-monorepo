package co.chainring.integrationtests.api

import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.Order
import co.chainring.apps.api.model.ReasonCode
import co.chainring.apps.api.model.UpdateOrderApiRequest
import co.chainring.apps.api.model.websocket.OrderCreated
import co.chainring.apps.api.model.websocket.OrderUpdated
import co.chainring.apps.api.model.websocket.Orders
import co.chainring.apps.api.model.websocket.OutgoingWSMessage
import co.chainring.apps.api.model.websocket.SubscriptionTopic
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.OrderStatus
import co.chainring.core.utils.toFundamentalUnits
import co.chainring.integrationtests.testutils.AbnormalApiResponseException
import co.chainring.integrationtests.testutils.ApiClient
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.testutils.apiError
import co.chainring.integrationtests.testutils.blocking
import co.chainring.integrationtests.testutils.receivedDecoded
import co.chainring.integrationtests.testutils.subscribe
import co.chainring.integrationtests.testutils.waitForMessage
import kotlinx.datetime.Clock
import org.http4k.client.WebsocketClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

@ExtendWith(AppUnderTestRunner::class)
class OrderRoutesApiTest {
    @Test
    fun `CRUD order`() {
        val apiClient = ApiClient.initWallet()

        var wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribe(SubscriptionTopic.Orders)
        val initialOrdersOverWs = wsClient.waitForMessage<Orders>().orders

        val limitOrderApiRequest = CreateOrderApiRequest.Limit(
            nonce = UUID.randomUUID().toString(),
            marketId = MarketId("USDC/DAI"),
            side = OrderSide.Buy,
            amount = BigDecimal("1").toFundamentalUnits(18),
            price = BigDecimal("2"),
        )
        val limitOrder = apiClient.createOrder(limitOrderApiRequest)

        // order created correctly
        assertIs<Order.Limit>(limitOrder)
        assertEquals(limitOrder.marketId, limitOrderApiRequest.marketId)
        assertEquals(limitOrder.side, limitOrderApiRequest.side)
        assertEquals(limitOrder.amount, limitOrderApiRequest.amount)
        assertEquals(0, limitOrder.price.compareTo(limitOrderApiRequest.price))

        // order creation is idempotent
        assertEquals(limitOrder.id, apiClient.createOrder(limitOrderApiRequest).id)

        // client is notified over websocket
        wsClient.waitForMessage<OrderCreated>().also { event ->
            assertEquals(limitOrder, event.order)
        }
        wsClient.close()

        // check that order is included in the orders list sent via websocket
        wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribe(SubscriptionTopic.Orders)
        assertEquals(listOf(limitOrder) + initialOrdersOverWs, wsClient.waitForMessage<Orders>().orders)

        // update order
        val updatedOrder = apiClient.updateOrder(
            apiRequest = UpdateOrderApiRequest.Limit(
                id = limitOrder.id,
                amount = BigDecimal("3").toFundamentalUnits(18),
                price = BigDecimal("4"),
            ),
        )
        assertIs<Order.Limit>(updatedOrder)
        assertEquals(BigDecimal("3").toFundamentalUnits(18), updatedOrder.amount)
        assertEquals(0, BigDecimal("4").compareTo(updatedOrder.price))
        wsClient.waitForMessage<OrderUpdated>().also { event ->
            assertEquals(updatedOrder, event.order)
        }

        // cancel order is idempotent
        apiClient.cancelOrder(limitOrder.id)
        val cancelledOrder = apiClient.getOrder(limitOrder.id)
        assertEquals(OrderStatus.Cancelled, cancelledOrder.status)
        wsClient.waitForMessage<OrderUpdated>().also { event ->
            assertEquals(cancelledOrder, event.order)
        }

        wsClient.close()
    }

    @Test
    fun `CRUD order error cases`() {
        val apiClient = ApiClient.initWallet()

        // operation on non-existent order
        listOf(
            { apiClient.getOrder(OrderId.generate()) },
            {
                apiClient.updateOrder(
                    apiRequest = UpdateOrderApiRequest.Limit(
                        id = OrderId.generate(),
                        amount = BigDecimal("3").toFundamentalUnits(18),
                        price = BigDecimal("4"),
                    ),
                )
            },
            { apiClient.cancelOrder(OrderId.generate()) },
        ).forEach { op ->
            assertThrows<AbnormalApiResponseException> {
                op()
            }.also {
                assertEquals(
                    ApiError(ReasonCode.OrderNotFound, "Requested order does not exist"),
                    it.response.apiError(),
                )
            }
        }

        // try update cancelled order
        val limitOrder = apiClient.createOrder(
            CreateOrderApiRequest.Limit(
                nonce = Clock.System.now().toEpochMilliseconds().toString(),
                marketId = MarketId("USDC/DAI"),
                side = OrderSide.Buy,
                amount = BigDecimal("1").toFundamentalUnits(18),
                price = BigDecimal("2"),
            ),
        )
        apiClient.cancelOrder(limitOrder.id)
        assertThrows<AbnormalApiResponseException> {
            apiClient.updateOrder(
                apiRequest = UpdateOrderApiRequest.Limit(
                    id = limitOrder.id,
                    amount = BigDecimal("3").toFundamentalUnits(18),
                    price = BigDecimal("4"),
                ),
            )
        }.also {
            assertEquals(
                ApiError(ReasonCode.OrderIsClosed, "Order is already finalized"),
                it.response.apiError(),
            )
        }
    }

    @Test
    fun `list and cancel all open orders`() {
        val apiClient = ApiClient.initWallet()
        apiClient.cancelOpenOrders()

        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribe(SubscriptionTopic.Orders)
        val initialOrdersOverWs = wsClient.waitForMessage<Orders>().orders

        val limitOrderApiRequest = CreateOrderApiRequest.Limit(
            nonce = Clock.System.now().toEpochMilliseconds().toString(),
            marketId = MarketId("USDC/DAI"),
            side = OrderSide.Buy,
            amount = BigDecimal("1").toFundamentalUnits(18),
            price = BigDecimal("2"),
        )
        repeat(times = 10) {
            apiClient.createOrder(limitOrderApiRequest.copy(nonce = UUID.randomUUID().toString()))
        }
        assertEquals(10, apiClient.listOrders().orders.count { it.status != OrderStatus.Cancelled })
        wsClient.receivedDecoded().take(10).forEach {
            assertIs<OrderCreated>((it as OutgoingWSMessage.Publish).data)
        }

        apiClient.cancelOpenOrders()
        assertTrue(apiClient.listOrders().orders.all { it.status == OrderStatus.Cancelled })

        wsClient.waitForMessage<Orders>().also { event ->
            assertNotEquals(event.orders, initialOrdersOverWs)
            assertTrue(event.orders.all { it.status == OrderStatus.Cancelled })
        }
        wsClient.close()
    }
}
