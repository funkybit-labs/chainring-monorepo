package co.chainring.integrationtests.api

import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.BatchOrdersApiRequest
import co.chainring.apps.api.model.CancelOrderApiRequest
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.CreateOrderApiResponse
import co.chainring.apps.api.model.Order
import co.chainring.apps.api.model.ReasonCode
import co.chainring.apps.api.model.RequestStatus
import co.chainring.apps.api.model.UpdateOrderApiRequest
import co.chainring.apps.api.model.websocket.LastTrade
import co.chainring.apps.api.model.websocket.LastTradeDirection
import co.chainring.apps.api.model.websocket.OHLC
import co.chainring.apps.api.model.websocket.OrderBook
import co.chainring.apps.api.model.websocket.OrderBookEntry
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.ExecutionRole
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OHLCDuration
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
import co.chainring.integrationtests.testutils.assertPricesMessageReceived
import co.chainring.integrationtests.testutils.assertTradeCreatedMessageReceived
import co.chainring.integrationtests.testutils.assertTradeUpdatedMessageReceived
import co.chainring.integrationtests.testutils.assertTradesMessageReceived
import co.chainring.integrationtests.testutils.blocking
import co.chainring.integrationtests.testutils.subscribeToBalances
import co.chainring.integrationtests.testutils.subscribeToOrderBook
import co.chainring.integrationtests.testutils.subscribeToOrders
import co.chainring.integrationtests.testutils.subscribeToPrices
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

        val createLimitOrderResponse = apiClient.createOrder(limitOrderApiRequest)

        // order created correctly
        assertIs<CreateOrderApiRequest.Limit>(createLimitOrderResponse.order)
        assertEquals(createLimitOrderResponse.order.marketId, limitOrderApiRequest.marketId)
        assertEquals(createLimitOrderResponse.order.side, limitOrderApiRequest.side)
        assertEquals(createLimitOrderResponse.order.amount, limitOrderApiRequest.amount)
        assertEquals(0, (createLimitOrderResponse.order as CreateOrderApiRequest.Limit).price.compareTo(limitOrderApiRequest.price))

        // client is notified over websocket
        wsClient.assertOrderCreatedMessageReceived { msg ->
            assertIs<Order.Limit>(msg.order)
            validateLimitOrders(createLimitOrderResponse, msg.order as Order.Limit, false)
        }
        wsClient.close()

        // check that order is included in the orders list sent via websocket
        wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToOrders()
        assertEquals(
            listOf(createLimitOrderResponse.orderId) + initialOrdersOverWs.map { it.id },
            wsClient.assertOrdersMessageReceived().orders.map { it.id },
        )

        // update order
        val updatedOrderApiResponse = apiClient.updateOrder(
            apiRequest = UpdateOrderApiRequest.Limit(
                orderId = createLimitOrderResponse.orderId,
                marketId = createLimitOrderResponse.order.marketId,
                side = createLimitOrderResponse.order.side,
                amount = wallet.formatAmount("3", "USDC"),
                price = BigDecimal("2.01"),
            ),
        )
        assertEquals(updatedOrderApiResponse.requestStatus, RequestStatus.Accepted)
        assertIs<UpdateOrderApiRequest.Limit>(updatedOrderApiResponse.order)
        assertEquals(wallet.formatAmount("3", "USDC"), updatedOrderApiResponse.order.amount)
        assertEquals(0, BigDecimal("2.01").compareTo((updatedOrderApiResponse.order as UpdateOrderApiRequest.Limit).price))
        wsClient.assertOrderUpdatedMessageReceived { msg ->
            assertIs<Order.Limit>(msg.order)
            validateLimitOrders(updatedOrderApiResponse.order, msg.order as Order.Limit, true)
        }

        // cancel order is idempotent
        apiClient.cancelOrder(createLimitOrderResponse.orderId)
        wsClient.assertOrderUpdatedMessageReceived { msg ->
            assertEquals(createLimitOrderResponse.orderId, msg.order.id)
            assertEquals(OrderStatus.Cancelled, msg.order.status)
        }
        val cancelledOrder = apiClient.getOrder(createLimitOrderResponse.orderId)
        assertEquals(OrderStatus.Cancelled, cancelledOrder.status)

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
                    marketId = usdcDaiMarketId,
                    side = OrderSide.Buy,
                    amount = BigDecimal("3").toFundamentalUnits(18),
                    price = BigDecimal("4"),
                ),
            ).assertError(ApiError(ReasonCode.RejectedBySequencer, "Rejected By Sequencer"))

            apiClient.tryCancelOrder(OrderId.generate()).assertError(ApiError(ReasonCode.ProcessingError, expectedError.message))
        }

        // try to submit an order that crosses the market
        val createLimitOrderResponse = apiClient.createOrder(
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

        assertIs<CreateOrderApiRequest.Limit>(createLimitOrderResponse.order)

        wsClient.apply {
            assertOrderCreatedMessageReceived { msg ->
                validateLimitOrders(createLimitOrderResponse, msg.order as Order.Limit, false)
            }
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(createLimitOrderResponse.orderId, msg.order.id)
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

        val createLimitOrderResponse2 = apiClient.createOrder(
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

        wsClient.apply {
            assertOrderCreatedMessageReceived { msg ->
                validateLimitOrders(createLimitOrderResponse2, msg.order as Order.Limit, false)
            }
        }

        // try updating the price to something not a tick size
        apiClient.tryUpdateOrder(
            apiRequest = UpdateOrderApiRequest.Limit(
                orderId = createLimitOrderResponse2.orderId,
                amount = wallet.formatAmount("1", "USDC"),
                marketId = createLimitOrderResponse2.order.marketId,
                side = createLimitOrderResponse2.order.side,
                price = BigDecimal("2.015"),
            ),
        ).assertError(
            ApiError(ReasonCode.ProcessingError, "Order price is not a multiple of tick size"),
        )

        // try updating and cancelling an order not created by this wallet
        val apiClient2 = ApiClient()
        apiClient2.tryUpdateOrder(
            apiRequest = UpdateOrderApiRequest.Limit(
                orderId = createLimitOrderResponse2.orderId,
                amount = wallet.formatAmount("1", "USDC"),
                marketId = createLimitOrderResponse2.order.marketId,
                side = createLimitOrderResponse2.order.side,
                price = BigDecimal("2.01"),
            ),
        ).assertError(
            ApiError(ReasonCode.RejectedBySequencer, "Rejected By Sequencer"),
        )
        apiClient2.tryCancelOrder(
            createLimitOrderResponse2.orderId,
        ).assertError(
            ApiError(ReasonCode.RejectedBySequencer, "Rejected By Sequencer"),
        )

        // try update cancelled order
        apiClient.cancelOrder(createLimitOrderResponse2.orderId)
        wsClient.apply {
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(createLimitOrderResponse2.orderId, msg.order.id)
                assertEquals(OrderStatus.Cancelled, msg.order.status)
            }
        }
        apiClient.tryUpdateOrder(
            apiRequest = UpdateOrderApiRequest.Limit(
                orderId = createLimitOrderResponse2.orderId,
                amount = wallet.formatAmount("3", "USDC"),
                marketId = createLimitOrderResponse2.order.marketId,
                side = createLimitOrderResponse2.order.side,
                price = BigDecimal("2.01"),
            ),
        ).assertError(
            ApiError(ReasonCode.RejectedBySequencer, "Rejected By Sequencer"),
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
        repeat(10) { wsClient.assertOrderCreatedMessageReceived() }
        assertEquals(10, apiClient.listOrders().orders.count { it.status != OrderStatus.Cancelled })

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
        val limitBuyOrderApiResponse = makerApiClient.createOrder(
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
        assertIs<CreateOrderApiRequest.Limit>(limitBuyOrderApiResponse.order)
        makerWsClient.assertOrderCreatedMessageReceived { msg ->
            validateLimitOrders(limitBuyOrderApiResponse, msg.order as Order.Limit, false)
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
                    last = LastTrade("0.000", LastTradeDirection.Unchanged),
                ),
            )
        }

        val updatedLimitBuyOrderApiResponse = makerApiClient.updateOrder(
            UpdateOrderApiRequest.Limit(
                orderId = limitBuyOrderApiResponse.orderId,
                amount = makerWallet.formatAmount("0.00012345", "BTC"),
                marketId = limitBuyOrderApiResponse.order.marketId,
                side = limitBuyOrderApiResponse.order.side,
                price = BigDecimal("17.50"),
            ),
        )
        assertIs<UpdateOrderApiRequest.Limit>(updatedLimitBuyOrderApiResponse.order)
        assertEquals(RequestStatus.Accepted, updatedLimitBuyOrderApiResponse.requestStatus)

        makerWsClient.assertOrderUpdatedMessageReceived { msg ->
            validateLimitOrders(updatedLimitBuyOrderApiResponse.order, msg.order as Order.Limit, true)
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
                    last = LastTrade("0.000", LastTradeDirection.Unchanged),
                ),
            )
        }

        // place a sell order
        val limitSellOrderApiResponse = makerApiClient.createOrder(
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
        assertIs<CreateOrderApiRequest.Limit>(limitSellOrderApiResponse.order)

        makerWsClient.assertOrderCreatedMessageReceived { msg ->
            validateLimitOrders(limitSellOrderApiResponse, msg.order as Order.Limit, false)
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
                    last = LastTrade("0.000", LastTradeDirection.Unchanged),
                ),
            )
        }

        // update amount and price of the sell
        val updatedLimitSellOrderApiResponse = makerApiClient.updateOrder(
            UpdateOrderApiRequest.Limit(
                orderId = limitSellOrderApiResponse.orderId,
                amount = makerWallet.formatAmount("0.00054321", "BTC"),
                marketId = limitSellOrderApiResponse.order.marketId,
                side = limitSellOrderApiResponse.order.side,
                price = BigDecimal("17.550"),
            ),
        )
        assertIs<UpdateOrderApiRequest.Limit>(updatedLimitSellOrderApiResponse.order)
        assertEquals(RequestStatus.Accepted, updatedLimitSellOrderApiResponse.requestStatus)

        makerWsClient.assertOrderUpdatedMessageReceived { msg ->
            assertIs<Order.Limit>(msg.order)
            validateLimitOrders(updatedLimitSellOrderApiResponse.order, msg.order as Order.Limit, true)
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
                    last = LastTrade("0.000", LastTradeDirection.Unchanged),
                ),
            )
        }

        // place a buy order and see it gets executed
        val marketBuyOrderApiResponse = takerApiClient.createOrder(
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

        assertIs<CreateOrderApiRequest.Market>(marketBuyOrderApiResponse.order)

        takerWsClient.apply {
            assertOrderCreatedMessageReceived { msg ->
                assertEquals(marketBuyOrderApiResponse.orderId, msg.order.id)
                assertEquals(marketBuyOrderApiResponse.order.amount, msg.order.amount)
                assertEquals(marketBuyOrderApiResponse.order.side, msg.order.side)
                assertEquals(marketBuyOrderApiResponse.order.marketId, msg.order.marketId)
                assertNotNull(msg.order.timing.createdAt)
            }
            assertTradeCreatedMessageReceived { msg ->
                assertEquals(marketBuyOrderApiResponse.orderId, msg.trade.orderId)
                assertEquals(marketBuyOrderApiResponse.order.marketId, msg.trade.marketId)
                assertEquals(marketBuyOrderApiResponse.order.side, msg.trade.side)
                assertEquals(0, (updatedLimitSellOrderApiResponse.order as UpdateOrderApiRequest.Limit).price.compareTo(msg.trade.price))
                assertEquals(marketBuyOrderApiResponse.order.amount, msg.trade.amount)
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
                assertEquals(updatedLimitSellOrderApiResponse.order.orderId, msg.trade.orderId)
                assertEquals(updatedLimitSellOrderApiResponse.order.marketId, msg.trade.marketId)
                assertEquals(updatedLimitSellOrderApiResponse.order.side, msg.trade.side)
                assertEquals(0, (updatedLimitSellOrderApiResponse.order as UpdateOrderApiRequest.Limit).price.compareTo(msg.trade.price))
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
                        OrderBookEntry(price = "17.550", size = "0.00011111".toBigDecimal()),
                    ),
                    last = LastTrade("17.550", LastTradeDirection.Up),
                ),
            )
        }

        val trade = getTradesForOrders(listOf(marketBuyOrderApiResponse.orderId)).first()

        assertEquals(trade.amount, takerWallet.formatAmount("0.00043210", "BTC"))
        assertEquals(0, trade.price.compareTo(BigDecimal("17.550")))

        waitForSettlementToFinish(listOf(trade.id.value))
        takerWsClient.assertPricesMessageReceived(btcEthMarketId) { msg ->
            assertEquals(
                OHLC(
                    start = OHLCDuration.P5M.durationStart(trade.timestamp),
                    open = 17.55,
                    high = 17.55,
                    low = 17.55,
                    close = 17.55,
                    duration = OHLCDuration.P5M,
                ),
                msg.ohlc.last(),
            )
        }
        makerWsClient.assertPricesMessageReceived(btcEthMarketId) { msg ->
            assertEquals(
                OHLC(
                    start = OHLCDuration.P5M.durationStart(trade.timestamp),
                    open = 17.55,
                    high = 17.55,
                    low = 17.55,
                    close = 17.55,
                    duration = OHLCDuration.P5M,
                ),
                msg.ohlc.last(),
            )
        }
        takerWsClient.assertTradeUpdatedMessageReceived { msg ->
            assertEquals(marketBuyOrderApiResponse.orderId, msg.trade.orderId)
            assertEquals(SettlementStatus.Completed, msg.trade.settlementStatus)
        }
        makerWsClient.assertTradeUpdatedMessageReceived { msg ->
            assertEquals(updatedLimitSellOrderApiResponse.order.orderId, msg.trade.orderId)
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
        val marketSellOrderApiResponse = takerApiClient.createOrder(
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

        assertIs<CreateOrderApiRequest.Market>(marketSellOrderApiResponse.order)

        takerWsClient.apply {
            assertOrderCreatedMessageReceived { msg ->
                assertEquals(marketSellOrderApiResponse.orderId, msg.order.id)
                assertEquals(marketSellOrderApiResponse.order.amount, msg.order.amount)
                assertEquals(marketSellOrderApiResponse.order.side, msg.order.side)
                assertEquals(marketSellOrderApiResponse.order.marketId, msg.order.marketId)
                assertNotNull(msg.order.timing.createdAt)
            }
            assertTradeCreatedMessageReceived { msg ->
                assertEquals(marketSellOrderApiResponse.orderId, msg.trade.orderId)
                assertEquals(marketSellOrderApiResponse.order.marketId, msg.trade.marketId)
                assertEquals(marketSellOrderApiResponse.order.side, msg.trade.side)
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
                assertEquals(updatedLimitBuyOrderApiResponse.order.orderId, msg.trade.orderId)
                assertEquals(updatedLimitBuyOrderApiResponse.order.marketId, msg.trade.marketId)
                assertEquals(updatedLimitBuyOrderApiResponse.order.side, msg.trade.side)
                assertEquals(0, (updatedLimitBuyOrderApiResponse.order as UpdateOrderApiRequest.Limit).price.compareTo(msg.trade.price))
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
                        OrderBookEntry(price = "17.550", size = "0.00011111".toBigDecimal()),
                    ),
                    last = LastTrade("17.500", LastTradeDirection.Down),
                ),
            )
        }

        val trade2 = getTradesForOrders(listOf(marketSellOrderApiResponse.orderId)).first()
        assertEquals(trade2.amount, takerWallet.formatAmount("0.00012345", "BTC"))
        assertEquals(0, trade2.price.compareTo(BigDecimal("17.500")))

        waitForSettlementToFinish(listOf(trade2.id.value))
        takerWsClient.assertPricesMessageReceived(btcEthMarketId) { msg ->
            assertEquals(
                OHLC(
                    start = OHLCDuration.P5M.durationStart(trade.timestamp),
                    open = 17.55,
                    high = 17.55,
                    low = 17.5,
                    close = 17.5,
                    duration = OHLCDuration.P5M,
                ),
                msg.ohlc.last(),
            )
        }
        makerWsClient.assertPricesMessageReceived(btcEthMarketId) { msg ->
            assertEquals(
                OHLC(
                    start = OHLCDuration.P5M.durationStart(trade.timestamp),
                    open = 17.55,
                    high = 17.55,
                    low = 17.5,
                    close = 17.5,
                    duration = OHLCDuration.P5M,
                ),
                msg.ohlc.last(),
            )
        }
        takerWsClient.assertTradeUpdatedMessageReceived { msg ->
            assertEquals(marketSellOrderApiResponse.orderId, msg.trade.orderId)
            assertEquals(SettlementStatus.Completed, msg.trade.settlementStatus)
        }
        makerWsClient.assertTradeUpdatedMessageReceived { msg ->
            assertEquals(updatedLimitBuyOrderApiResponse.order.orderId, msg.trade.orderId)
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
                    assertEquals(marketSellOrderApiResponse.orderId, order.id)
                    assertEquals(marketSellOrderApiResponse.order.marketId, order.marketId)
                    assertEquals(marketSellOrderApiResponse.order.side, order.side)
                    assertEquals(marketSellOrderApiResponse.order.amount, order.amount)
                    assertEquals(OrderStatus.Partial, order.status)
                }
                msg.orders[1].also { order ->
                    assertIs<Order.Market>(order)
                    assertEquals(marketBuyOrderApiResponse.orderId, order.id)
                    assertEquals(marketBuyOrderApiResponse.order.marketId, order.marketId)
                    assertEquals(marketBuyOrderApiResponse.order.side, order.side)
                    assertEquals(marketBuyOrderApiResponse.order.amount, order.amount)
                    assertEquals(OrderStatus.Filled, order.status)
                }
            }

            subscribeToTrades()
            assertTradesMessageReceived { msg ->
                assertEquals(2, msg.trades.size)
                msg.trades[0].apply {
                    assertEquals(marketSellOrderApiResponse.orderId, orderId)
                    assertEquals(marketSellOrderApiResponse.order.marketId, marketId)
                    assertEquals(marketSellOrderApiResponse.order.side, side)
                    assertEquals(0, price.compareTo(BigDecimal("17.500")))
                    assertEquals(makerWallet.formatAmount("0.00012345", "BTC"), amount)
                    assertEquals(SettlementStatus.Completed, settlementStatus)
                }
                msg.trades[1].apply {
                    assertEquals(marketBuyOrderApiResponse.orderId, orderId)
                    assertEquals(marketBuyOrderApiResponse.order.marketId, marketId)
                    assertEquals(marketBuyOrderApiResponse.order.side, side)
                    assertEquals(0, (updatedLimitSellOrderApiResponse.order as UpdateOrderApiRequest.Limit).price.compareTo(price))
                    assertEquals(marketBuyOrderApiResponse.order.amount, amount)
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

        assertEquals(3, createBatchLimitOrders.createdOrders.count())
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
                        orderId = createBatchLimitOrders.createdOrders[0].orderId,
                        amount = makerWallet.formatAmount("0.0001", "BTC"),
                        marketId = createBatchLimitOrders.createdOrders[0].order.marketId,
                        side = createBatchLimitOrders.createdOrders[0].order.side,
                        price = BigDecimal("68405.000"),
                    ),
                    UpdateOrderApiRequest.Limit(
                        orderId = createBatchLimitOrders.createdOrders[1].orderId,
                        amount = makerWallet.formatAmount("0.0002", "BTC"),
                        marketId = createBatchLimitOrders.createdOrders[1].order.marketId,
                        side = createBatchLimitOrders.createdOrders[2].order.side,
                        price = BigDecimal("68405.000"),
                    ),
                    UpdateOrderApiRequest.Limit(
                        orderId = OrderId.generate(),
                        amount = makerWallet.formatAmount("0.0002", "BTC"),
                        marketId = createBatchLimitOrders.createdOrders[1].order.marketId,
                        side = createBatchLimitOrders.createdOrders[2].order.side,
                        price = BigDecimal("68405.000"),
                    ),
                ),
                cancelOrders = listOf(
                    CancelOrderApiRequest(orderId = createBatchLimitOrders.createdOrders[2].orderId),
                    CancelOrderApiRequest(orderId = OrderId.generate()),
                ),
            ),
        )

        assertEquals(3, batchOrderResponse.createdOrders.count { it.requestStatus == RequestStatus.Accepted })
        assertEquals(2, batchOrderResponse.updatedOrders.count { it.requestStatus == RequestStatus.Accepted })
        assertEquals(1, batchOrderResponse.updatedOrders.count { it.requestStatus == RequestStatus.Rejected })
        assertEquals(1, batchOrderResponse.canceledOrders.count { it.requestStatus == RequestStatus.Accepted })
        assertEquals(1, batchOrderResponse.canceledOrders.count { it.requestStatus == RequestStatus.Rejected })

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

    private fun validateLimitOrders(response: CreateOrderApiResponse, order: Order.Limit, updated: Boolean) {
        assertEquals(response.orderId, order.id)
        assertEquals(response.order.amount, order.amount)
        assertEquals(response.order.side, order.side)
        assertEquals(response.order.marketId, order.marketId)
        assertEquals(0, (response.order as CreateOrderApiRequest.Limit).price.compareTo(order.price))
        assertNotNull(order.timing.createdAt)
        if (updated) {
            assertNotNull(order.timing.updatedAt)
        }
    }

    private fun validateLimitOrders(response: UpdateOrderApiRequest, order: Order.Limit, updated: Boolean) {
        assertEquals(response.orderId, order.id)
        assertEquals(response.amount, order.amount)
        assertEquals(response.side, order.side)
        assertEquals(response.marketId, order.marketId)
        assertEquals(0, (response as UpdateOrderApiRequest.Limit).price.compareTo(order.price))
        assertNotNull(order.timing.createdAt)
        if (updated) {
            assertNotNull(order.timing.updatedAt)
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

            subscribeToPrices(marketId)
            assertPricesMessageReceived(marketId)

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
            .atMost(Duration.ofMillis(20000L))
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
