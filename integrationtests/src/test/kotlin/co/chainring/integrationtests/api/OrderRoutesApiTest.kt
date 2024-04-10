package co.chainring.integrationtests.api

import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.CreateSequencerDeposit
import co.chainring.apps.api.model.Order
import co.chainring.apps.api.model.ReasonCode
import co.chainring.apps.api.model.UpdateOrderApiRequest
import co.chainring.apps.api.model.websocket.OrderCreated
import co.chainring.apps.api.model.websocket.OrderUpdated
import co.chainring.apps.api.model.websocket.Orders
import co.chainring.apps.api.model.websocket.OutgoingWSMessage
import co.chainring.apps.api.model.websocket.SubscriptionTopic
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.db.ExecutionRole
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderEntity
import co.chainring.core.model.db.OrderExecutionEntity
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.OrderStatus
import co.chainring.core.model.db.SettlementStatus
import co.chainring.core.model.db.TradeEntity
import co.chainring.core.model.db.TradeId
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
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

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
        wallet.mintERC20("DAI", wallet.formatAmount("2", "DAI"))
        val amountToDeposit = wallet.formatAmount("2", "DAI")
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
                id = limitOrder.id,
                amount = BigDecimal("3").toFundamentalUnits(18),
                price = BigDecimal("4"),
            ),
        )
        assertIs<Order.Limit>(updatedOrder)
        assertEquals(BigDecimal("3").toFundamentalUnits(18), updatedOrder.amount)
        assertEquals(0, BigDecimal("4").compareTo(updatedOrder.price))
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

        logger.debug { "Before wait for order created" }
        wsClient.waitForMessage().also { message ->
            assertEquals(SubscriptionTopic.Orders, message.topic)
            message.data.let { data ->
                assertIs<OrderCreated>(data)
                assertEquals(limitOrder, data.order)
            }
        }

        logger.debug { "Before wait for order updates" }
        wsClient.waitForMessage().also { message ->
            assertEquals(SubscriptionTopic.Orders, message.topic)
            message.data.let { data ->
                assertIs<OrderUpdated>(data)
                assertEquals(limitOrder.id, data.order.id)
                assertEquals(OrderStatus.CrossesMarket, data.order.status)
            }
        }

        // try update cancelled order
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
        apiClient.cancelOrder(limitOrder2.id)
        assertThrows<AbnormalApiResponseException> {
            apiClient.updateOrder(
                apiRequest = UpdateOrderApiRequest.Limit(
                    id = limitOrder2.id,
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
        val takerApiClient = ApiClient()
        val takerWallet = Wallet(takerApiClient)

        val takerWsClient = WebsocketClient.blocking(takerApiClient.authToken)
        takerWsClient.subscribe(SubscriptionTopic.Orders)
        takerWsClient.waitForMessage().also { message ->
            assertEquals(SubscriptionTopic.Orders, message.topic)
            assertIs<Orders>(message.data)
        }

        val makerApiClient = ApiClient()
        val makerWallet = Wallet(makerApiClient)

        val makerWsClient = WebsocketClient.blocking(makerApiClient.authToken)
        makerWsClient.subscribe(SubscriptionTopic.Orders)
        makerWsClient.waitForMessage().also { message ->
            assertEquals(SubscriptionTopic.Orders, message.topic)
            assertIs<Orders>(message.data)
        }

        Faucet.fund(takerWallet.address, takerWallet.formatAmount("0.5", "BTC"))
        Faucet.fund(makerWallet.address, takerWallet.formatAmount("0.5", "BTC"))
        takerWallet.mintERC20("ETH", takerWallet.formatAmount("2", "ETH"))
        makerWallet.mintERC20("ETH", takerWallet.formatAmount("2", "ETH"))

        val btcDepositAmount = takerWallet.formatAmount("0.2", "BTC")
        val ethDepositAmount = takerWallet.formatAmount("1", "ETH")
        BalanceHelper.waitForAndVerifyBalanceChange(
            makerApiClient,
            listOf(
                ExpectedBalance("BTC", btcDepositAmount, btcDepositAmount),
                ExpectedBalance("ETH", ethDepositAmount, ethDepositAmount),
            ),
        ) {
            deposit(makerApiClient, makerWallet, "BTC", btcDepositAmount)
            deposit(makerApiClient, makerWallet, "ETH", ethDepositAmount)
        }
        BalanceHelper.waitForAndVerifyBalanceChange(takerApiClient, listOf(ExpectedBalance("ETH", ethDepositAmount, ethDepositAmount))) {
            deposit(takerApiClient, takerWallet, "ETH", ethDepositAmount)
        }
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
                amount = makerWallet.formatAmount("0.00012345", "BTC"),
                price = BigDecimal("17.5"),
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

        // place a sell order
        val limitSellOrder = makerApiClient.createOrder(
            CreateOrderApiRequest.Limit(
                nonce = generateHexString(32),
                marketId = MarketId("BTC/ETH"),
                side = OrderSide.Sell,
                amount = makerWallet.formatAmount("0.00054321", "BTC"),
                price = BigDecimal("17.550"),
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

        // place a buy order and see it gets executes
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
        // we should see 2 updates over the WS, created and then an update for filled
        takerWsClient.waitForMessage().also { message ->
            assertEquals(SubscriptionTopic.Orders, message.topic)
            message.data.let { data ->
                assertIs<OrderCreated>(data)
                assertEquals(marketBuyOrder, data.order)
            }
        }

        takerWsClient.waitForMessage().also { message ->
            assertEquals(SubscriptionTopic.Orders, message.topic)
            message.data.let { data ->
                assertIs<OrderUpdated>(data)
                assertEquals(1, data.order.executions.size)
                assertEquals(data.order.executions[0].amount, takerWallet.formatAmount("0.00043210", "BTC"))
                assertEquals(0, data.order.executions[0].price.compareTo(BigDecimal("17.550")))
                assertEquals(data.order.executions[0].role, ExecutionRole.Taker)
            }
        }

        // maker should an update too
        makerWsClient.waitForMessage().also { message ->
            assertEquals(SubscriptionTopic.Orders, message.topic)
            message.data.let { data ->
                assertIs<OrderUpdated>(data)
                assertEquals(OrderStatus.Partial, data.order.status)
                assertEquals(1, data.order.executions.size)
                assertEquals(data.order.executions[0].amount, takerWallet.formatAmount("0.00043210", "BTC"))
                assertEquals(0, data.order.executions[0].price.compareTo(BigDecimal("17.550")))
                assertEquals(ExecutionRole.Maker, data.order.executions[0].role)
            }
        }
        val trade = waitForTradeToExist(marketBuyOrder.id)

        assertEquals(trade.amount, takerWallet.formatAmount("0.00043210", "BTC"))
        assertEquals(0, trade.price.compareTo(BigDecimal("17.550")))

        waitForSettlementToFinish(trade.id.value)

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

        // place a sell order and see it gets executes
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
        // we should see 2 updates over the WS, created and then an update for partially filled
        takerWsClient.waitForMessage().also { message ->
            assertEquals(SubscriptionTopic.Orders, message.topic)
            message.data.let { data ->
                assertIs<OrderCreated>(data)
                assertEquals(marketSellOrder, data.order)
            }
        }

        takerWsClient.waitForMessage().also { message ->
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

        // maker should an update too
        makerWsClient.waitForMessage().also { message ->
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
        val trade2 = waitForTradeToExist(marketSellOrder.id)
        assertEquals(trade2.amount, takerWallet.formatAmount("0.00012345", "BTC"))
        assertEquals(0, trade2.price.compareTo(BigDecimal("17.500")))

        waitForSettlementToFinish(trade2.id.value)

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

        makerWsClient.close()
        takerWsClient.close()
    }

    private fun deposit(apiClient: ApiClient, wallet: Wallet, asset: String, amount: BigInteger) {
        // deposit onchain and update sequencer
        if (asset == "BTC") {
            wallet.depositNative(amount)
        } else {
            wallet.depositERC20(asset, amount)
        }
        apiClient.createSequencerDeposit(CreateSequencerDeposit(asset, amount))
    }

    private fun waitForSettlementToFinish(id: TradeId) {
        await
            .pollInSameThread()
            .pollDelay(Duration.ofMillis(100))
            .pollInterval(Duration.ofMillis(100))
            .atMost(Duration.ofMillis(30000L))
            .until {
                transaction {
                    TradeEntity[id].settlementStatus.isFinal()
                }
            }

        transaction {
            assertEquals(SettlementStatus.Completed, TradeEntity[id].settlementStatus)
        }
    }

    private fun waitForTradeToExist(orderId: OrderId): TradeEntity {
        await
            .pollInSameThread()
            .pollDelay(Duration.ofMillis(100))
            .pollInterval(Duration.ofMillis(100))
            .atMost(Duration.ofMillis(10000L))
            .until {
                transaction {
                    OrderExecutionEntity.findForOrder(OrderEntity[orderId]).firstOrNull()?.trade != null
                }
            }

        return transaction { OrderExecutionEntity.findForOrder(OrderEntity[orderId]).firstOrNull()?.trade!! }
    }
}
