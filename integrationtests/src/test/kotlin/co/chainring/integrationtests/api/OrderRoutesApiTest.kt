package co.chainring.integrationtests.api

import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.BatchOrdersApiRequest
import co.chainring.apps.api.model.CancelUpdateOrderApiRequest
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.Order
import co.chainring.apps.api.model.ReasonCode
import co.chainring.apps.api.model.UpdateOrderApiRequest
import co.chainring.apps.api.model.websocket.OrderCreated
import co.chainring.apps.api.model.websocket.OrderUpdated
import co.chainring.apps.api.model.websocket.Orders
import co.chainring.apps.api.model.websocket.OutgoingWSMessage
import co.chainring.apps.api.model.websocket.SubscriptionTopic
import co.chainring.apps.api.model.websocket.TradeCreated
import co.chainring.apps.api.model.websocket.TradeUpdated
import co.chainring.apps.api.model.websocket.Trades
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.db.ExecutionRole
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderExecutionEntity
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.OrderStatus
import co.chainring.core.model.db.SettlementStatus
import co.chainring.core.model.db.TradeEntity
import co.chainring.core.model.db.TradeId
import co.chainring.core.model.db.TradeTable
import co.chainring.core.utils.fromFundamentalUnits
import co.chainring.core.utils.generateHexString
import co.chainring.core.utils.toFundamentalUnits
import co.chainring.integrationtests.testutils.AbnormalApiResponseException
import co.chainring.integrationtests.testutils.ApiClient
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.testutils.BalanceHelper
import co.chainring.integrationtests.testutils.ExpectedBalance
import co.chainring.integrationtests.testutils.Faucet
import co.chainring.integrationtests.testutils.Wallet
import co.chainring.integrationtests.testutils.apiError
import co.chainring.integrationtests.testutils.blocking
import co.chainring.integrationtests.testutils.receivedDecoded
import co.chainring.integrationtests.testutils.subscribe
import co.chainring.integrationtests.testutils.waitForMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import org.awaitility.kotlin.await
import org.http4k.client.WebsocketClient
import org.http4k.websocket.WsClient
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertIs

@ExtendWith(AppUnderTestRunner::class)
class OrderRoutesApiTest {
    private val logger = KotlinLogging.logger {}

    @Test
    fun `CRUD order`() {
        val apiClient = ApiClient()
        val wallet = Wallet(apiClient)

        var wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribe(SubscriptionTopic.Orders)
        val initialOrdersOverWs = wsClient.waitForMessage().let { message ->
            assertEquals(SubscriptionTopic.Orders, message.topic)
            message.data.let { data ->
                assertIs<Orders>(data)
                data.orders
            }
        }

        Faucet.fund(wallet.address)
        wallet.mintERC20("DAI", wallet.formatAmount("14", "DAI"))
        val amountToDeposit = wallet.formatAmount("14", "DAI")
        BalanceHelper.waitForAndVerifyBalanceChange(apiClient, listOf(ExpectedBalance("DAI", amountToDeposit, amountToDeposit))) {
            deposit(apiClient, wallet, "DAI", amountToDeposit)
        }

        val limitOrderApiRequest = CreateOrderApiRequest.Limit(
            nonce = generateHexString(32),
            marketId = MarketId("USDC/DAI"),
            side = OrderSide.Buy,
            amount = wallet.formatAmount("1", "USDC"),
            price = BigDecimal("2"),
            signature = EvmSignature.emptySignature(),
        ).let {
            wallet.signOrder(it)
        }
        val limitOrder = apiClient.createOrder(limitOrderApiRequest)

        // order created correctly
        assertIs<Order.Limit>(limitOrder)
        assertEquals(limitOrder.marketId, limitOrderApiRequest.marketId)
        assertEquals(limitOrder.side, limitOrderApiRequest.side)
        assertEquals(limitOrder.amount, limitOrderApiRequest.amount)
        assertEquals(0, limitOrder.price.compareTo(limitOrderApiRequest.price))
        assertEquals(OrderStatus.Open, limitOrder.status)

        // order creation is idempotent
        assertEquals(limitOrder.id, apiClient.createOrder(limitOrderApiRequest).id)

        // client is notified over websocket
        wsClient.waitForMessage().also { message ->
            assertEquals(SubscriptionTopic.Orders, message.topic)
            message.data.let { data ->
                assertIs<OrderCreated>(data)
                assertEquals(limitOrder, data.order)
            }
        }
        wsClient.close()

        // check that order is included in the orders list sent via websocket
        wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribe(SubscriptionTopic.Orders)
        assertEquals(
            listOf(limitOrder) + initialOrdersOverWs,
            wsClient.waitForMessage().let { message ->
                assertEquals(SubscriptionTopic.Orders, message.topic)
                message.data.let { data ->
                    assertIs<Orders>(data)
                    data.orders
                }
            },
        )

        // update order
        val updatedOrder = apiClient.updateOrder(
            apiRequest = UpdateOrderApiRequest.Limit(
                orderId = limitOrder.id,
                amount = wallet.formatAmount("3", "USDC"),
                price = BigDecimal("2.01"),
            ),
        )
        assertIs<Order.Limit>(updatedOrder)
        assertEquals(wallet.formatAmount("3", "USDC"), updatedOrder.amount)
        assertEquals(0, BigDecimal("2.01").compareTo(updatedOrder.price))
        wsClient.waitForMessage().also { message ->
            assertEquals(SubscriptionTopic.Orders, message.topic)
            message.data.let { data ->
                assertIs<OrderUpdated>(data)
                assertEquals(updatedOrder, data.order)
            }
        }

        // cancel order is idempotent
        apiClient.cancelOrder(limitOrder.id)
        val cancelledOrder = apiClient.getOrder(limitOrder.id)
        wsClient.waitForMessage().also { message ->
            assertEquals(SubscriptionTopic.Orders, message.topic)
            message.data.let { data ->
                assertIs<OrderUpdated>(data)
                assertEquals(cancelledOrder.id, data.order.id)
                assertEquals(OrderStatus.Cancelled, data.order.status)
            }
        }
        assertEquals(OrderStatus.Cancelled, apiClient.getOrder(limitOrder.id).status)

        wsClient.close()
    }

    @Test
    fun `CRUD order error cases`() {
        val apiClient = ApiClient()
        val wallet = Wallet(apiClient)

        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribe(SubscriptionTopic.Orders)
        wsClient.waitForMessage().let { message ->
            assertEquals(SubscriptionTopic.Orders, message.topic)
            assertIs<Orders>(message.data)
        }

        Faucet.fund(wallet.address)
        val amountToDeposit = wallet.formatAmount("20", "DAI")
        wallet.mintERC20("DAI", amountToDeposit)
        BalanceHelper.waitForAndVerifyBalanceChange(apiClient, listOf(ExpectedBalance("DAI", amountToDeposit, amountToDeposit))) {
            deposit(apiClient, wallet, "DAI", amountToDeposit)
        }

        // operation on non-existent order
        listOf(
            { apiClient.getOrder(OrderId.generate()) },
            {
                apiClient.updateOrder(
                    apiRequest = UpdateOrderApiRequest.Limit(
                        orderId = OrderId.generate(),
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

        // try to submit an order that crosses the market
        val limitOrder = apiClient.createOrder(
            CreateOrderApiRequest.Limit(
                nonce = generateHexString(32),
                marketId = MarketId("USDC/DAI"),
                side = OrderSide.Buy,
                amount = wallet.formatAmount("1", "USDC"),
                price = BigDecimal("3"),
                signature = EvmSignature.emptySignature(),
            ).let {
                wallet.signOrder(it)
            },
        )

        wsClient.waitForMessage().also { message ->
            assertEquals(SubscriptionTopic.Orders, message.topic)
            message.data.let { data ->
                assertIs<OrderCreated>(data)
                assertEquals(limitOrder, data.order)
            }
        }

        wsClient.waitForMessage().also { message ->
            assertEquals(SubscriptionTopic.Orders, message.topic)
            message.data.let { data ->
                assertIs<OrderUpdated>(data)
                assertEquals(limitOrder.id, data.order.id)
                assertEquals(OrderStatus.CrossesMarket, data.order.status)
            }
        }

        // try creating a limit order not a multiple of tick size
        assertThrows<AbnormalApiResponseException> {
            apiClient.createOrder(
                CreateOrderApiRequest.Limit(
                    nonce = generateHexString(32),
                    marketId = MarketId("USDC/DAI"),
                    side = OrderSide.Buy,
                    amount = wallet.formatAmount("1", "USDC"),
                    price = BigDecimal("2.015"),
                    signature = EvmSignature.emptySignature(),
                ).let {
                    wallet.signOrder(it)
                },
            )
        }.also {
            assertEquals(
                ApiError(ReasonCode.ProcessingError, "Order price is not a multiple of tick size"),
                it.response.apiError(),
            )
        }

        val limitOrder2 = apiClient.createOrder(
            CreateOrderApiRequest.Limit(
                nonce = generateHexString(32),
                marketId = MarketId("USDC/DAI"),
                side = OrderSide.Buy,
                amount = wallet.formatAmount("1", "USDC"),
                price = BigDecimal("2"),
                signature = EvmSignature.emptySignature(),
            ).let {
                wallet.signOrder(it)
            },
        )

        // try updating the price to something not a tick size
        assertThrows<AbnormalApiResponseException> {
            apiClient.updateOrder(
                apiRequest = UpdateOrderApiRequest.Limit(
                    orderId = limitOrder2.id,
                    amount = wallet.formatAmount("1", "USDC"),
                    price = BigDecimal("2.015"),
                ),
            )
        }.also {
            assertEquals(
                ApiError(ReasonCode.ProcessingError, "Order price is not a multiple of tick size"),
                it.response.apiError(),
            )
        }

        // try updating and cancelling an order not created by this wallet
        val apiClient2 = ApiClient()
        assertThrows<AbnormalApiResponseException> {
            apiClient2.updateOrder(
                apiRequest = UpdateOrderApiRequest.Limit(
                    orderId = limitOrder2.id,
                    amount = wallet.formatAmount("1", "USDC"),
                    price = BigDecimal("2.01"),
                ),
            )
        }.also {
            assertEquals(
                ApiError(ReasonCode.ProcessingError, "Order not created with this wallet"),
                it.response.apiError(),
            )
        }
        assertThrows<AbnormalApiResponseException> {
            apiClient2.cancelOrder(limitOrder2.id)
        }.also {
            assertEquals(
                ApiError(ReasonCode.ProcessingError, "Order not created with this wallet"),
                it.response.apiError(),
            )
        }

        // try update cancelled order
        apiClient.cancelOrder(limitOrder2.id)
        assertThrows<AbnormalApiResponseException> {
            apiClient.updateOrder(
                apiRequest = UpdateOrderApiRequest.Limit(
                    orderId = limitOrder2.id,
                    amount = wallet.formatAmount("3", "USDC"),
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
        val apiClient = ApiClient()
        val wallet = Wallet(apiClient)
        apiClient.cancelOpenOrders()

        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribe(SubscriptionTopic.Orders)
        val initialOrdersOverWs = wsClient.waitForMessage().let { message ->
            assertEquals(SubscriptionTopic.Orders, message.topic)
            message.data.let { data ->
                assertIs<Orders>(data)
                data.orders
            }
        }

        Faucet.fund(wallet.address)
        val amountToDeposit = wallet.formatAmount("30", "DAI")
        wallet.mintERC20("DAI", amountToDeposit)
        BalanceHelper.waitForAndVerifyBalanceChange(apiClient, listOf(ExpectedBalance("DAI", amountToDeposit, amountToDeposit))) {
            deposit(apiClient, wallet, "DAI", amountToDeposit)
        }

        val limitOrderApiRequest = CreateOrderApiRequest.Limit(
            nonce = generateHexString(32),
            marketId = MarketId("USDC/DAI"),
            side = OrderSide.Buy,
            amount = wallet.formatAmount("1", "USDC"),
            price = BigDecimal("2"),
            signature = EvmSignature.emptySignature(),
        ).let {
            wallet.signOrder(it)
        }
        repeat(times = 10) {
            apiClient.createOrder(wallet.signOrder(limitOrderApiRequest.copy(nonce = generateHexString(32))))
        }
        assertEquals(10, apiClient.listOrders().orders.count { it.status != OrderStatus.Cancelled })
        wsClient.receivedDecoded().take(10).forEach {
            assertIs<OrderCreated>((it as OutgoingWSMessage.Publish).data)
        }

        apiClient.cancelOpenOrders()

        wsClient.waitForMessage().also { message ->
            assertEquals(SubscriptionTopic.Orders, message.topic)
            message.data.let { data ->
                assertIs<Orders>(data)
                assertNotEquals(initialOrdersOverWs, data.orders)
                assertTrue(data.orders.all { it.status == OrderStatus.Cancelled })
            }
        }

        assertTrue(apiClient.listOrders().orders.all { it.status == OrderStatus.Cancelled })

        wsClient.close()
    }

    @Test
    fun `order execution`() {
        val (takerApiClient, takerWallet, takerWsClient) =
            setupTrader("0.5", null, "ETH", "2")

        val (makerApiClient, makerWallet, makerWsClient) =
            setupTrader("0.5", "0.2", "ETH", "2")

        // starting onchain balances
        val makerStartingBTCBalance = makerWallet.getExchangeNativeBalance()
        val makerStartingETHBalance = makerWallet.getExchangeERC20Balance("ETH")
        val takerStartingBTCBalance = takerWallet.getExchangeNativeBalance()
        val takerStartingETHBalance = takerWallet.getExchangeERC20Balance("ETH")

        // place an order and see that it gets accepted
        val limitBuyOrder = makerApiClient.createOrder(
            CreateOrderApiRequest.Limit(
                nonce = generateHexString(32),
                marketId = MarketId("BTC/ETH"),
                side = OrderSide.Buy,
                amount = makerWallet.formatAmount("0.00013345", "BTC"),
                price = BigDecimal("17.45"),
                signature = EvmSignature.emptySignature(),
            ).let {
                makerWallet.signOrder(it)
            },
        )
        assertIs<Order.Limit>(limitBuyOrder)
        assertEquals(limitBuyOrder.status, OrderStatus.Open)
        makerWsClient.waitForMessage().also { message ->
            assertEquals(SubscriptionTopic.Orders, message.topic)
            message.data.let { data ->
                assertIs<OrderCreated>(data)
                assertEquals(limitBuyOrder, data.order)
            }
        }

        val updatedLimitBuyOrder = makerApiClient.updateOrder(
            UpdateOrderApiRequest.Limit(
                orderId = limitBuyOrder.id,
                amount = makerWallet.formatAmount("0.00012345", "BTC"),
                price = BigDecimal("17.50"),
            ),
        )
        assertIs<Order.Limit>(updatedLimitBuyOrder)

        makerWsClient.waitForMessage().also { message ->
            assertEquals(SubscriptionTopic.Orders, message.topic)
            message.data.let { data ->
                assertIs<OrderUpdated>(data)
                assertEquals(updatedLimitBuyOrder, data.order)
            }
        }

        // place a sell order
        val limitSellOrder = makerApiClient.createOrder(
            CreateOrderApiRequest.Limit(
                nonce = generateHexString(32),
                marketId = MarketId("BTC/ETH"),
                side = OrderSide.Sell,
                amount = makerWallet.formatAmount("0.00154321", "BTC"),
                price = BigDecimal("17.600"),
                signature = EvmSignature.emptySignature(),
            ).let {
                makerWallet.signOrder(it)
            },
        )
        assertIs<Order.Limit>(limitSellOrder)
        assertEquals(limitSellOrder.status, OrderStatus.Open)
        makerWsClient.waitForMessage().also { message ->
            assertEquals(SubscriptionTopic.Orders, message.topic)
            message.data.let { data ->
                assertIs<OrderCreated>(data)
                assertEquals(limitSellOrder, data.order)
            }
        }

        // update amount and price of the sell
        val updatedLimitSellOrder = makerApiClient.updateOrder(
            UpdateOrderApiRequest.Limit(
                orderId = limitSellOrder.id,
                amount = makerWallet.formatAmount("0.00054321", "BTC"),
                price = BigDecimal("17.550"),
            ),
        )
        assertIs<Order.Limit>(updatedLimitSellOrder)

        makerWsClient.waitForMessage().also { message ->
            assertEquals(SubscriptionTopic.Orders, message.topic)
            message.data.let { data ->
                assertIs<OrderUpdated>(data)
                assertEquals(updatedLimitSellOrder, data.order)
            }
        }

        // place a buy order and see it gets executed
        val marketBuyOrder = takerApiClient.createOrder(
            CreateOrderApiRequest.Market(
                nonce = generateHexString(32),
                marketId = MarketId("BTC/ETH"),
                side = OrderSide.Buy,
                amount = takerWallet.formatAmount("0.00043210", "BTC"),
                signature = EvmSignature.emptySignature(),
            ).let {
                takerWallet.signOrder(it)
            },
        )

        assertIs<Order.Market>(marketBuyOrder)
        assertEquals(marketBuyOrder.status, OrderStatus.Open)

        takerWsClient.apply {
            waitForMessage().also { message ->
                assertEquals(SubscriptionTopic.Orders, message.topic)
                message.data.let { data ->
                    assertIs<OrderCreated>(data)
                    assertEquals(marketBuyOrder, data.order)
                }
            }
            waitForMessage().also { message ->
                assertEquals(SubscriptionTopic.Trades, message.topic)
                message.data.let { data ->
                    assertIs<TradeCreated>(data)
                    assertEquals(marketBuyOrder.id, data.trade.orderId)
                    assertEquals(marketBuyOrder.marketId, data.trade.marketId)
                    assertEquals(marketBuyOrder.side, data.trade.side)
                    assertEquals(updatedLimitSellOrder.price, data.trade.price)
                    assertEquals(marketBuyOrder.amount, data.trade.amount)
                    assertEquals(SettlementStatus.Pending, data.trade.settlementStatus)
                }
            }
            waitForMessage().also { message ->
                assertEquals(SubscriptionTopic.Orders, message.topic)
                message.data.let { data ->
                    assertIs<OrderUpdated>(data)
                    assertEquals(1, data.order.executions.size)
                    assertEquals(data.order.executions[0].amount, takerWallet.formatAmount("0.00043210", "BTC"))
                    assertEquals(0, data.order.executions[0].price.compareTo(BigDecimal("17.550")))
                    assertEquals(data.order.executions[0].role, ExecutionRole.Taker)
                }
            }
        }

        makerWsClient.apply {
            waitForMessage().also { message ->
                assertEquals(SubscriptionTopic.Trades, message.topic)
                message.data.let { data ->
                    assertIs<TradeCreated>(data)
                    assertEquals(updatedLimitSellOrder.id, data.trade.orderId)
                    assertEquals(updatedLimitSellOrder.marketId, data.trade.marketId)
                    assertEquals(updatedLimitSellOrder.side, data.trade.side)
                    assertEquals(updatedLimitSellOrder.price, data.trade.price)
                    assertEquals(takerWallet.formatAmount("0.00043210", "BTC"), data.trade.amount)
                    assertEquals(SettlementStatus.Pending, data.trade.settlementStatus)
                }
            }
            waitForMessage().also { message ->
                assertEquals(SubscriptionTopic.Orders, message.topic)
                message.data.let { data ->
                    assertIs<OrderUpdated>(data)
                    assertEquals(OrderStatus.Partial, data.order.status)
                    assertEquals(1, data.order.executions.size)
                    assertEquals(data.order.executions[0].amount, makerWallet.formatAmount("0.00043210", "BTC"))
                    assertEquals(0, data.order.executions[0].price.compareTo(BigDecimal("17.550")))
                    assertEquals(ExecutionRole.Maker, data.order.executions[0].role)
                }
            }
        }

        val trade = getTradesForOrders(listOf(marketBuyOrder.id)).first()

        assertEquals(trade.amount, takerWallet.formatAmount("0.00043210", "BTC"))
        assertEquals(0, trade.price.compareTo(BigDecimal("17.550")))

        waitForSettlementToFinish(listOf(trade.id.value))
        takerWsClient.waitForMessage().also { message ->
            assertEquals(SubscriptionTopic.Trades, message.topic)
            message.data.let { data ->
                assertIs<TradeUpdated>(data)
                assertEquals(marketBuyOrder.id, data.trade.orderId)
                assertEquals(SettlementStatus.Completed, data.trade.settlementStatus)
            }
        }
        makerWsClient.waitForMessage().also { message ->
            assertEquals(SubscriptionTopic.Trades, message.topic)
            message.data.let { data ->
                assertIs<TradeUpdated>(data)
                assertEquals(updatedLimitSellOrder.id, data.trade.orderId)
                assertEquals(SettlementStatus.Completed, data.trade.settlementStatus)
            }
        }

        val baseQuantity = takerWallet.formatAmount("0.00043210", "BTC")
        val notional = (trade.price * trade.amount.fromFundamentalUnits(takerWallet.decimals("BTC"))).toFundamentalUnits(takerWallet.decimals("ETH"))
        BalanceHelper.verifyBalances(
            makerApiClient,
            listOf(
                ExpectedBalance("BTC", makerStartingBTCBalance - baseQuantity, makerStartingBTCBalance - baseQuantity),
                ExpectedBalance("ETH", makerStartingETHBalance + notional, makerStartingETHBalance + notional),
            ),
        )
        BalanceHelper.verifyBalances(
            takerApiClient,
            listOf(
                ExpectedBalance("BTC", takerStartingBTCBalance + baseQuantity, takerStartingBTCBalance + baseQuantity),
                ExpectedBalance("ETH", takerStartingETHBalance - notional, takerStartingETHBalance - notional),
            ),
        )

        // place a sell order and see it gets executed
        val marketSellOrder = takerApiClient.createOrder(
            CreateOrderApiRequest.Market(
                nonce = generateHexString(32),
                marketId = MarketId("BTC/ETH"),
                side = OrderSide.Sell,
                amount = takerWallet.formatAmount("0.00012346", "BTC"),
                signature = EvmSignature.emptySignature(),
            ).let {
                takerWallet.signOrder(it)
            },
        )

        assertIs<Order.Market>(marketSellOrder)
        assertEquals(marketSellOrder.status, OrderStatus.Open)

        takerWsClient.apply {
            waitForMessage().also { message ->
                assertEquals(SubscriptionTopic.Orders, message.topic)
                message.data.let { data ->
                    assertIs<OrderCreated>(data)
                    assertEquals(marketSellOrder, data.order)
                }
            }
            waitForMessage().also { message ->
                assertEquals(SubscriptionTopic.Trades, message.topic)
                message.data.let { data ->
                    assertIs<TradeCreated>(data)
                    assertEquals(marketSellOrder.id, data.trade.orderId)
                    assertEquals(marketSellOrder.marketId, data.trade.marketId)
                    assertEquals(marketSellOrder.side, data.trade.side)
                    assertEquals(0, data.trade.price.compareTo(BigDecimal("17.500")))
                    assertEquals(makerWallet.formatAmount("0.00012345", "BTC"), data.trade.amount)
                    assertEquals(SettlementStatus.Pending, data.trade.settlementStatus)
                }
            }
            waitForMessage().also { message ->
                assertEquals(SubscriptionTopic.Orders, message.topic)
                message.data.let { data ->
                    assertIs<OrderUpdated>(data)
                    assertEquals(OrderStatus.Partial, data.order.status)
                    assertEquals(1, data.order.executions.size)
                    assertEquals(data.order.executions[0].amount, takerWallet.formatAmount("0.00012345", "BTC"))
                    assertEquals(0, data.order.executions[0].price.compareTo(BigDecimal("17.500")))
                    assertEquals(ExecutionRole.Taker, data.order.executions[0].role)
                }
            }
        }

        makerWsClient.apply {
            waitForMessage().also { message ->
                assertEquals(SubscriptionTopic.Trades, message.topic)
                message.data.let { data ->
                    assertIs<TradeCreated>(data)
                    assertEquals(updatedLimitBuyOrder.id, data.trade.orderId)
                    assertEquals(updatedLimitBuyOrder.marketId, data.trade.marketId)
                    assertEquals(updatedLimitBuyOrder.side, data.trade.side)
                    assertEquals(updatedLimitBuyOrder.price, data.trade.price)
                    assertEquals(makerWallet.formatAmount("0.00012345", "BTC"), data.trade.amount)
                    assertEquals(SettlementStatus.Pending, data.trade.settlementStatus)
                }
            }
            waitForMessage().also { message ->
                assertEquals(SubscriptionTopic.Orders, message.topic)
                message.data.let { data ->
                    assertIs<OrderUpdated>(data)
                    assertEquals(OrderStatus.Filled, data.order.status)
                    assertEquals(1, data.order.executions.size)
                    assertEquals(data.order.executions[0].amount, takerWallet.formatAmount("0.00012345", "BTC"))
                    assertEquals(0, data.order.executions[0].price.compareTo(BigDecimal("17.500")))
                    assertEquals(ExecutionRole.Maker, data.order.executions[0].role)
                }
            }
        }

        val trade2 = getTradesForOrders(listOf(marketSellOrder.id)).first()
        assertEquals(trade2.amount, takerWallet.formatAmount("0.00012345", "BTC"))
        assertEquals(0, trade2.price.compareTo(BigDecimal("17.500")))

        waitForSettlementToFinish(listOf(trade2.id.value))
        takerWsClient.waitForMessage().also { message ->
            assertEquals(SubscriptionTopic.Trades, message.topic)
            message.data.let { data ->
                assertIs<TradeUpdated>(data)
                assertEquals(marketSellOrder.id, data.trade.orderId)
                assertEquals(SettlementStatus.Completed, data.trade.settlementStatus)
            }
        }
        makerWsClient.waitForMessage().also { message ->
            assertEquals(SubscriptionTopic.Trades, message.topic)
            message.data.let { data ->
                assertIs<TradeUpdated>(data)
                assertEquals(updatedLimitBuyOrder.id, data.trade.orderId)
                assertEquals(SettlementStatus.Completed, data.trade.settlementStatus)
            }
        }

        val baseQuantity2 = takerWallet.formatAmount("0.00012345", "BTC")
        val notional2 = (trade2.price * trade2.amount.fromFundamentalUnits(takerWallet.decimals("BTC"))).toFundamentalUnits(takerWallet.decimals("ETH"))
        BalanceHelper.verifyBalances(
            makerApiClient,
            listOf(
                ExpectedBalance("BTC", makerStartingBTCBalance - baseQuantity + baseQuantity2, makerStartingBTCBalance - baseQuantity + baseQuantity2),
                ExpectedBalance("ETH", makerStartingETHBalance + notional - notional2, makerStartingETHBalance + notional - notional2),
            ),
        )
        BalanceHelper.verifyBalances(
            takerApiClient,
            listOf(
                ExpectedBalance("BTC", takerStartingBTCBalance + baseQuantity - baseQuantity2, takerStartingBTCBalance + baseQuantity - baseQuantity2),
                ExpectedBalance("ETH", takerStartingETHBalance - notional + notional2, takerStartingETHBalance - notional + notional2),
            ),
        )

        takerApiClient.cancelOpenOrders()
        makerApiClient.cancelOpenOrders()

        // verify that client's websocket gets same orders and trades reconnect
        takerWsClient.close()
        WebsocketClient.blocking(takerApiClient.authToken).apply {
            subscribe(SubscriptionTopic.Orders)
            waitForMessage().also { message ->
                assertEquals(SubscriptionTopic.Orders, message.topic)
                assertIs<Orders>(message.data)
                val orders = (message.data as Orders).orders
                assertEquals(2, orders.size)
                orders[0].also { order ->
                    assertIs<Order.Market>(order)
                    assertEquals(marketSellOrder.id, order.id)
                    assertEquals(marketSellOrder.marketId, order.marketId)
                    assertEquals(marketSellOrder.side, order.side)
                    assertEquals(marketSellOrder.amount, order.amount)
                    assertEquals(OrderStatus.Cancelled, order.status)
                }
                orders[1].also { order ->
                    assertIs<Order.Market>(order)
                    assertEquals(marketBuyOrder.id, order.id)
                    assertEquals(marketBuyOrder.marketId, order.marketId)
                    assertEquals(marketBuyOrder.side, order.side)
                    assertEquals(marketBuyOrder.amount, order.amount)
                    assertEquals(OrderStatus.Filled, order.status)
                }
            }

            subscribe(SubscriptionTopic.Trades)
            waitForMessage().also { message ->
                assertEquals(SubscriptionTopic.Trades, message.topic)
                assertIs<Trades>(message.data)
                val trades = (message.data as Trades).trades
                assertEquals(2, trades.size)
                trades[0].apply {
                    assertEquals(marketSellOrder.id, orderId)
                    assertEquals(marketSellOrder.marketId, marketId)
                    assertEquals(marketSellOrder.side, side)
                    assertEquals(0, price.compareTo(BigDecimal("17.500")))
                    assertEquals(makerWallet.formatAmount("0.00012345", "BTC"), amount)
                    assertEquals(SettlementStatus.Completed, settlementStatus)
                }
                trades[1].apply {
                    assertEquals(marketBuyOrder.id, orderId)
                    assertEquals(marketBuyOrder.marketId, marketId)
                    assertEquals(marketBuyOrder.side, side)
                    assertEquals(updatedLimitSellOrder.price, price)
                    assertEquals(marketBuyOrder.amount, amount)
                    assertEquals(SettlementStatus.Completed, settlementStatus)
                }
            }
        }.close()

        makerWsClient.close()
    }

    @Test
    fun `order batches`() {
        val (makerApiClient, makerWallet, makerWsClient) =
            setupTrader("0.5", "0.2", "USDC", "500")

        val (takerApiClient, takerWallet, takerWsClient) =
            setupTrader("0.5", null, "USDC", "500")

        // starting onchain balances
        val makerStartingBTCBalance = makerWallet.getExchangeNativeBalance()
        val makerStartingUSDCBalance = makerWallet.getExchangeERC20Balance("USDC")
        val takerStartingBTCBalance = takerWallet.getExchangeNativeBalance()
        val takerStartingUSDCBalance = takerWallet.getExchangeERC20Balance("USDC")

        // place 3 orders
        val createBatchLimitOrders = makerApiClient.batchOrders(
            BatchOrdersApiRequest(
                marketId = MarketId("BTC/USDC"),
                createOrders = listOf("0.00001", "0.00002", "0.0003").map {
                    CreateOrderApiRequest.Limit(
                        nonce = generateHexString(32),
                        marketId = MarketId("BTC/USDC"),
                        side = OrderSide.Sell,
                        amount = makerWallet.formatAmount(it, "BTC"),
                        price = BigDecimal("68400.000"),
                        signature = EvmSignature.emptySignature(),
                    ).let {
                        makerWallet.signOrder(it)
                    }
                },
                updateOrders = listOf(),
                cancelOrders = listOf(),
            ),
        )

        assertEquals(3, createBatchLimitOrders.orders.count { it.status == OrderStatus.Open })
        makerWsClient.receivedDecoded().take(3).forEach {
            assertIs<OrderCreated>((it as OutgoingWSMessage.Publish).data)
        }

        val batchOrderResponse = makerApiClient.batchOrders(
            BatchOrdersApiRequest(
                marketId = MarketId("BTC/USDC"),
                createOrders = listOf("0.0004", "0.0005", "0.0006").map {
                    CreateOrderApiRequest.Limit(
                        nonce = generateHexString(32),
                        marketId = MarketId("BTC/USDC"),
                        side = OrderSide.Sell,
                        amount = makerWallet.formatAmount(it, "BTC"),
                        price = BigDecimal("68400.000"),
                        signature = EvmSignature.emptySignature(),
                    ).let {
                        makerWallet.signOrder(it)
                    }
                },
                updateOrders = listOf(
                    UpdateOrderApiRequest.Limit(
                        orderId = createBatchLimitOrders.orders[0].id,
                        amount = makerWallet.formatAmount("0.0001", "BTC"),
                        price = BigDecimal("68405.000"),
                    ),
                    UpdateOrderApiRequest.Limit(
                        orderId = createBatchLimitOrders.orders[1].id,
                        amount = makerWallet.formatAmount("0.0002", "BTC"),
                        price = BigDecimal("68405.000"),
                    ),
                ),
                cancelOrders = listOf(
                    CancelUpdateOrderApiRequest(orderId = createBatchLimitOrders.orders[2].id),
                ),
            ),
        )

        assertEquals(6, batchOrderResponse.orders.count())
        assertEquals(5, batchOrderResponse.orders.count { it.status == OrderStatus.Open })
        assertEquals(1, batchOrderResponse.orders.count { it.status == OrderStatus.Cancelled })

        makerWsClient.receivedDecoded().take(3).forEach {
            assertIs<OrderCreated>((it as OutgoingWSMessage.Publish).data)
        }

        makerWsClient.receivedDecoded().take(3).forEach {
            assertIs<OrderUpdated>((it as OutgoingWSMessage.Publish).data)
        }

        assertEquals(5, makerApiClient.listOrders().orders.count { it.status == OrderStatus.Open })

        // total BTC available is 0.0001 + 0.0002 + 0.0004 + 0.0005 + 0.0006 = 0.0018
        val takerOrderAmount = takerWallet.formatAmount("0.0018", "BTC")
        // create a market orders that should match against the 5 limit orders
        takerApiClient.createOrder(
            CreateOrderApiRequest.Market(
                nonce = generateHexString(32),
                marketId = MarketId("BTC/USDC"),
                side = OrderSide.Buy,
                amount = takerOrderAmount,
                signature = EvmSignature.emptySignature(),
            ).let {
                takerWallet.signOrder(it)
            },
        )

        takerWsClient.receivedDecoded().take(1).forEach {
            assertIs<OrderCreated>((it as OutgoingWSMessage.Publish).data)
        }

        takerWsClient.receivedDecoded().take(5).forEach {
            assertIs<TradeCreated>((it as OutgoingWSMessage.Publish).data)
        }
        takerWsClient.receivedDecoded().take(1).forEach {
            assertIs<OrderUpdated>((it as OutgoingWSMessage.Publish).data)
        }

        makerWsClient.receivedDecoded().take(5).forEach {
            assertIs<TradeCreated>((it as OutgoingWSMessage.Publish).data)
        }
        makerWsClient.receivedDecoded().take(5).forEach {
            assertIs<OrderUpdated>((it as OutgoingWSMessage.Publish).data)
        }
        // should be 8 filled orders
        val takerOrders = takerApiClient.listOrders().orders
        assertEquals(1, takerOrders.count { it.status == OrderStatus.Filled })
        assertEquals(5, makerApiClient.listOrders().orders.count { it.status == OrderStatus.Filled })

        // now verify the trades

        val expectedAmounts = listOf("0.0001", "0.0002", "0.0004", "0.0005", "0.0006").map { takerWallet.formatAmount(it, "BTC") }.toSet()
        val trades = getTradesForOrders(takerOrders.map { it.id })
        val prices = trades.map { it.price }.toSet()

        assertEquals(5, trades.size)
        assertEquals(expectedAmounts, trades.map { it.amount }.toSet())
        assertEquals(prices.size, 2)

        waitForSettlementToFinish(trades.map { it.id.value })

        val notional = trades.map {
            (it.price * it.amount.fromFundamentalUnits(makerWallet.decimals("BTC"))).toFundamentalUnits(takerWallet.decimals("USDC"))
        }.reduce { acc, notionalForTrade -> acc + notionalForTrade }

        // val notional = (prices.first() * BigDecimal("0.0018")).toFundamentalUnits(takerWallet.decimals("USDC"))
        BalanceHelper.verifyBalances(
            makerApiClient,
            listOf(
                ExpectedBalance("BTC", makerStartingBTCBalance - takerOrderAmount, makerStartingBTCBalance - takerOrderAmount),
                ExpectedBalance("USDC", makerStartingUSDCBalance + notional, makerStartingUSDCBalance + notional),
            ),
        )
        BalanceHelper.verifyBalances(
            takerApiClient,
            listOf(
                ExpectedBalance("BTC", takerStartingBTCBalance + takerOrderAmount, takerStartingBTCBalance + takerOrderAmount),
                ExpectedBalance("USDC", takerStartingUSDCBalance - notional, takerStartingUSDCBalance - notional),
            ),
        )

        takerApiClient.cancelOpenOrders()
        makerApiClient.cancelOpenOrders()

        makerWsClient.close()
        takerWsClient.close()
    }

    private fun setupTrader(
        nativeAmount: String,
        nativeDepositAmount: String?,
        mintSymbol: String,
        mintAmount: String,
    ): Triple<ApiClient, Wallet, WsClient> {
        val apiClient = ApiClient()
        val wallet = Wallet(apiClient)

        val wsClient = WebsocketClient.blocking(apiClient.authToken).apply {
            subscribe(SubscriptionTopic.Orders)
            waitForMessage().also { message ->
                assertEquals(SubscriptionTopic.Orders, message.topic)
                assertIs<Orders>(message.data)
            }
            subscribe(SubscriptionTopic.Trades)
            waitForMessage().also { message ->
                assertEquals(SubscriptionTopic.Trades, message.topic)
                assertIs<Trades>(message.data)
            }
        }

        Faucet.fund(wallet.address, wallet.formatAmount(nativeAmount, "BTC"))
        val formattedMintAmount = wallet.formatAmount(mintAmount, mintSymbol)
        wallet.mintERC20(mintSymbol, formattedMintAmount)

        val formattedNativeAmount = nativeDepositAmount?.let { wallet.formatAmount(it, "BTC") }

        BalanceHelper.waitForAndVerifyBalanceChange(
            apiClient,
            listOfNotNull(
                ExpectedBalance(mintSymbol, formattedMintAmount, formattedMintAmount),
                formattedNativeAmount?.let { ExpectedBalance("BTC", it, it) },
            ),
        ) {
            deposit(apiClient, wallet, mintSymbol, formattedMintAmount)
            formattedNativeAmount?.let { deposit(apiClient, wallet, "BTC", it) }
        }

        return Triple(apiClient, wallet, wsClient)
    }

    private fun deposit(apiClient: ApiClient, wallet: Wallet, asset: String, amount: BigInteger) {
        // deposit onchain and update sequencer
        if (asset == "BTC") {
            wallet.depositNative(amount)
        } else {
            wallet.depositERC20(asset, amount)
        }
    }

    private fun waitForSettlementToFinish(tradeIds: List<TradeId>) {
        await
            .pollInSameThread()
            .pollDelay(Duration.ofMillis(100))
            .pollInterval(Duration.ofMillis(100))
            .atMost(Duration.ofMillis(30000L))
            .until {
                transaction {
                    TradeEntity.count(TradeTable.guid.inList(tradeIds) and TradeTable.settlementStatus.eq(SettlementStatus.Completed))
                } == tradeIds.size.toLong()
            }
    }

    private fun getTradesForOrders(orderIds: List<OrderId>): List<TradeEntity> {
        return transaction {
            OrderExecutionEntity.findForOrders(orderIds).map { it.trade }
        }
    }
}
