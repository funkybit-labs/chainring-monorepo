package co.chainring.integrationtests.api

import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.BatchOrdersApiRequest
import co.chainring.apps.api.model.CancelUpdateOrderApiRequest
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.Order
import co.chainring.apps.api.model.ReasonCode
import co.chainring.apps.api.model.UpdateOrderApiRequest
import co.chainring.apps.api.model.websocket.LastTrade
import co.chainring.apps.api.model.websocket.LastTradeDirection
import co.chainring.apps.api.model.websocket.OrderBook
import co.chainring.apps.api.model.websocket.OrderBookEntry
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.Symbol
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
import co.chainring.integrationtests.testutils.ApiClient
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.testutils.BalanceHelper
import co.chainring.integrationtests.testutils.ExpectedBalance
import co.chainring.integrationtests.testutils.Faucet
import co.chainring.integrationtests.testutils.Wallet
import co.chainring.integrationtests.testutils.assertBalancesMessageReceived
import co.chainring.integrationtests.testutils.assertError
import co.chainring.integrationtests.testutils.assertOrderBookMessageReceived
import co.chainring.integrationtests.testutils.assertOrderCreatedMessageReceived
import co.chainring.integrationtests.testutils.assertOrderUpdatedMessageReceived
import co.chainring.integrationtests.testutils.assertOrdersMessageReceived
import co.chainring.integrationtests.testutils.assertTradeCreatedMessageReceived
import co.chainring.integrationtests.testutils.assertTradeUpdatedMessageReceived
import co.chainring.integrationtests.testutils.assertTradesMessageReceived
import co.chainring.integrationtests.testutils.blocking
import co.chainring.integrationtests.testutils.subscribeToBalances
import co.chainring.integrationtests.testutils.subscribeToOrderBook
import co.chainring.integrationtests.testutils.subscribeToOrders
import co.chainring.integrationtests.testutils.subscribeToTrades
import io.github.oshai.kotlinlogging.KotlinLogging
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withAlias
import org.http4k.client.WebsocketClient
import org.http4k.websocket.WsClient
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull

@ExtendWith(AppUnderTestRunner::class)
class OrderRoutesApiTest {
    private val logger = KotlinLogging.logger {}
    private val btcEthMarketId = MarketId("BTC/ETH")
    private val usdcDaiMarketId = MarketId("USDC/DAI")

    @Test
    fun `CRUD order`() {
        val apiClient = ApiClient()
        val wallet = Wallet(apiClient)

        var wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToOrders()
        val initialOrdersOverWs = wsClient.assertOrdersMessageReceived().orders

        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        Faucet.fund(wallet.address)
        wallet.mintERC20("DAI", wallet.formatAmount("14", "DAI"))
        val amountToDeposit = wallet.formatAmount("14", "DAI")
        BalanceHelper.waitForAndVerifyBalanceChange(apiClient, listOf(ExpectedBalance("DAI", amountToDeposit, amountToDeposit))) {
            deposit(wallet, "DAI", amountToDeposit)
        }
        wsClient.assertBalancesMessageReceived { msg ->
            assertEquals(1, msg.balances.size)
            assertEquals(Symbol("DAI"), msg.balances.first().symbol)
            assertEquals(BigInteger("14000000000000000000"), msg.balances.first().available)
        }

        val limitOrderApiRequest = CreateOrderApiRequest.Limit(
            nonce = generateHexString(32),
            marketId = usdcDaiMarketId,
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
        wsClient.assertOrderCreatedMessageReceived { msg ->
            assertIs<Order.Limit>(msg.order)
            validateLimitOrders(limitOrder, msg.order as Order.Limit, false)
        }
        wsClient.close()

        // check that order is included in the orders list sent via websocket
        wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToOrders()
        assertEquals(
            listOf(limitOrder) + initialOrdersOverWs,
            wsClient.assertOrdersMessageReceived().orders,
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
        wsClient.assertOrderUpdatedMessageReceived { msg ->
            assertIs<Order.Limit>(msg.order)
            validateLimitOrders(updatedOrder, msg.order as Order.Limit, true)
        }

        // cancel order is idempotent
        apiClient.cancelOrder(limitOrder.id)
        val cancelledOrder = apiClient.getOrder(limitOrder.id)
        wsClient.assertOrderUpdatedMessageReceived { msg ->
            assertEquals(cancelledOrder.id, msg.order.id)
            assertEquals(OrderStatus.Cancelled, msg.order.status)
        }
        assertEquals(OrderStatus.Cancelled, apiClient.getOrder(limitOrder.id).status)

        wsClient.close()
    }

    @Test
    fun `CRUD order error cases`() {
        val apiClient = ApiClient()
        val wallet = Wallet(apiClient)

        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToOrders()
        wsClient.assertOrdersMessageReceived()

        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        Faucet.fund(wallet.address)
        val amountToDeposit = wallet.formatAmount("20", "DAI")
        wallet.mintERC20("DAI", amountToDeposit)
        BalanceHelper.waitForAndVerifyBalanceChange(apiClient, listOf(ExpectedBalance("DAI", amountToDeposit, amountToDeposit))) {
            deposit(wallet, "DAI", amountToDeposit)
        }
        wsClient.assertBalancesMessageReceived { msg ->
            assertEquals(1, msg.balances.size)
            assertEquals(Symbol("DAI"), msg.balances.first().symbol)
            assertEquals(BigInteger("20000000000000000000"), msg.balances.first().available)
        }

        // operation on non-existent order
        ApiError(ReasonCode.OrderNotFound, "Requested order does not exist").also { expectedError ->
            apiClient.tryGetOrder(OrderId.generate()).assertError(expectedError)

            apiClient.tryUpdateOrder(
                apiRequest = UpdateOrderApiRequest.Limit(
                    orderId = OrderId.generate(),
                    amount = BigDecimal("3").toFundamentalUnits(18),
                    price = BigDecimal("4"),
                ),
            ).assertError(expectedError)

            apiClient.tryCancelOrder(OrderId.generate()).assertError(expectedError)
        }

        // try to submit an order that crosses the market
        val limitOrder = apiClient.createOrder(
            CreateOrderApiRequest.Limit(
                nonce = generateHexString(32),
                marketId = usdcDaiMarketId,
                side = OrderSide.Buy,
                amount = wallet.formatAmount("1", "USDC"),
                price = BigDecimal("3"),
                signature = EvmSignature.emptySignature(),
            ).let {
                wallet.signOrder(it)
            },
        )

        assertIs<Order.Limit>(limitOrder)

        wsClient.apply {
            assertOrderCreatedMessageReceived { msg ->
                validateLimitOrders(limitOrder, msg.order as Order.Limit, false)
            }
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(limitOrder.id, msg.order.id)
                assertEquals(OrderStatus.CrossesMarket, msg.order.status)
            }
        }

        // try creating a limit order not a multiple of tick size
        apiClient.tryCreateOrder(
            CreateOrderApiRequest.Limit(
                nonce = generateHexString(32),
                marketId = usdcDaiMarketId,
                side = OrderSide.Buy,
                amount = wallet.formatAmount("1", "USDC"),
                price = BigDecimal("2.015"),
                signature = EvmSignature.emptySignature(),
            ).let {
                wallet.signOrder(it)
            },
        ).assertError(
            ApiError(ReasonCode.ProcessingError, "Order price is not a multiple of tick size"),
        )

        val limitOrder2 = apiClient.createOrder(
            CreateOrderApiRequest.Limit(
                nonce = generateHexString(32),
                marketId = usdcDaiMarketId,
                side = OrderSide.Buy,
                amount = wallet.formatAmount("1", "USDC"),
                price = BigDecimal("2"),
                signature = EvmSignature.emptySignature(),
            ).let {
                wallet.signOrder(it)
            },
        )

        // try updating the price to something not a tick size
        apiClient.tryUpdateOrder(
            apiRequest = UpdateOrderApiRequest.Limit(
                orderId = limitOrder2.id,
                amount = wallet.formatAmount("1", "USDC"),
                price = BigDecimal("2.015"),
            ),
        ).assertError(
            ApiError(ReasonCode.ProcessingError, "Order price is not a multiple of tick size"),
        )

        // try updating and cancelling an order not created by this wallet
        val apiClient2 = ApiClient()
        apiClient2.tryUpdateOrder(
            apiRequest = UpdateOrderApiRequest.Limit(
                orderId = limitOrder2.id,
                amount = wallet.formatAmount("1", "USDC"),
                price = BigDecimal("2.01"),
            ),
        ).assertError(
            ApiError(ReasonCode.ProcessingError, "Order not created with this wallet"),
        )
        apiClient2.tryCancelOrder(
            limitOrder2.id,
        ).assertError(
            ApiError(ReasonCode.ProcessingError, "Order not created with this wallet"),
        )

        // try update cancelled order
        apiClient.cancelOrder(limitOrder2.id)
        apiClient.tryUpdateOrder(
            apiRequest = UpdateOrderApiRequest.Limit(
                orderId = limitOrder2.id,
                amount = wallet.formatAmount("3", "USDC"),
                price = BigDecimal("4"),
            ),
        ).assertError(
            ApiError(ReasonCode.OrderIsClosed, "Order is already finalized"),
        )
    }

    @Test
    fun `list and cancel all open orders`() {
        val apiClient = ApiClient()
        val wallet = Wallet(apiClient)
        apiClient.cancelOpenOrders()

        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToOrders()
        val initialOrdersOverWs = wsClient.assertOrdersMessageReceived().orders

        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        Faucet.fund(wallet.address)
        val amountToDeposit = wallet.formatAmount("30", "DAI")
        wallet.mintERC20("DAI", amountToDeposit)
        BalanceHelper.waitForAndVerifyBalanceChange(apiClient, listOf(ExpectedBalance("DAI", amountToDeposit, amountToDeposit))) {
            deposit(wallet, "DAI", amountToDeposit)
        }
        wsClient.assertBalancesMessageReceived { msg ->
            assertEquals(1, msg.balances.size)
            assertEquals(Symbol("DAI"), msg.balances.first().symbol)
            assertEquals(BigInteger("30000000000000000000"), msg.balances.first().available)
        }

        val limitOrderApiRequest = CreateOrderApiRequest.Limit(
            nonce = generateHexString(32),
            marketId = usdcDaiMarketId,
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
        repeat(10) { wsClient.assertOrderCreatedMessageReceived() }

        apiClient.cancelOpenOrders()

        wsClient.assertOrdersMessageReceived { msg ->
            assertNotEquals(initialOrdersOverWs, msg.orders)
            assertTrue(msg.orders.all { it.status == OrderStatus.Cancelled })
        }

        assertTrue(apiClient.listOrders().orders.all { it.status == OrderStatus.Cancelled })

        wsClient.close()
    }

    @Test
    fun `order execution`() {
        val (takerApiClient, takerWallet, takerWsClient) =
            setupTrader(btcEthMarketId, "0.5", null, "ETH", "2")

        val (makerApiClient, makerWallet, makerWsClient) =
            setupTrader(btcEthMarketId, "0.5", "0.2", "ETH", "2")

        // starting onchain balances
        val makerStartingBTCBalance = makerWallet.getExchangeNativeBalance()
        val makerStartingETHBalance = makerWallet.getExchangeERC20Balance("ETH")
        val takerStartingBTCBalance = takerWallet.getExchangeNativeBalance()
        val takerStartingETHBalance = takerWallet.getExchangeERC20Balance("ETH")

        // place an order and see that it gets accepted
        val limitBuyOrder = makerApiClient.createOrder(
            CreateOrderApiRequest.Limit(
                nonce = generateHexString(32),
                marketId = btcEthMarketId,
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
        makerWsClient.assertOrderCreatedMessageReceived { msg ->
            validateLimitOrders(limitBuyOrder, msg.order as Order.Limit, false)
        }

        listOf(makerWsClient, takerWsClient).forEach { wsClient ->
            wsClient.assertOrderBookMessageReceived(
                btcEthMarketId,
                OrderBook(
                    marketId = btcEthMarketId,
                    buy = listOf(
                        OrderBookEntry(price = "17.450", size = "0.00013345".toBigDecimal()),
                    ),
                    sell = emptyList(),
                    last = LastTrade("0.000", LastTradeDirection.Up),
                ),
            )
        }

        val updatedLimitBuyOrder = makerApiClient.updateOrder(
            UpdateOrderApiRequest.Limit(
                orderId = limitBuyOrder.id,
                amount = makerWallet.formatAmount("0.00012345", "BTC"),
                price = BigDecimal("17.50"),
            ),
        )
        assertIs<Order.Limit>(updatedLimitBuyOrder)

        makerWsClient.assertOrderUpdatedMessageReceived { msg ->
            validateLimitOrders(updatedLimitBuyOrder, msg.order as Order.Limit, true)
        }
        listOf(makerWsClient, takerWsClient).forEach { wsClient ->
            wsClient.assertOrderBookMessageReceived(
                btcEthMarketId,
                OrderBook(
                    marketId = btcEthMarketId,
                    buy = listOf(
                        OrderBookEntry(price = "17.500", size = "0.00012345".toBigDecimal()),
                    ),
                    sell = emptyList(),
                    last = LastTrade("0.000", LastTradeDirection.Up),
                ),
            )
        }

        // place a sell order
        val limitSellOrder = makerApiClient.createOrder(
            CreateOrderApiRequest.Limit(
                nonce = generateHexString(32),
                marketId = btcEthMarketId,
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

        makerWsClient.assertOrderCreatedMessageReceived { msg ->
            validateLimitOrders(limitSellOrder, msg.order as Order.Limit, false)
        }
        listOf(makerWsClient, takerWsClient).forEach { wsClient ->
            wsClient.assertOrderBookMessageReceived(
                btcEthMarketId,
                OrderBook(
                    marketId = btcEthMarketId,
                    buy = listOf(
                        OrderBookEntry(price = "17.500", size = "0.00012345".toBigDecimal()),
                    ),
                    sell = listOf(
                        OrderBookEntry(price = "17.600", size = "0.00154321".toBigDecimal()),
                    ),
                    last = LastTrade("0.000", LastTradeDirection.Up),
                ),
            )
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

        makerWsClient.assertOrderUpdatedMessageReceived { msg ->
            assertIs<Order.Limit>(msg.order)
            validateLimitOrders(updatedLimitSellOrder, msg.order as Order.Limit, true)
        }
        listOf(makerWsClient, takerWsClient).forEach { wsClient ->
            wsClient.assertOrderBookMessageReceived(
                btcEthMarketId,
                OrderBook(
                    marketId = btcEthMarketId,
                    buy = listOf(
                        OrderBookEntry(price = "17.500", size = "0.00012345".toBigDecimal()),
                    ),
                    sell = listOf(
                        OrderBookEntry(price = "17.550", size = "0.00054321".toBigDecimal()),
                    ),
                    last = LastTrade("0.000", LastTradeDirection.Up),
                ),
            )
        }

        // place a buy order and see it gets executed
        val marketBuyOrder = takerApiClient.createOrder(
            CreateOrderApiRequest.Market(
                nonce = generateHexString(32),
                marketId = btcEthMarketId,
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
            assertOrderCreatedMessageReceived { msg ->
                assertEquals(marketBuyOrder.id, msg.order.id)
                assertEquals(marketBuyOrder.amount, msg.order.amount)
                assertEquals(marketBuyOrder.side, msg.order.side)
                assertEquals(marketBuyOrder.marketId, msg.order.marketId)
                assertEquals(marketBuyOrder.timing.createdAt, msg.order.timing.createdAt)
            }
            assertTradeCreatedMessageReceived { msg ->
                assertEquals(marketBuyOrder.id, msg.trade.orderId)
                assertEquals(marketBuyOrder.marketId, msg.trade.marketId)
                assertEquals(marketBuyOrder.side, msg.trade.side)
                assertEquals(updatedLimitSellOrder.price, msg.trade.price)
                assertEquals(marketBuyOrder.amount, msg.trade.amount)
                assertEquals(SettlementStatus.Pending, msg.trade.settlementStatus)
            }
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Filled, msg.order.status)
                assertEquals(1, msg.order.executions.size)
                assertEquals(msg.order.executions[0].amount, takerWallet.formatAmount("0.00043210", "BTC"))
                assertEquals(0, msg.order.executions[0].price.compareTo(BigDecimal("17.550")))
                assertEquals(msg.order.executions[0].role, ExecutionRole.Taker)
            }
            assertBalancesMessageReceived { msg ->
                assertEquals(BigInteger("1992416645000000000"), msg.balances.first { it.symbol.value == "ETH" }.available)
                assertEquals(BigInteger("432100000000000"), msg.balances.first { it.symbol.value == "BTC" }.available)
            }
        }

        makerWsClient.apply {
            assertTradeCreatedMessageReceived { msg ->
                assertEquals(updatedLimitSellOrder.id, msg.trade.orderId)
                assertEquals(updatedLimitSellOrder.marketId, msg.trade.marketId)
                assertEquals(updatedLimitSellOrder.side, msg.trade.side)
                assertEquals(updatedLimitSellOrder.price, msg.trade.price)
                assertEquals(takerWallet.formatAmount("0.00043210", "BTC"), msg.trade.amount)
                assertEquals(SettlementStatus.Pending, msg.trade.settlementStatus)
            }
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Partial, msg.order.status)
                assertEquals(1, msg.order.executions.size)
                assertEquals(msg.order.executions[0].amount, makerWallet.formatAmount("0.00043210", "BTC"))
                assertEquals(0, msg.order.executions[0].price.compareTo(BigDecimal("17.550")))
                assertEquals(ExecutionRole.Maker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived { msg ->
                assertEquals(BigInteger("2007583355000000000"), msg.balances.first { it.symbol.value == "ETH" }.available)
                assertEquals(BigInteger("199567900000000000"), msg.balances.first { it.symbol.value == "BTC" }.available)
            }
        }

        listOf(makerWsClient, takerWsClient).forEach { wsClient ->
            wsClient.assertOrderBookMessageReceived(
                btcEthMarketId,
                OrderBook(
                    marketId = btcEthMarketId,
                    buy = listOf(
                        OrderBookEntry(price = "17.500", size = "0.00012345".toBigDecimal()),
                    ),
                    sell = listOf(
                        // TODO: amount should be reduced since MM order was partially filled
                        OrderBookEntry(price = "17.550", size = "0.00054321".toBigDecimal()),
                    ),
                    last = LastTrade("17.550", LastTradeDirection.Up),
                ),
            )
        }

        val trade = getTradesForOrders(listOf(marketBuyOrder.id)).first()

        assertEquals(trade.amount, takerWallet.formatAmount("0.00043210", "BTC"))
        assertEquals(0, trade.price.compareTo(BigDecimal("17.550")))

        waitForSettlementToFinish(listOf(trade.id.value))
        takerWsClient.assertTradeUpdatedMessageReceived { msg ->
            assertEquals(marketBuyOrder.id, msg.trade.orderId)
            assertEquals(SettlementStatus.Completed, msg.trade.settlementStatus)
        }
        makerWsClient.assertTradeUpdatedMessageReceived { msg ->
            assertEquals(updatedLimitSellOrder.id, msg.trade.orderId)
            assertEquals(SettlementStatus.Completed, msg.trade.settlementStatus)
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
                marketId = btcEthMarketId,
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
            assertOrderCreatedMessageReceived { msg ->
                assertEquals(marketSellOrder.id, msg.order.id)
                assertEquals(marketSellOrder.amount, msg.order.amount)
                assertEquals(marketSellOrder.side, msg.order.side)
                assertEquals(marketSellOrder.marketId, msg.order.marketId)
                assertEquals(marketSellOrder.timing.createdAt, msg.order.timing.createdAt)
            }
            assertTradeCreatedMessageReceived { msg ->
                assertEquals(marketSellOrder.id, msg.trade.orderId)
                assertEquals(marketSellOrder.marketId, msg.trade.marketId)
                assertEquals(marketSellOrder.side, msg.trade.side)
                assertEquals(0, msg.trade.price.compareTo(BigDecimal("17.500")))
                assertEquals(makerWallet.formatAmount("0.00012345", "BTC"), msg.trade.amount)
                assertEquals(SettlementStatus.Pending, msg.trade.settlementStatus)
            }
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Partial, msg.order.status)
                assertEquals(1, msg.order.executions.size)
                assertEquals(msg.order.executions[0].amount, takerWallet.formatAmount("0.00012345", "BTC"))
                assertEquals(0, msg.order.executions[0].price.compareTo(BigDecimal("17.500")))
                assertEquals(ExecutionRole.Taker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived { msg ->
                assertEquals(BigInteger("1994577020000000000"), msg.balances.first { it.symbol.value == "ETH" }.available)
                assertEquals(BigInteger("308650000000000"), msg.balances.first { it.symbol.value == "BTC" }.available)
            }
        }

        makerWsClient.apply {
            assertTradeCreatedMessageReceived { msg ->
                assertEquals(updatedLimitBuyOrder.id, msg.trade.orderId)
                assertEquals(updatedLimitBuyOrder.marketId, msg.trade.marketId)
                assertEquals(updatedLimitBuyOrder.side, msg.trade.side)
                assertEquals(updatedLimitBuyOrder.price, msg.trade.price)
                assertEquals(makerWallet.formatAmount("0.00012345", "BTC"), msg.trade.amount)
                assertEquals(SettlementStatus.Pending, msg.trade.settlementStatus)
            }
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Filled, msg.order.status)
                assertEquals(1, msg.order.executions.size)
                assertEquals(msg.order.executions[0].amount, takerWallet.formatAmount("0.00012345", "BTC"))
                assertEquals(0, msg.order.executions[0].price.compareTo(BigDecimal("17.500")))
                assertEquals(ExecutionRole.Maker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived { msg ->
                assertEquals(BigInteger("2005422980000000000"), msg.balances.first { it.symbol.value == "ETH" }.available)
                assertEquals(BigInteger("199691350000000000"), msg.balances.first { it.symbol.value == "BTC" }.available)
            }
        }

        listOf(makerWsClient, takerWsClient).forEach { wsClient ->
            wsClient.assertOrderBookMessageReceived(
                btcEthMarketId,
                OrderBook(
                    marketId = btcEthMarketId,
                    buy = emptyList(),
                    sell = listOf(
                        OrderBookEntry(price = "17.550", size = "0.00054321".toBigDecimal()),
                    ),
                    last = LastTrade("17.500", LastTradeDirection.Down),
                ),
            )
        }

        val trade2 = getTradesForOrders(listOf(marketSellOrder.id)).first()
        assertEquals(trade2.amount, takerWallet.formatAmount("0.00012345", "BTC"))
        assertEquals(0, trade2.price.compareTo(BigDecimal("17.500")))

        waitForSettlementToFinish(listOf(trade2.id.value))
        takerWsClient.assertTradeUpdatedMessageReceived { msg ->
            assertEquals(marketSellOrder.id, msg.trade.orderId)
            assertEquals(SettlementStatus.Completed, msg.trade.settlementStatus)
        }
        makerWsClient.assertTradeUpdatedMessageReceived { msg ->
            assertEquals(updatedLimitBuyOrder.id, msg.trade.orderId)
            assertEquals(SettlementStatus.Completed, msg.trade.settlementStatus)
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
        takerWsClient.assertOrdersMessageReceived()
        listOf(makerWsClient, takerWsClient).forEach { wsClient ->
            wsClient.assertOrderBookMessageReceived(
                btcEthMarketId,
                OrderBook(
                    marketId = btcEthMarketId,
                    buy = emptyList(),
                    sell = listOf(
                        OrderBookEntry(price = "17.550", size = "0.00054321".toBigDecimal()),
                    ),
                    last = LastTrade("17.500", LastTradeDirection.Down),
                ),
            )
        }

        makerApiClient.cancelOpenOrders()
        makerWsClient.assertOrdersMessageReceived()
        listOf(makerWsClient, takerWsClient).forEach { wsClient ->
            wsClient.assertOrderBookMessageReceived(
                btcEthMarketId,
                OrderBook(
                    marketId = btcEthMarketId,
                    buy = emptyList(),
                    sell = emptyList(),
                    last = LastTrade("17.500", LastTradeDirection.Down),
                ),
            )
        }

        // verify that client's websocket gets same orders, trades and order book on reconnect
        takerWsClient.close()
        WebsocketClient.blocking(takerApiClient.authToken).apply {
            subscribeToOrders()
            assertOrdersMessageReceived { msg ->
                assertEquals(2, msg.orders.size)
                msg.orders[0].also { order ->
                    assertIs<Order.Market>(order)
                    assertEquals(marketSellOrder.id, order.id)
                    assertEquals(marketSellOrder.marketId, order.marketId)
                    assertEquals(marketSellOrder.side, order.side)
                    assertEquals(marketSellOrder.amount, order.amount)
                    assertEquals(OrderStatus.Cancelled, order.status)
                }
                msg.orders[1].also { order ->
                    assertIs<Order.Market>(order)
                    assertEquals(marketBuyOrder.id, order.id)
                    assertEquals(marketBuyOrder.marketId, order.marketId)
                    assertEquals(marketBuyOrder.side, order.side)
                    assertEquals(marketBuyOrder.amount, order.amount)
                    assertEquals(OrderStatus.Filled, order.status)
                }
            }

            subscribeToTrades()
            assertTradesMessageReceived { msg ->
                assertEquals(2, msg.trades.size)
                msg.trades[0].apply {
                    assertEquals(marketSellOrder.id, orderId)
                    assertEquals(marketSellOrder.marketId, marketId)
                    assertEquals(marketSellOrder.side, side)
                    assertEquals(0, price.compareTo(BigDecimal("17.500")))
                    assertEquals(makerWallet.formatAmount("0.00012345", "BTC"), amount)
                    assertEquals(SettlementStatus.Completed, settlementStatus)
                }
                msg.trades[1].apply {
                    assertEquals(marketBuyOrder.id, orderId)
                    assertEquals(marketBuyOrder.marketId, marketId)
                    assertEquals(marketBuyOrder.side, side)
                    assertEquals(updatedLimitSellOrder.price, price)
                    assertEquals(marketBuyOrder.amount, amount)
                    assertEquals(SettlementStatus.Completed, settlementStatus)
                }
            }

            subscribeToOrderBook(btcEthMarketId)
            assertOrderBookMessageReceived(
                btcEthMarketId,
                OrderBook(
                    marketId = btcEthMarketId,
                    buy = emptyList(),
                    sell = emptyList(),
                    last = LastTrade("17.500", LastTradeDirection.Down),
                ),
            )
        }.close()

        makerWsClient.close()
    }

    @Test
    fun `order batches`() {
        val (makerApiClient, makerWallet, makerWsClient) =
            setupTrader(usdcDaiMarketId, "0.5", "0.2", "USDC", "500")

        val (takerApiClient, takerWallet, takerWsClient) =
            setupTrader(usdcDaiMarketId, "0.5", null, "USDC", "500")

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
        repeat(3) { makerWsClient.assertOrderCreatedMessageReceived() }

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

        repeat(3) { makerWsClient.assertOrderCreatedMessageReceived() }
        repeat(3) { makerWsClient.assertOrderUpdatedMessageReceived() }

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

        takerWsClient.apply {
            assertOrderCreatedMessageReceived()
            repeat(5) { assertTradeCreatedMessageReceived() }
            assertOrderUpdatedMessageReceived()
        }

        makerWsClient.apply {
            repeat(5) { assertTradeCreatedMessageReceived() }
            assertOrderUpdatedMessageReceived()
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

    private fun validateLimitOrders(left: Order.Limit, right: Order.Limit, updated: Boolean) {
        assertEquals(left.id, right.id)
        assertEquals(left.amount, right.amount)
        assertEquals(left.side, right.side)
        assertEquals(left.marketId, right.marketId)
        assertEquals(left.price, right.price)
        assertEquals(left.timing.createdAt, right.timing.createdAt)
        if (updated) {
            assertNotNull(left.timing.updatedAt)
            assertNotNull(right.timing.updatedAt)
        }
    }

    private fun setupTrader(
        marketId: MarketId,
        nativeAmount: String,
        nativeDepositAmount: String?,
        mintSymbol: String,
        mintAmount: String,
    ): Triple<ApiClient, Wallet, WsClient> {
        val apiClient = ApiClient()
        val wallet = Wallet(apiClient)

        val wsClient = WebsocketClient.blocking(apiClient.authToken).apply {
            subscribeToOrderBook(marketId)
            assertOrderBookMessageReceived(marketId)

            subscribeToOrders()
            assertOrdersMessageReceived()

            subscribeToTrades()
            assertTradesMessageReceived()

            subscribeToBalances()
            assertBalancesMessageReceived()
        }

        Faucet.fund(wallet.address, wallet.formatAmount(nativeAmount, "BTC"))
        val formattedMintAmount = wallet.formatAmount(mintAmount, mintSymbol)
        wallet.mintERC20(mintSymbol, formattedMintAmount)

        val formattedNativeAmount = nativeDepositAmount?.let { wallet.formatAmount(it, "BTC") }

        val expectedBalances = listOfNotNull(
            ExpectedBalance(mintSymbol, formattedMintAmount, formattedMintAmount),
            formattedNativeAmount?.let { ExpectedBalance("BTC", it, it) },
        )

        BalanceHelper.waitForAndVerifyBalanceChange(
            apiClient,
            expectedBalances,
        ) {
            deposit(wallet, mintSymbol, formattedMintAmount)
            formattedNativeAmount?.let { deposit(wallet, "BTC", it) }
        }
        repeat(expectedBalances.size) { wsClient.assertBalancesMessageReceived() }

        return Triple(apiClient, wallet, wsClient)
    }

    private fun deposit(wallet: Wallet, asset: String, amount: BigInteger) {
        // deposit onchain and update sequencer
        if (asset == "BTC") {
            wallet.depositNative(amount)
        } else {
            wallet.depositERC20(asset, amount)
        }
    }

    private fun waitForSettlementToFinish(tradeIds: List<TradeId>) {
        await
            .withAlias("Waiting for trade settlement to finish. TradeIds: ${tradeIds.joinToString { it.value }}")
            .pollInSameThread()
            .pollDelay(Duration.ofMillis(100))
            .pollInterval(Duration.ofMillis(100))
            .atMost(Duration.ofMillis(30000L))
            .until {
                Faucet.mine()
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
