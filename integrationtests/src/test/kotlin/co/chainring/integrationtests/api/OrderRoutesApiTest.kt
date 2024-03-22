package co.chainring.integrationtests.api

import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.OrderApiResponse
import co.chainring.apps.api.model.ReasonCode
import co.chainring.apps.api.model.UpdateOrderApiRequest
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.OrderStatus
import co.chainring.integrationtests.testutils.AbnormalApiResponseException
import co.chainring.integrationtests.testutils.ApiClient
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.testutils.apiError
import co.chainring.integrationtests.testutils.toFundamentalUnits
import kotlinx.datetime.Clock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.util.*
import kotlin.test.Test
import kotlin.test.assertIs

@ExtendWith(AppUnderTestRunner::class)
class OrderRoutesApiTest {
    @Test
    fun `CRUD order`() {
        val apiClient = ApiClient()
        val limitOrderApiRequest = CreateOrderApiRequest.Limit(
            nonce = UUID.randomUUID().toString(),
            marketId = MarketId("USDC/DAI"),
            side = OrderSide.Buy,
            amount = BigDecimal("1").toFundamentalUnits(18),
            price = BigDecimal("2").toFundamentalUnits(18),
        )
        val limitOrder = apiClient.createOrder(limitOrderApiRequest)

        // order created correctly
        assertIs<OrderApiResponse.Limit>(limitOrder)
        assertEquals(limitOrder.marketId, limitOrderApiRequest.marketId)
        assertEquals(limitOrder.side, limitOrderApiRequest.side)
        assertEquals(limitOrder.amount, limitOrderApiRequest.amount)
        assertEquals(limitOrder.price, limitOrderApiRequest.price)

        // order creation is idempotent
        assertEquals(limitOrder.id, apiClient.createOrder(limitOrderApiRequest).id)

        // update order
        val updatedOrder = apiClient.updateOrder(
            apiRequest = UpdateOrderApiRequest.Limit(
                id = limitOrder.id,
                amount = BigDecimal("3").toFundamentalUnits(18),
                price = BigDecimal("4").toFundamentalUnits(18),
            ),
        )
        assertIs<OrderApiResponse.Limit>(updatedOrder)
        assertEquals(BigDecimal("3").toFundamentalUnits(18), updatedOrder.amount)
        assertEquals(BigDecimal("4").toFundamentalUnits(18), updatedOrder.price)

        // cancel order is idempotent
        apiClient.cancelOrder(limitOrder.id)
        assertEquals(OrderStatus.Cancelled, apiClient.getOrder(limitOrder.id).status)
    }

    @Test
    fun `CRUD order error cases`() {
        val apiClient = ApiClient()

        // operation on non-existent order
        listOf(
            { apiClient.getOrder(OrderId.generate()) },
            {
                apiClient.updateOrder(
                    apiRequest = UpdateOrderApiRequest.Limit(
                        id = OrderId.generate(),
                        amount = BigDecimal("3").toFundamentalUnits(18),
                        price = BigDecimal("4").toFundamentalUnits(18),
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
                price = BigDecimal("2").toFundamentalUnits(18),
            ),
        )
        apiClient.cancelOrder(limitOrder.id)
        assertThrows<AbnormalApiResponseException> {
            apiClient.updateOrder(
                apiRequest = UpdateOrderApiRequest.Limit(
                    id = limitOrder.id,
                    amount = BigDecimal("3").toFundamentalUnits(18),
                    price = BigDecimal("4").toFundamentalUnits(18),
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
        val apiClient = ApiClient()
        apiClient.cancelOpenOrders()

        val limitOrderApiRequest = CreateOrderApiRequest.Limit(
            nonce = Clock.System.now().toEpochMilliseconds().toString(),
            marketId = MarketId("USDC/DAI"),
            side = OrderSide.Buy,
            amount = BigDecimal("1").toFundamentalUnits(18),
            price = BigDecimal("2").toFundamentalUnits(18),
        )
        repeat(times = 10) {
            apiClient.createOrder(limitOrderApiRequest.copy(nonce = UUID.randomUUID().toString()))
        }
        assertEquals(10, apiClient.listOrders().orders.count { it.status != OrderStatus.Cancelled })

        apiClient.cancelOpenOrders()
        assertTrue(apiClient.listOrders().orders.all { it.status == OrderStatus.Cancelled })
    }
}