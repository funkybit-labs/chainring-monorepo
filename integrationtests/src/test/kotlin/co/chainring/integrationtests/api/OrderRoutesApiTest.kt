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
import co.chainring.core.client.ws.blocking
import co.chainring.core.client.ws.subscribeToBalances
import co.chainring.core.client.ws.subscribeToLimits
import co.chainring.core.client.ws.subscribeToOrderBook
import co.chainring.core.client.ws.subscribeToOrders
import co.chainring.core.client.ws.subscribeToPrices
import co.chainring.core.client.ws.subscribeToTrades
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.ExchangeTransactionEntity
import co.chainring.core.model.db.ExchangeTransactionStatus
import co.chainring.core.model.db.ExchangeTransactionTable
import co.chainring.core.model.db.ExecutionRole
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OHLCDuration
import co.chainring.core.model.db.OrderEntity
import co.chainring.core.model.db.OrderExecutionEntity
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.OrderStatus
import co.chainring.core.model.db.SettlementStatus
import co.chainring.core.model.db.TradeEntity
import co.chainring.core.model.db.TradeId
import co.chainring.core.model.db.TradeTable
import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.model.db.WithdrawalStatus
import co.chainring.core.utils.fromFundamentalUnits
import co.chainring.core.utils.generateOrderNonce
import co.chainring.core.utils.toFundamentalUnits
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.testutils.waitForBalance
import co.chainring.integrationtests.testutils.waitForFinalizedWithdrawal
import co.chainring.integrationtests.utils.ExchangeContractManager
import co.chainring.integrationtests.utils.ExpectedBalance
import co.chainring.integrationtests.utils.Faucet
import co.chainring.integrationtests.utils.TestApiClient
import co.chainring.integrationtests.utils.Wallet
import co.chainring.integrationtests.utils.assertBalances
import co.chainring.integrationtests.utils.assertBalancesMessageReceived
import co.chainring.integrationtests.utils.assertError
import co.chainring.integrationtests.utils.assertLimitsMessageReceived
import co.chainring.integrationtests.utils.assertOrderBookMessageReceived
import co.chainring.integrationtests.utils.assertOrderCreatedMessageReceived
import co.chainring.integrationtests.utils.assertOrderUpdatedMessageReceived
import co.chainring.integrationtests.utils.assertOrdersMessageReceived
import co.chainring.integrationtests.utils.assertPricesMessageReceived
import co.chainring.integrationtests.utils.assertTradeCreatedMessageReceived
import co.chainring.integrationtests.utils.assertTradeUpdatedMessageReceived
import co.chainring.integrationtests.utils.assertTradesMessageReceived
import co.chainring.sequencer.core.sum
import co.chainring.tasks.fixtures.toChainSymbol
import kotlinx.datetime.Clock
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withAlias
import org.http4k.client.WebsocketClient
import org.http4k.websocket.WsClient
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull

@ExtendWith(AppUnderTestRunner::class)
class OrderRoutesApiTest {
    private val usdcDaiMarketId = MarketId("USDC/DAI")

    companion object {
        @JvmStatic
        fun chainIndices() = listOf(
            Arguments.of(0),
            Arguments.of(1),
        )
    }

    @Test
    fun `CRUD order`() {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)

        var wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToOrders()
        val initialOrdersOverWs = wsClient.assertOrdersMessageReceived().orders

        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        wsClient.subscribeToLimits(usdcDaiMarketId)
        wsClient.assertLimitsMessageReceived(usdcDaiMarketId)

        Faucet.fund(wallet.address)
        wallet.mintERC20("DAI", wallet.formatAmount("14", "DAI"))

        val amountToDeposit = wallet.formatAmount("14", "DAI")
        deposit(wallet, "DAI", amountToDeposit)
        waitForBalance(apiClient, wsClient, listOf(ExpectedBalance("DAI", amountToDeposit, amountToDeposit)))

        wsClient.assertLimitsMessageReceived(usdcDaiMarketId) { msg ->
            assertEquals(BigInteger("0"), msg.base)
            assertEquals(amountToDeposit, msg.quote)
        }

        val limitOrderApiRequest = CreateOrderApiRequest.Limit(
            nonce = generateOrderNonce(),
            marketId = usdcDaiMarketId,
            side = OrderSide.Buy,
            amount = wallet.formatAmount("1", "USDC"),
            price = BigDecimal("2"),
            signature = EvmSignature.emptySignature(),
            verifyingChainId = ChainId.empty,
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
            validateNonceAndSignatureStored(createLimitOrderResponse.orderId, limitOrderApiRequest.nonce, limitOrderApiRequest.signature)
        }
        wsClient.assertLimitsMessageReceived(usdcDaiMarketId) { msg ->
            assertEquals(BigInteger("0"), msg.base)
            assertEquals(BigInteger("13999999999998000000"), msg.quote)
        }
        wsClient.close()

        // check that order is included in the orders list sent via websocket
        wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToOrders()
        assertEquals(
            listOf(createLimitOrderResponse.orderId) + initialOrdersOverWs.map { it.id },
            wsClient.assertOrdersMessageReceived().orders.map { it.id },
        )

        wsClient.subscribeToLimits(usdcDaiMarketId)
        wsClient.assertLimitsMessageReceived(usdcDaiMarketId)

        // update order
        val updateOrderApiRequest = UpdateOrderApiRequest(
            orderId = createLimitOrderResponse.orderId,
            marketId = createLimitOrderResponse.order.marketId,
            side = createLimitOrderResponse.order.side,
            amount = wallet.formatAmount("3", "USDC"),
            price = BigDecimal("2.01"),
            nonce = generateOrderNonce(),
            signature = EvmSignature.emptySignature(),
            verifyingChainId = ChainId.empty,
        ).let {
            wallet.signOrder(it)
        }
        val updatedOrderApiResponse = apiClient.updateOrder(
            updateOrderApiRequest,
        )
        assertEquals(updatedOrderApiResponse.requestStatus, RequestStatus.Accepted)
        assertIs<UpdateOrderApiRequest>(updatedOrderApiResponse.order)
        assertEquals(wallet.formatAmount("3", "USDC"), updatedOrderApiResponse.order.amount)
        assertEquals(0, BigDecimal("2.01").compareTo(updatedOrderApiResponse.order.price))
        wsClient.assertOrderUpdatedMessageReceived { msg ->
            assertIs<Order.Limit>(msg.order)
            validateLimitOrders(updatedOrderApiResponse.order, msg.order as Order.Limit, true)
            validateNonceAndSignatureStored(createLimitOrderResponse.orderId, updateOrderApiRequest.nonce, updateOrderApiRequest.signature)
        }
        wsClient.assertLimitsMessageReceived(usdcDaiMarketId) { msg ->
            assertEquals(BigInteger("0"), msg.base)
            assertEquals(BigInteger("13999999999993970000"), msg.quote)
        }

        // cancel order is idempotent
        apiClient.cancelOrder(
            CancelOrderApiRequest(
                orderId = createLimitOrderResponse.orderId,
                marketId = createLimitOrderResponse.order.marketId,
                amount = createLimitOrderResponse.order.amount,
                side = createLimitOrderResponse.order.side,
                nonce = generateOrderNonce(),
                signature = EvmSignature.emptySignature(),
                verifyingChainId = ChainId.empty,
            ).let {
                wallet.signCancelOrder(it)
            },
        )
        wsClient.assertOrderUpdatedMessageReceived { msg ->
            assertEquals(createLimitOrderResponse.orderId, msg.order.id)
            assertEquals(OrderStatus.Cancelled, msg.order.status)
        }
        wsClient.assertLimitsMessageReceived(usdcDaiMarketId) { msg ->
            assertEquals(BigInteger("0"), msg.base)
            assertEquals(BigInteger("14000000000000000000"), msg.quote)
        }
        val cancelledOrder = apiClient.getOrder(createLimitOrderResponse.orderId)
        assertEquals(OrderStatus.Cancelled, cancelledOrder.status)

        wsClient.close()
    }

    @Test
    fun `CRUD order error cases`() {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)

        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToOrders()
        wsClient.assertOrdersMessageReceived()

        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        Faucet.fund(wallet.address)
        val amountToDeposit = wallet.formatAmount("20", "DAI")
        wallet.mintERC20("DAI", amountToDeposit)
        deposit(wallet, "DAI", amountToDeposit)
        waitForBalance(apiClient, wsClient, listOf(ExpectedBalance("DAI", amountToDeposit, amountToDeposit)))

        // operation on non-existent order
        apiClient.tryGetOrder(OrderId.generate())
            .assertError(ApiError(ReasonCode.OrderNotFound, "Requested order does not exist"))

        apiClient.tryUpdateOrder(
            apiRequest = UpdateOrderApiRequest(
                orderId = OrderId.generate(),
                marketId = usdcDaiMarketId,
                side = OrderSide.Buy,
                amount = BigDecimal("3").toFundamentalUnits(18),
                price = BigDecimal("4"),
                nonce = generateOrderNonce(),
                signature = EvmSignature.emptySignature(),
                verifyingChainId = ChainId.empty,
            ).let {
                wallet.signOrder(it)
            },
        ).assertError(ApiError(ReasonCode.RejectedBySequencer, "Order does not exist or is already finalized"))

        apiClient.tryCancelOrder(
            CancelOrderApiRequest(
                orderId = OrderId.generate(),
                marketId = usdcDaiMarketId,
                amount = BigInteger.ZERO,
                side = OrderSide.Buy,
                nonce = generateOrderNonce(),
                signature = EvmSignature.emptySignature(),
                verifyingChainId = ChainId.empty,
            ).let {
                wallet.signCancelOrder(it)
            },
        ).assertError(ApiError(ReasonCode.RejectedBySequencer, "Order does not exist or is already finalized"))

        // invalid signature (malformed signature)
        apiClient.tryUpdateOrder(
            apiRequest = UpdateOrderApiRequest(
                orderId = OrderId.generate(),
                marketId = usdcDaiMarketId,
                side = OrderSide.Buy,
                amount = BigDecimal("3").toFundamentalUnits(18),
                price = BigDecimal("4"),
                nonce = generateOrderNonce(),
                signature = EvmSignature.emptySignature(),
                verifyingChainId = wallet.currentChainId,
            ),
        ).assertError(ApiError(ReasonCode.SignatureNotValid, "Invalid signature"))

        // try creating a limit order not a multiple of tick size
        apiClient.tryCreateOrder(
            CreateOrderApiRequest.Limit(
                nonce = generateOrderNonce(),
                marketId = usdcDaiMarketId,
                side = OrderSide.Buy,
                amount = wallet.formatAmount("1", "USDC"),
                price = BigDecimal("2.015"),
                signature = EvmSignature.emptySignature(),
                verifyingChainId = ChainId.empty,
            ).let {
                wallet.signOrder(it)
            },
        ).assertError(
            ApiError(ReasonCode.ProcessingError, "Order price is not a multiple of tick size"),
        )

        val createLimitOrderResponse2 = apiClient.createOrder(
            CreateOrderApiRequest.Limit(
                nonce = generateOrderNonce(),
                marketId = usdcDaiMarketId,
                side = OrderSide.Buy,
                amount = wallet.formatAmount("1", "USDC"),
                price = BigDecimal("2"),
                signature = EvmSignature.emptySignature(),
                verifyingChainId = ChainId.empty,
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
            apiRequest = UpdateOrderApiRequest(
                orderId = createLimitOrderResponse2.orderId,
                amount = wallet.formatAmount("1", "USDC"),
                marketId = createLimitOrderResponse2.order.marketId,
                side = createLimitOrderResponse2.order.side,
                price = BigDecimal("2.015"),
                nonce = generateOrderNonce(),
                signature = EvmSignature.emptySignature(),
                verifyingChainId = ChainId.empty,
            ).let {
                wallet.signOrder(it)
            },
        ).assertError(
            ApiError(ReasonCode.ProcessingError, "Order price is not a multiple of tick size"),
        )

        // try updating and cancelling an order not created by this wallet - signature should fail
        val apiClient2 = TestApiClient()
        val wallet2 = Wallet(apiClient2)
        apiClient2.tryUpdateOrder(
            apiRequest = UpdateOrderApiRequest(
                orderId = createLimitOrderResponse2.orderId,
                amount = wallet.formatAmount("1", "USDC"),
                marketId = createLimitOrderResponse2.order.marketId,
                side = createLimitOrderResponse2.order.side,
                price = BigDecimal("2.01"),
                nonce = generateOrderNonce(),
                signature = EvmSignature.emptySignature(),
                verifyingChainId = ChainId.empty,
            ).let {
                wallet.signOrder(it)
            },
        ).assertError(
            ApiError(ReasonCode.SignatureNotValid, "Invalid signature"),
        )
        apiClient2.tryCancelOrder(
            CancelOrderApiRequest(
                orderId = createLimitOrderResponse2.orderId,
                marketId = createLimitOrderResponse2.order.marketId,
                amount = createLimitOrderResponse2.order.amount,
                side = createLimitOrderResponse2.order.side,
                nonce = generateOrderNonce(),
                signature = EvmSignature.emptySignature(),
                verifyingChainId = ChainId.empty,
            ).let {
                wallet2.signCancelOrder(it)
            },
        ).assertError(
            ApiError(ReasonCode.RejectedBySequencer, "Order not created by this wallet"),
        )

        // invalid signature
        apiClient.tryCancelOrder(
            CancelOrderApiRequest(
                orderId = createLimitOrderResponse2.orderId,
                marketId = createLimitOrderResponse2.order.marketId,
                amount = createLimitOrderResponse2.order.amount,
                side = createLimitOrderResponse2.order.side,
                nonce = generateOrderNonce(),
                signature = EvmSignature.emptySignature(),
                verifyingChainId = ChainId.empty,
            ).let {
                wallet2.signCancelOrder(it)
            },
        ).assertError(
            ApiError(ReasonCode.SignatureNotValid, "Invalid signature"),
        )

        // try update cancelled order
        apiClient.cancelOrder(
            CancelOrderApiRequest(
                orderId = createLimitOrderResponse2.orderId,
                marketId = createLimitOrderResponse2.order.marketId,
                amount = createLimitOrderResponse2.order.amount,
                side = createLimitOrderResponse2.order.side,
                nonce = generateOrderNonce(),
                signature = EvmSignature.emptySignature(),
                verifyingChainId = ChainId.empty,
            ).let {
                wallet.signCancelOrder(it)
            },
        )
        wsClient.apply {
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(createLimitOrderResponse2.orderId, msg.order.id)
                assertEquals(OrderStatus.Cancelled, msg.order.status)
            }
        }
        apiClient.tryUpdateOrder(
            apiRequest = UpdateOrderApiRequest(
                orderId = createLimitOrderResponse2.orderId,
                amount = wallet.formatAmount("3", "USDC"),
                marketId = createLimitOrderResponse2.order.marketId,
                side = createLimitOrderResponse2.order.side,
                price = BigDecimal("2.01"),
                nonce = generateOrderNonce(),
                signature = EvmSignature.emptySignature(),
                verifyingChainId = ChainId.empty,
            ).let {
                wallet.signOrder(it)
            },
        ).assertError(
            ApiError(ReasonCode.RejectedBySequencer, "Order does not exist or is already finalized"),
        )
    }

    @Test
    fun `list and cancel all open orders`() {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)

        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToOrders()
        val initialOrdersOverWs = wsClient.assertOrdersMessageReceived().orders

        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        wsClient.subscribeToLimits(usdcDaiMarketId)
        wsClient.assertLimitsMessageReceived(usdcDaiMarketId)

        Faucet.fund(wallet.address)
        val amountToDeposit = wallet.formatAmount("30", "DAI")
        wallet.mintERC20("DAI", amountToDeposit)
        deposit(wallet, "DAI", amountToDeposit)
        waitForBalance(apiClient, wsClient, listOf(ExpectedBalance("DAI", amountToDeposit, amountToDeposit)))
        wsClient.assertLimitsMessageReceived(usdcDaiMarketId)

        val limitOrderApiRequest = CreateOrderApiRequest.Limit(
            nonce = generateOrderNonce(),
            marketId = usdcDaiMarketId,
            side = OrderSide.Buy,
            amount = wallet.formatAmount("1", "USDC"),
            price = BigDecimal("2"),
            signature = EvmSignature.emptySignature(),
            verifyingChainId = ChainId.empty,
        ).let {
            wallet.signOrder(it)
        }
        repeat(times = 10) {
            apiClient.createOrder(wallet.signOrder(limitOrderApiRequest.copy(nonce = generateOrderNonce())))
        }
        repeat(10) {
            wsClient.assertOrderCreatedMessageReceived()
            wsClient.assertLimitsMessageReceived(usdcDaiMarketId)
        }
        assertEquals(10, apiClient.listOrders().orders.count { it.status != OrderStatus.Cancelled })

        apiClient.cancelOpenOrders()

        wsClient.assertOrdersMessageReceived { msg ->
            assertNotEquals(initialOrdersOverWs, msg.orders)
            assertTrue(msg.orders.all { it.status == OrderStatus.Cancelled })
        }
        wsClient.assertLimitsMessageReceived(usdcDaiMarketId)

        assertTrue(apiClient.listOrders().orders.all { it.status == OrderStatus.Cancelled })

        wsClient.close()
    }

    @ParameterizedTest
    @MethodSource("chainIndices")
    fun `order execution`(chainIndex: Int) {
        val exchangeContractManager = ExchangeContractManager()

        val ethSymbol = "ETH".toChainSymbol(chainIndex)
        val btcSymbol = "BTC".toChainSymbol(chainIndex)
        val btcEthMarketId = MarketId("$btcSymbol/$ethSymbol")

        val initialFeeAccountEthBalance = exchangeContractManager.getFeeBalance(ethSymbol)

        val (takerApiClient, takerWallet, takerWsClient) =
            setupTrader(btcEthMarketId, "0.5", null, ethSymbol, "2", chainIndex = chainIndex)

        val (makerApiClient, makerWallet, makerWsClient) =
            setupTrader(btcEthMarketId, "0.5", "0.2", ethSymbol, "2", chainIndex = chainIndex)

        // starting onchain balances
        val makerStartingBTCBalance = makerWallet.getExchangeNativeBalance()
        val makerStartingETHBalance = makerWallet.getExchangeERC20Balance(ethSymbol)
        val takerStartingBTCBalance = takerWallet.getExchangeNativeBalance()
        val takerStartingETHBalance = takerWallet.getExchangeERC20Balance(ethSymbol)

        // place an order and see that it gets accepted
        val limitBuyOrderApiResponse = makerApiClient.createOrder(
            CreateOrderApiRequest.Limit(
                nonce = generateOrderNonce(),
                marketId = btcEthMarketId,
                side = OrderSide.Buy,
                amount = makerWallet.formatAmount("0.00013345", btcSymbol),
                price = BigDecimal("17.45"),
                signature = EvmSignature.emptySignature(),
                verifyingChainId = ChainId.empty,
            ).let {
                makerWallet.signOrder(it)
            },
        )
        assertIs<CreateOrderApiRequest.Limit>(limitBuyOrderApiResponse.order)
        makerWsClient.apply {
            assertOrderCreatedMessageReceived { msg ->
                validateLimitOrders(limitBuyOrderApiResponse, msg.order as Order.Limit, false)
            }
            assertLimitsMessageReceived(btcEthMarketId) { msg ->
                assertEquals(BigInteger("200000000000000000"), msg.base)
                assertEquals(BigInteger("1997671297500000000"), msg.quote)
            }
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
            UpdateOrderApiRequest(
                orderId = limitBuyOrderApiResponse.orderId,
                amount = makerWallet.formatAmount("0.00012345", btcSymbol),
                marketId = limitBuyOrderApiResponse.order.marketId,
                side = limitBuyOrderApiResponse.order.side,
                price = BigDecimal("17.50"),
                nonce = generateOrderNonce(),
                signature = EvmSignature.emptySignature(),
                verifyingChainId = ChainId.empty,
            ).let {
                makerWallet.signOrder(it)
            },
        )
        assertIs<UpdateOrderApiRequest>(updatedLimitBuyOrderApiResponse.order)
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
        makerWsClient.assertLimitsMessageReceived(btcEthMarketId) { msg ->
            assertEquals(BigInteger("200000000000000000"), msg.base)
            assertEquals(BigInteger("1997839625000000000"), msg.quote)
        }

        // place a sell order
        val limitSellOrderApiResponse = makerApiClient.createOrder(
            CreateOrderApiRequest.Limit(
                nonce = generateOrderNonce(),
                marketId = btcEthMarketId,
                side = OrderSide.Sell,
                amount = makerWallet.formatAmount("0.00154321", btcSymbol),
                price = BigDecimal("17.600"),
                signature = EvmSignature.emptySignature(),
                verifyingChainId = ChainId.empty,
            ).let {
                makerWallet.signOrder(it)
            },
        )
        assertIs<CreateOrderApiRequest.Limit>(limitSellOrderApiResponse.order)

        makerWsClient.apply {
            assertOrderCreatedMessageReceived { msg ->
                validateLimitOrders(limitSellOrderApiResponse, msg.order as Order.Limit, false)
            }
            assertLimitsMessageReceived(btcEthMarketId) { msg ->
                assertEquals(BigInteger("198456790000000000"), msg.base)
                assertEquals(BigInteger("1997839625000000000"), msg.quote)
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
                        OrderBookEntry(price = "17.600", size = "0.00154321".toBigDecimal()),
                    ),
                    last = LastTrade("0.000", LastTradeDirection.Unchanged),
                ),
            )
        }

        // update amount and price of the sell
        val updatedLimitSellOrderApiResponse = makerApiClient.updateOrder(
            UpdateOrderApiRequest(
                orderId = limitSellOrderApiResponse.orderId,
                amount = makerWallet.formatAmount("0.00054321", btcSymbol),
                marketId = limitSellOrderApiResponse.order.marketId,
                side = limitSellOrderApiResponse.order.side,
                price = BigDecimal("17.550"),
                nonce = generateOrderNonce(),
                signature = EvmSignature.emptySignature(),
                verifyingChainId = ChainId.empty,
            ).let {
                makerWallet.signOrder(it)
            },
        )
        assertIs<UpdateOrderApiRequest>(updatedLimitSellOrderApiResponse.order)
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
        makerWsClient.assertLimitsMessageReceived(btcEthMarketId) { msg ->
            assertEquals(BigInteger("199456790000000000"), msg.base)
            assertEquals(BigInteger("1997839625000000000"), msg.quote)
        }

        // place a buy order and see it gets executed
        val marketBuyOrderApiResponse = takerApiClient.createOrder(
            CreateOrderApiRequest.Market(
                nonce = generateOrderNonce(),
                marketId = btcEthMarketId,
                side = OrderSide.Buy,
                amount = takerWallet.formatAmount("0.00043210", btcSymbol),
                signature = EvmSignature.emptySignature(),
                verifyingChainId = ChainId.empty,
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
                assertEquals(0, (updatedLimitSellOrderApiResponse.order).price.compareTo(msg.trade.price))
                assertEquals(marketBuyOrderApiResponse.order.amount, msg.trade.amount)
                assertEquals(takerWallet.formatAmount("0.0001516671", ethSymbol), msg.trade.feeAmount)
                assertEquals(ethSymbol, msg.trade.feeSymbol.value)
                assertEquals(SettlementStatus.Pending, msg.trade.settlementStatus)
            }
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Filled, msg.order.status)
                assertEquals(1, msg.order.executions.size)
                assertEquals(msg.order.executions[0].amount, takerWallet.formatAmount("0.00043210", btcSymbol))
                assertEquals(0, msg.order.executions[0].price.compareTo(BigDecimal("17.550")))
                assertEquals(msg.order.executions[0].role, ExecutionRole.Taker)
            }
            assertBalancesMessageReceived { msg ->
                assertEquals(
                    BigInteger("1992264977900000000"),
                    msg.balances.first { it.symbol.value == ethSymbol }.available,
                )
                assertEquals(
                    BigInteger("432100000000000"),
                    msg.balances.first { it.symbol.value == btcSymbol }.available,
                )
            }
        }

        makerWsClient.apply {
            assertTradeCreatedMessageReceived { msg ->
                assertEquals(updatedLimitSellOrderApiResponse.order.orderId, msg.trade.orderId)
                assertEquals(updatedLimitSellOrderApiResponse.order.marketId, msg.trade.marketId)
                assertEquals(updatedLimitSellOrderApiResponse.order.side, msg.trade.side)
                assertEquals(0, (updatedLimitSellOrderApiResponse.order).price.compareTo(msg.trade.price))
                assertEquals(makerWallet.formatAmount("0.00043210", btcSymbol), msg.trade.amount)
                assertEquals(makerWallet.formatAmount("0.00007583355", ethSymbol), msg.trade.feeAmount)
                assertEquals(ethSymbol, msg.trade.feeSymbol.value)
                assertEquals(SettlementStatus.Pending, msg.trade.settlementStatus)
            }
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Partial, msg.order.status)
                assertEquals(1, msg.order.executions.size)
                assertEquals(msg.order.executions[0].amount, makerWallet.formatAmount("0.00043210", btcSymbol))
                assertEquals(0, msg.order.executions[0].price.compareTo(BigDecimal("17.550")))
                assertEquals(ExecutionRole.Maker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived { msg ->
                assertEquals(
                    BigInteger("2007507521450000000"),
                    msg.balances.first { it.symbol.value == ethSymbol }.available,
                )
                assertEquals(
                    BigInteger("199567900000000000"),
                    msg.balances.first { it.symbol.value == btcSymbol }.available,
                )
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

        takerWsClient.apply {
            assertPricesMessageReceived(btcEthMarketId) { msg ->
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
            assertLimitsMessageReceived(btcEthMarketId) { msg ->
                assertEquals(BigInteger("432100000000000"), msg.base)
                assertEquals(BigInteger("1992264977900000000"), msg.quote)
            }
        }

        makerWsClient.apply {
            assertPricesMessageReceived(btcEthMarketId) { msg ->
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
            assertLimitsMessageReceived(btcEthMarketId) { msg ->
                assertEquals(BigInteger("199456790000000000"), msg.base)
                assertEquals(BigInteger("2005347146450000000"), msg.quote)
            }
        }

        assertEquals(trade.amount, takerWallet.formatAmount("0.00043210", btcSymbol))
        assertEquals(0, trade.price.compareTo(BigDecimal("17.550")))

        waitForSettlementToFinish(listOf(trade.id.value))

        val baseQuantity = takerWallet.formatAmount("0.00043210", btcSymbol)
        val notional =
            (trade.price * trade.amount.fromFundamentalUnits(takerWallet.decimals(btcSymbol))).toFundamentalUnits(
                takerWallet.decimals(ethSymbol),
            )
        val makerFee = notional * BigInteger.valueOf(100) / BigInteger.valueOf(10000)
        val takerFee = notional * BigInteger.valueOf(200) / BigInteger.valueOf(10000)

        assertBalances(
            listOf(
                ExpectedBalance(
                    btcSymbol,
                    makerStartingBTCBalance - baseQuantity,
                    makerStartingBTCBalance - baseQuantity,
                ),
                ExpectedBalance(
                    ethSymbol,
                    makerStartingETHBalance + notional - makerFee,
                    makerStartingETHBalance + notional - makerFee,
                ),
            ),
            makerApiClient.getBalances().balances,
        )
        assertBalances(
            listOf(
                ExpectedBalance(
                    btcSymbol,
                    takerStartingBTCBalance + baseQuantity,
                    takerStartingBTCBalance + baseQuantity,
                ),
                ExpectedBalance(
                    ethSymbol,
                    takerStartingETHBalance - notional - takerFee,
                    takerStartingETHBalance - notional - takerFee,
                ),
            ),
            takerApiClient.getBalances().balances,
        )

        takerWsClient.assertBalancesMessageReceived { msg ->
            assertEquals(
                takerStartingBTCBalance + baseQuantity,
                msg.balances.first { it.symbol.value == btcSymbol }.available,
            )
            assertEquals(
                takerStartingETHBalance - notional - takerFee,
                msg.balances.first { it.symbol.value == ethSymbol }.available,
            )
        }
        takerWsClient.assertTradeUpdatedMessageReceived { msg ->
            assertEquals(marketBuyOrderApiResponse.orderId, msg.trade.orderId)
            assertEquals(SettlementStatus.Completed, msg.trade.settlementStatus)
        }

        makerWsClient.assertBalancesMessageReceived { msg ->
            assertEquals(
                makerStartingBTCBalance - baseQuantity,
                msg.balances.first { it.symbol.value == btcSymbol }.available,
            )
            assertEquals(
                makerStartingETHBalance + notional - makerFee,
                msg.balances.first { it.symbol.value == ethSymbol }.available,
            )
        }
        makerWsClient.assertTradeUpdatedMessageReceived { msg ->
            assertEquals(updatedLimitSellOrderApiResponse.order.orderId, msg.trade.orderId)
            assertEquals(SettlementStatus.Completed, msg.trade.settlementStatus)
        }

        // place a sell order and see it gets executed
        val marketSellOrderApiResponse = takerApiClient.createOrder(
            CreateOrderApiRequest.Market(
                nonce = generateOrderNonce(),
                marketId = btcEthMarketId,
                side = OrderSide.Sell,
                amount = takerWallet.formatAmount("0.00012346", btcSymbol),
                signature = EvmSignature.emptySignature(),
                verifyingChainId = ChainId.empty,
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
                assertEquals(takerWallet.formatAmount("0.00012345", btcSymbol), msg.trade.amount)
                assertEquals(takerWallet.formatAmount("0.0000432075", ethSymbol), msg.trade.feeAmount)
                assertEquals(ethSymbol, msg.trade.feeSymbol.value)
                assertEquals(SettlementStatus.Pending, msg.trade.settlementStatus)
            }
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Partial, msg.order.status)
                assertEquals(1, msg.order.executions.size)
                assertEquals(msg.order.executions[0].amount, takerWallet.formatAmount("0.00012345", btcSymbol))
                assertEquals(0, msg.order.executions[0].price.compareTo(BigDecimal("17.500")))
                assertEquals(ExecutionRole.Taker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived { msg ->
                assertEquals(
                    BigInteger("1994382145400000000"),
                    msg.balances.first { it.symbol.value == ethSymbol }.available,
                )
                assertEquals(
                    BigInteger("308650000000000"),
                    msg.balances.first { it.symbol.value == btcSymbol }.available,
                )
            }
        }

        makerWsClient.apply {
            assertTradeCreatedMessageReceived { msg ->
                assertEquals(updatedLimitBuyOrderApiResponse.order.orderId, msg.trade.orderId)
                assertEquals(updatedLimitBuyOrderApiResponse.order.marketId, msg.trade.marketId)
                assertEquals(updatedLimitBuyOrderApiResponse.order.side, msg.trade.side)
                assertEquals(0, updatedLimitBuyOrderApiResponse.order.price.compareTo(msg.trade.price))
                assertEquals(makerWallet.formatAmount("0.00012345", btcSymbol), msg.trade.amount)
                assertEquals(makerWallet.formatAmount("0.00002160375", ethSymbol), msg.trade.feeAmount)
                assertEquals(ethSymbol, msg.trade.feeSymbol.value)
                assertEquals(SettlementStatus.Pending, msg.trade.settlementStatus)
            }
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Filled, msg.order.status)
                assertEquals(1, msg.order.executions.size)
                assertEquals(msg.order.executions[0].amount, takerWallet.formatAmount("0.00012345", btcSymbol))
                assertEquals(0, msg.order.executions[0].price.compareTo(BigDecimal("17.500")))
                assertEquals(ExecutionRole.Maker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived { msg ->
                assertEquals(
                    BigInteger("2005325542700000000"),
                    msg.balances.first { it.symbol.value == ethSymbol }.available,
                )
                assertEquals(
                    BigInteger("199691350000000000"),
                    msg.balances.first { it.symbol.value == btcSymbol }.available,
                )
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
        assertEquals(trade2.amount, takerWallet.formatAmount("0.00012345", btcSymbol))
        assertEquals(0, trade2.price.compareTo(BigDecimal("17.500")))

        waitForSettlementToFinish(listOf(trade2.id.value))

        val baseQuantity2 = takerWallet.formatAmount("0.00012345", btcSymbol)
        val notional2 =
            (trade2.price * trade2.amount.fromFundamentalUnits(takerWallet.decimals(btcSymbol))).toFundamentalUnits(
                takerWallet.decimals(ethSymbol),
            )
        val makerFee2 = notional2 * BigInteger.valueOf(100) / BigInteger.valueOf(10000)
        val takerFee2 = notional2 * BigInteger.valueOf(200) / BigInteger.valueOf(10000)
        assertBalances(
            listOf(
                ExpectedBalance(
                    btcSymbol,
                    total = makerStartingBTCBalance - baseQuantity + baseQuantity2,
                    available = makerStartingBTCBalance - baseQuantity + baseQuantity2,
                ),
                ExpectedBalance(
                    ethSymbol,
                    total = makerStartingETHBalance + (notional - makerFee) - (notional2 + makerFee2),
                    available = makerStartingETHBalance + (notional - makerFee) - (notional2 + makerFee2),
                ),
            ),
            makerApiClient.getBalances().balances,
        )
        assertBalances(
            listOf(
                ExpectedBalance(
                    btcSymbol,
                    total = takerStartingBTCBalance + baseQuantity - baseQuantity2,
                    available = takerStartingBTCBalance + baseQuantity - baseQuantity2,
                ),
                ExpectedBalance(
                    ethSymbol,
                    total = takerStartingETHBalance - (notional + takerFee) + (notional2 - takerFee2),
                    available = takerStartingETHBalance - (notional + takerFee) + (notional2 - takerFee2),
                ),
            ),
            takerApiClient.getBalances().balances,
        )

        takerWsClient.apply {
            assertPricesMessageReceived(btcEthMarketId) { msg ->
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
            assertLimitsMessageReceived(btcEthMarketId) { msg ->
                assertEquals(BigInteger("308650000000000"), msg.base)
                assertEquals(BigInteger("1994382145400000000"), msg.quote)
            }
            assertBalancesMessageReceived { msg ->
                assertEquals(
                    takerStartingBTCBalance + baseQuantity - baseQuantity2,
                    msg.balances.first { it.symbol.value == btcSymbol }.available,
                )
                assertEquals(
                    takerStartingETHBalance - (notional + takerFee) + (notional2 - takerFee2),
                    msg.balances.first { it.symbol.value == ethSymbol }.available,
                )
            }
            assertTradeUpdatedMessageReceived { msg ->
                assertEquals(marketSellOrderApiResponse.orderId, msg.trade.orderId)
                assertEquals(SettlementStatus.Completed, msg.trade.settlementStatus)
            }
        }

        makerWsClient.apply {
            assertPricesMessageReceived(btcEthMarketId) { msg ->
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
            assertLimitsMessageReceived(btcEthMarketId) { msg ->
                assertEquals(BigInteger("199580240000000000"), msg.base)
                assertEquals(BigInteger("2005325542700000000"), msg.quote)
            }
            assertBalancesMessageReceived { msg ->
                assertEquals(
                    makerStartingBTCBalance - baseQuantity + baseQuantity2,
                    msg.balances.first { it.symbol.value == btcSymbol }.available,
                )
                assertEquals(
                    makerStartingETHBalance + (notional - makerFee) - (notional2 + makerFee2),
                    msg.balances.first { it.symbol.value == ethSymbol }.available,
                )
            }
            assertTradeUpdatedMessageReceived { msg ->
                assertEquals(updatedLimitBuyOrderApiResponse.order.orderId, msg.trade.orderId)
                assertEquals(SettlementStatus.Completed, msg.trade.settlementStatus)
            }
        }

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
        makerWsClient.assertLimitsMessageReceived(btcEthMarketId)

        // verify that client's websocket gets same state on reconnect
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
                    assertEquals(makerWallet.formatAmount("0.00012345", btcSymbol), amount)
                    assertEquals(takerWallet.formatAmount("0.0000432075", ethSymbol), feeAmount)
                    assertEquals(ethSymbol, feeSymbol.value)
                    assertEquals(SettlementStatus.Completed, settlementStatus)
                }
                msg.trades[1].apply {
                    assertEquals(marketBuyOrderApiResponse.orderId, orderId)
                    assertEquals(marketBuyOrderApiResponse.order.marketId, marketId)
                    assertEquals(marketBuyOrderApiResponse.order.side, side)
                    assertEquals(0, updatedLimitSellOrderApiResponse.order.price.compareTo(price))
                    assertEquals(marketBuyOrderApiResponse.order.amount, amount)
                    assertEquals(takerWallet.formatAmount("0.0001516671", ethSymbol), feeAmount)
                    assertEquals(ethSymbol, feeSymbol.value)
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

            subscribeToLimits(btcEthMarketId)
            assertLimitsMessageReceived(btcEthMarketId) { msg ->
                assertEquals(BigInteger("308650000000000"), msg.base)
                assertEquals(BigInteger("1994382145400000000"), msg.quote)
            }
        }.close()

        makerWsClient.close()

        // verify that fees have settled correctly on chain
        assertEquals(
            makerFee + takerFee + makerFee2 + takerFee2,
            exchangeContractManager.getFeeBalance(ethSymbol) - initialFeeAccountEthBalance,
        )
    }

    @ParameterizedTest
    @MethodSource("chainIndices")
    fun `order batches`(chainIndex: Int) {
        val usdcSymbol = "USDC".toChainSymbol(chainIndex)
        val btcSymbol = "BTC".toChainSymbol(chainIndex)
        val btcUsdcMarketId = MarketId("$btcSymbol/$usdcSymbol")

        val (makerApiClient, makerWallet, makerWsClient) =
            setupTrader(btcUsdcMarketId, "0.5", "0.2", usdcSymbol, "500", subscribeToOrderBook = false, chainIndex = chainIndex)

        val (takerApiClient, takerWallet, takerWsClient) =
            setupTrader(btcUsdcMarketId, "0.5", null, usdcSymbol, "500", subscribeToOrderBook = false, chainIndex = chainIndex)

        // starting onchain balances
        val makerStartingBTCBalance = makerWallet.getExchangeNativeBalance()
        val makerStartingUSDCBalance = makerWallet.getExchangeERC20Balance(usdcSymbol)
        val takerStartingBTCBalance = takerWallet.getExchangeNativeBalance()
        val takerStartingUSDCBalance = takerWallet.getExchangeERC20Balance(usdcSymbol)

        // place 3 orders
        val createBatchLimitOrders = makerApiClient.batchOrders(
            BatchOrdersApiRequest(
                marketId = btcUsdcMarketId,
                createOrders = listOf("0.00001", "0.00002", "0.0003").map {
                    CreateOrderApiRequest.Limit(
                        nonce = generateOrderNonce(),
                        marketId = btcUsdcMarketId,
                        side = OrderSide.Sell,
                        amount = makerWallet.formatAmount(it, btcSymbol),
                        price = BigDecimal("68400.000"),
                        signature = EvmSignature.emptySignature(),
                        verifyingChainId = ChainId.empty,
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
        makerWsClient.assertLimitsMessageReceived(btcUsdcMarketId) { msg ->
            assertEquals(BigInteger("199670000000000000"), msg.base)
            assertEquals(BigInteger("500000000"), msg.quote)
        }

        val batchOrderResponse = makerApiClient.batchOrders(
            BatchOrdersApiRequest(
                marketId = btcUsdcMarketId,
                createOrders = listOf("0.0004", "0.0005", "0.0006").map {
                    CreateOrderApiRequest.Limit(
                        nonce = generateOrderNonce(),
                        marketId = btcUsdcMarketId,
                        side = OrderSide.Sell,
                        amount = makerWallet.formatAmount(it, btcSymbol),
                        price = BigDecimal("68400.000"),
                        signature = EvmSignature.emptySignature(),
                        verifyingChainId = ChainId.empty,
                    ).let {
                        makerWallet.signOrder(it)
                    }
                },
                updateOrders = listOf(
                    UpdateOrderApiRequest(
                        orderId = createBatchLimitOrders.createdOrders[0].orderId,
                        amount = makerWallet.formatAmount("0.0001", btcSymbol),
                        marketId = createBatchLimitOrders.createdOrders[0].order.marketId,
                        side = createBatchLimitOrders.createdOrders[0].order.side,
                        price = BigDecimal("68405.000"),
                        nonce = generateOrderNonce(),
                        signature = EvmSignature.emptySignature(),
                        verifyingChainId = ChainId.empty,
                    ).let {
                        makerWallet.signOrder(it)
                    },
                    UpdateOrderApiRequest(
                        orderId = createBatchLimitOrders.createdOrders[1].orderId,
                        amount = makerWallet.formatAmount("0.0002", btcSymbol),
                        marketId = createBatchLimitOrders.createdOrders[1].order.marketId,
                        side = createBatchLimitOrders.createdOrders[2].order.side,
                        price = BigDecimal("68405.000"),
                        nonce = generateOrderNonce(),
                        signature = EvmSignature.emptySignature(),
                        verifyingChainId = ChainId.empty,
                    ).let {
                        makerWallet.signOrder(it)
                    },
                    UpdateOrderApiRequest(
                        orderId = OrderId.generate(),
                        amount = makerWallet.formatAmount("0.0002", btcSymbol),
                        marketId = createBatchLimitOrders.createdOrders[1].order.marketId,
                        side = createBatchLimitOrders.createdOrders[2].order.side,
                        price = BigDecimal("68405.000"),
                        nonce = generateOrderNonce(),
                        signature = EvmSignature.emptySignature(),
                        verifyingChainId = ChainId.empty,
                    ).let {
                        makerWallet.signOrder(it)
                    },
                ),
                cancelOrders = listOf(
                    CancelOrderApiRequest(
                        orderId = createBatchLimitOrders.createdOrders[2].orderId,
                        marketId = createBatchLimitOrders.createdOrders[2].order.marketId,
                        amount = createBatchLimitOrders.createdOrders[2].order.amount,
                        side = createBatchLimitOrders.createdOrders[2].order.side,
                        nonce = generateOrderNonce(),
                        signature = EvmSignature.emptySignature(),
                        verifyingChainId = ChainId.empty,
                    ).let {
                        makerWallet.signCancelOrder(it)
                    },
                    CancelOrderApiRequest(
                        orderId = OrderId.generate(),
                        marketId = btcUsdcMarketId,
                        amount = BigInteger.ZERO,
                        side = OrderSide.Buy,
                        nonce = generateOrderNonce(),
                        signature = EvmSignature.emptySignature(),
                        verifyingChainId = ChainId.empty,
                    ).let {
                        makerWallet.signCancelOrder(it)
                    },
                ),
            ),
        )

        assertEquals(3, batchOrderResponse.createdOrders.count { it.requestStatus == RequestStatus.Accepted })
        assertEquals(2, batchOrderResponse.updatedOrders.count { it.requestStatus == RequestStatus.Accepted })
        assertEquals(1, batchOrderResponse.updatedOrders.count { it.requestStatus == RequestStatus.Rejected })
        assertEquals(1, batchOrderResponse.canceledOrders.count { it.requestStatus == RequestStatus.Accepted })
        assertEquals(1, batchOrderResponse.canceledOrders.count { it.requestStatus == RequestStatus.Rejected })

        repeat(3) { makerWsClient.assertOrderCreatedMessageReceived() }
        makerWsClient.assertLimitsMessageReceived(btcUsdcMarketId) { msg ->
            assertEquals(BigInteger("197900000000000000"), msg.base)
            assertEquals(BigInteger("500000000"), msg.quote)
        }
        repeat(3) { makerWsClient.assertOrderUpdatedMessageReceived() }
        makerWsClient.assertLimitsMessageReceived(btcUsdcMarketId) { msg ->
            assertEquals(BigInteger("198200000000000000"), msg.base)
            assertEquals(BigInteger("500000000"), msg.quote)
        }

        assertEquals(5, makerApiClient.listOrders().orders.count { it.status == OrderStatus.Open })

        // total BTC available is 0.0001 + 0.0002 + 0.0004 + 0.0005 + 0.0006 = 0.0018
        val takerOrderAmount = takerWallet.formatAmount("0.0018", btcSymbol)
        // create a market orders that should match against the 5 limit orders
        takerApiClient.createOrder(
            CreateOrderApiRequest.Market(
                nonce = generateOrderNonce(),
                marketId = btcUsdcMarketId,
                side = OrderSide.Buy,
                amount = takerOrderAmount,
                signature = EvmSignature.emptySignature(),
                verifyingChainId = ChainId.empty,
            ).let {
                takerWallet.signOrder(it)
            },
        )

        takerWsClient.apply {
            assertOrderCreatedMessageReceived()
            repeat(5) { assertTradeCreatedMessageReceived() }
            assertOrderUpdatedMessageReceived()
            assertBalancesMessageReceived()
            assertPricesMessageReceived(btcUsdcMarketId) { msg ->
                assertEquals(
                    OHLC(
                        // initial ohlc in the BTC/USDC market
                        // price is weighted across limit orders that have been filled within execution
                        start = OHLCDuration.P5M.durationStart(Clock.System.now()),
                        open = 68400.3,
                        high = 68400.3,
                        low = 68400.3,
                        close = 68400.3,
                        duration = OHLCDuration.P5M,
                    ),
                    msg.ohlc.last(),
                )
            }
            assertLimitsMessageReceived(btcUsdcMarketId) { msg ->
                assertEquals(BigInteger("1800000000000000"), msg.base)
                assertEquals(BigInteger("374416988"), msg.quote)
            }
        }

        makerWsClient.apply {
            repeat(5) { assertTradeCreatedMessageReceived() }
            repeat(5) { assertOrderUpdatedMessageReceived() }
            assertBalancesMessageReceived()
            assertPricesMessageReceived(btcUsdcMarketId) { msg ->
                assertEquals(
                    OHLC(
                        start = OHLCDuration.P5M.durationStart(Clock.System.now()),
                        open = 68400.3,
                        high = 68400.3,
                        low = 68400.3,
                        close = 68400.3,
                        duration = OHLCDuration.P5M,
                    ),
                    msg.ohlc.last(),
                )
            }
            assertLimitsMessageReceived(btcUsdcMarketId) { msg ->
                assertEquals(BigInteger("198200000000000000"), msg.base)
                assertEquals(BigInteger("621889395"), msg.quote)
            }
        }

        // should be 8 filled orders
        val takerOrders = takerApiClient.listOrders().orders
        assertEquals(1, takerOrders.count { it.status == OrderStatus.Filled })
        assertEquals(5, makerApiClient.listOrders().orders.count { it.status == OrderStatus.Filled })

        // now verify the trades

        val expectedAmounts = listOf("0.0001", "0.0002", "0.0004", "0.0005", "0.0006").map { takerWallet.formatAmount(it, btcSymbol) }.toSet()
        val trades = getTradesForOrders(takerOrders.map { it.id })
        val prices = trades.map { it.price }.toSet()

        assertEquals(5, trades.size)
        assertEquals(expectedAmounts, trades.map { it.amount }.toSet())
        assertEquals(prices.size, 2)

        waitForSettlementToFinish(trades.map { it.id.value })

        val notionals = trades.map {
            (it.price * it.amount.fromFundamentalUnits(makerWallet.decimals(btcSymbol))).toFundamentalUnits(takerWallet.decimals(usdcSymbol))
        }

        val notional = notionals.sum()
        val makerFees = notionals.map { it * BigInteger.valueOf(100) / BigInteger.valueOf(10000) }.sum()
        val takerFees = notionals.map { it * BigInteger.valueOf(200) / BigInteger.valueOf(10000) }.sum()

        assertBalances(
            listOf(
                ExpectedBalance(btcSymbol, makerStartingBTCBalance - takerOrderAmount, makerStartingBTCBalance - takerOrderAmount),
                ExpectedBalance(usdcSymbol, makerStartingUSDCBalance + notional - makerFees, makerStartingUSDCBalance + notional - makerFees),
            ),
            makerApiClient.getBalances().balances,
        )
        assertBalances(
            listOf(
                ExpectedBalance(btcSymbol, takerStartingBTCBalance + takerOrderAmount, takerStartingBTCBalance + takerOrderAmount),
                ExpectedBalance(usdcSymbol, takerStartingUSDCBalance - notional - takerFees, takerStartingUSDCBalance - notional - takerFees),
            ),
            takerApiClient.getBalances().balances,
        )

        takerApiClient.cancelOpenOrders()
        makerApiClient.cancelOpenOrders()

        makerWsClient.close()
        takerWsClient.close()
    }

    @ParameterizedTest
    @MethodSource("chainIndices")
    fun `settlement failure`(chainIndex: Int) {
        val ethSymbol = "ETH".toChainSymbol(chainIndex)
        val btcSymbol = "BTC".toChainSymbol(chainIndex)
        val btcEthMarketId = MarketId("$btcSymbol/$ethSymbol")

        val (takerApiClient, takerWallet, takerWsClient) =
            setupTrader(btcEthMarketId, "0.5", "0.1", ethSymbol, "2", chainIndex = chainIndex)

        val (makerApiClient, makerWallet, makerWsClient) =
            setupTrader(btcEthMarketId, "0.5", null, ethSymbol, "2", chainIndex = chainIndex)

        // starting onchain balances
        val makerStartingBTCBalance = makerWallet.getExchangeNativeBalance()
        val makerStartingETHBalance = makerWallet.getExchangeERC20Balance(ethSymbol)
        val takerStartingBTCBalance = takerWallet.getExchangeNativeBalance()
        val takerStartingETHBalance = takerWallet.getExchangeERC20Balance(ethSymbol)

        val btcWithdrawalAmount = takerWallet.formatAmount("0.015", btcSymbol)

        val pendingBtcWithdrawal = takerApiClient.createWithdrawal(takerWallet.signWithdraw(btcSymbol, btcWithdrawalAmount)).withdrawal
        assertEquals(WithdrawalStatus.Pending, pendingBtcWithdrawal.status)

        takerWsClient.apply {
            assertBalancesMessageReceived { msg ->
                assertEquals(takerStartingBTCBalance - btcWithdrawalAmount, msg.balances.first { it.symbol.value == btcSymbol }.available)
                assertEquals(takerStartingETHBalance, msg.balances.first { it.symbol.value == ethSymbol }.available)
            }
            assertLimitsMessageReceived(btcEthMarketId) { msg ->
                assertEquals(takerStartingBTCBalance - btcWithdrawalAmount, msg.base)
                assertEquals(takerStartingETHBalance, msg.quote)
            }
        }

        waitForFinalizedWithdrawal(pendingBtcWithdrawal.id)

        takerWsClient.apply {
            assertBalancesMessageReceived { msg ->
                assertEquals(takerStartingBTCBalance - btcWithdrawalAmount, msg.balances.first { it.symbol.value == btcSymbol }.total)
            }
        }

        // hack to resubmit the same transaction again bypassing the sequencer. This way taker will not have a
        // sufficient on chain balance for the order so settlement will fail.
        transaction {
            WithdrawalEntity[pendingBtcWithdrawal.id].status = WithdrawalStatus.Pending
            val withdrawalExchangeTransaction = ExchangeTransactionEntity.all()
                .orderBy(ExchangeTransactionTable.sequenceId to SortOrder.DESC)
                .limit(1).first()
            withdrawalExchangeTransaction.status = ExchangeTransactionStatus.Pending
        }

        waitForFinalizedWithdrawal(pendingBtcWithdrawal.id)

        takerWsClient.apply {
            assertBalancesMessageReceived { msg ->
                assertEquals(takerStartingBTCBalance - btcWithdrawalAmount - btcWithdrawalAmount, msg.balances.first { it.symbol.value == btcSymbol }.total)
            }
        }

        // place a limit order
        val limitBuyOrderApiResponse = makerApiClient.createOrder(
            CreateOrderApiRequest.Limit(
                nonce = generateOrderNonce(),
                marketId = btcEthMarketId,
                side = OrderSide.Buy,
                amount = makerWallet.formatAmount("0.08", btcSymbol),
                price = BigDecimal("17.55"),
                signature = EvmSignature.emptySignature(),
                verifyingChainId = ChainId.empty,
            ).let {
                makerWallet.signOrder(it)
            },
        )
        assertIs<CreateOrderApiRequest.Limit>(limitBuyOrderApiResponse.order)
        makerWsClient.apply {
            assertOrderCreatedMessageReceived { msg ->
                validateLimitOrders(limitBuyOrderApiResponse, msg.order as Order.Limit, false)
            }
            assertLimitsMessageReceived(btcEthMarketId) { msg ->
                assertEquals(BigInteger.ZERO, msg.base)
                assertEquals(BigInteger("596000000000000000"), msg.quote)
            }
        }

        listOf(makerWsClient, takerWsClient).forEach { wsClient ->
            wsClient.assertOrderBookMessageReceived(
                btcEthMarketId,
                OrderBook(
                    marketId = btcEthMarketId,
                    buy = listOf(
                        OrderBookEntry(price = "17.550", size = "0.08".toBigDecimal()),
                    ),
                    sell = emptyList(),
                    last = LastTrade("0.000", LastTradeDirection.Unchanged),
                ),
            )
        }

        // place a sell order
        val marketBuyOrderApiResponse = takerApiClient.createOrder(
            CreateOrderApiRequest.Market(
                nonce = generateOrderNonce(),
                marketId = btcEthMarketId,
                side = OrderSide.Sell,
                amount = takerWallet.formatAmount("0.08", btcSymbol),
                signature = EvmSignature.emptySignature(),
                verifyingChainId = ChainId.empty,
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
                assertEquals(0, BigDecimal("17.55").compareTo(msg.trade.price))
                assertEquals(marketBuyOrderApiResponse.order.amount, msg.trade.amount)
                assertEquals(SettlementStatus.Pending, msg.trade.settlementStatus)
            }
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Filled, msg.order.status)
                assertEquals(1, msg.order.executions.size)
                assertEquals(msg.order.executions[0].amount, takerWallet.formatAmount("0.08", btcSymbol))
                assertEquals(0, msg.order.executions[0].price.compareTo(BigDecimal("17.550")))
                assertEquals(msg.order.executions[0].role, ExecutionRole.Taker)
            }
            assertBalancesMessageReceived { msg ->
                assertEquals(BigInteger("3375920000000000000"), msg.balances.first { it.symbol.value == ethSymbol }.available)
                assertEquals(BigInteger("5000000000000000"), msg.balances.first { it.symbol.value == btcSymbol }.available)
            }
        }

        makerWsClient.apply {
            assertTradeCreatedMessageReceived { msg ->
                assertEquals(limitBuyOrderApiResponse.orderId, msg.trade.orderId)
                assertEquals(limitBuyOrderApiResponse.order.marketId, msg.trade.marketId)
                assertEquals(limitBuyOrderApiResponse.order.side, msg.trade.side)
                assertEquals(0, BigDecimal("17.55").compareTo(msg.trade.price))
                assertEquals(takerWallet.formatAmount("0.08", btcSymbol), msg.trade.amount)
                assertEquals(SettlementStatus.Pending, msg.trade.settlementStatus)
            }
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Filled, msg.order.status)
                assertEquals(1, msg.order.executions.size)
                assertEquals(msg.order.executions[0].amount, makerWallet.formatAmount("0.08", btcSymbol))
                assertEquals(0, msg.order.executions[0].price.compareTo(BigDecimal("17.550")))
                assertEquals(ExecutionRole.Maker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived { msg ->
                assertEquals(BigInteger("581960000000000000"), msg.balances.first { it.symbol.value == ethSymbol }.available)
                assertEquals(BigInteger("80000000000000000"), msg.balances.first { it.symbol.value == btcSymbol }.available)
            }
        }

        listOf(makerWsClient, takerWsClient).forEach { wsClient ->
            wsClient.assertOrderBookMessageReceived(
                btcEthMarketId,
                OrderBook(
                    marketId = btcEthMarketId,
                    buy = listOf(),
                    sell = listOf(),
                    last = LastTrade("17.550", LastTradeDirection.Up),
                ),
            )
        }
        val trade = getTradesForOrders(listOf(marketBuyOrderApiResponse.orderId)).first()

        takerWsClient.apply {
            assertPricesMessageReceived(btcEthMarketId)
            assertLimitsMessageReceived(btcEthMarketId) { msg ->
                assertEquals(BigInteger("5000000000000000"), msg.base)
                assertEquals(BigInteger("3375920000000000000"), msg.quote)
            }
        }

        makerWsClient.apply {
            assertPricesMessageReceived(btcEthMarketId)
            assertLimitsMessageReceived(btcEthMarketId) { msg ->
                assertEquals(BigInteger("80000000000000000"), msg.base)
                assertEquals(BigInteger("581960000000000000"), msg.quote)
            }
        }

        assertEquals(trade.amount, takerWallet.formatAmount("0.08", btcSymbol))
        assertEquals(0, trade.price.compareTo(BigDecimal("17.550")))

        waitForSettlementToFinish(listOf(trade.id.value), SettlementStatus.Failed)

        takerWsClient.apply {
            assertTradeUpdatedMessageReceived { msg ->
                assertEquals(marketBuyOrderApiResponse.orderId, msg.trade.orderId)
                assertEquals(SettlementStatus.Failed, msg.trade.settlementStatus)
                assertEquals("Insufficient Balance", msg.trade.error)
            }
            assertBalancesMessageReceived { msg ->
                assertEquals(takerStartingBTCBalance - btcWithdrawalAmount, msg.balances.first { it.symbol.value == btcSymbol }.available)
                assertEquals(takerStartingETHBalance, msg.balances.first { it.symbol.value == ethSymbol }.available)
            }
            assertLimitsMessageReceived(btcEthMarketId) { msg ->
                assertEquals(takerStartingBTCBalance - btcWithdrawalAmount, msg.base)
                assertEquals(takerStartingETHBalance, msg.quote)
            }
        }

        makerWsClient.apply {
            assertTradeUpdatedMessageReceived { msg ->
                assertEquals(limitBuyOrderApiResponse.orderId, msg.trade.orderId)
                assertEquals(SettlementStatus.Failed, msg.trade.settlementStatus)
                assertEquals("Insufficient Balance", msg.trade.error)
            }
            assertBalancesMessageReceived { msg ->
                assertEquals(makerStartingBTCBalance, msg.balances.first { it.symbol.value == btcSymbol }.available)
                assertEquals(makerStartingETHBalance, msg.balances.first { it.symbol.value == ethSymbol }.available)
            }
            assertLimitsMessageReceived(btcEthMarketId) { msg ->
                assertEquals(makerStartingBTCBalance, msg.base)
                assertEquals(makerStartingETHBalance, msg.quote)
            }
        }

        makerWsClient.close()
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
        assertEquals(0, response.price.compareTo(order.price))
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
        subscribeToOrderBook: Boolean = true,
        subscribeToOrderPrices: Boolean = true,
        chainIndex: Int = 0,
    ): Triple<TestApiClient, Wallet, WsClient> {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)
        val config = apiClient.getConfiguration()
        wallet.switchChain(config.chains[chainIndex].id)
        val chainId = wallet.currentChainId

        val wsClient = WebsocketClient.blocking(apiClient.authToken).apply {
            if (subscribeToOrderBook) {
                subscribeToOrderBook(marketId)
                assertOrderBookMessageReceived(marketId)
            }

            if (subscribeToOrderPrices) {
                subscribeToPrices(marketId)
                assertPricesMessageReceived(marketId)
            }

            subscribeToOrders()
            assertOrdersMessageReceived()

            subscribeToTrades()
            assertTradesMessageReceived()

            subscribeToBalances()
            assertBalancesMessageReceived()

            subscribeToLimits(marketId)
            assertLimitsMessageReceived(marketId)
        }

        val btcSymbol = "BTC".toChainSymbol(chainIndex)
        Faucet.fund(wallet.address, wallet.formatAmount(nativeAmount, btcSymbol), chainId)
        val formattedMintAmount = wallet.formatAmount(mintAmount, mintSymbol)
        wallet.mintERC20(mintSymbol, formattedMintAmount)

        deposit(wallet, mintSymbol, formattedMintAmount)
        waitForBalance(apiClient, wsClient, listOf(ExpectedBalance(mintSymbol, formattedMintAmount, formattedMintAmount)))
        wsClient.assertLimitsMessageReceived(marketId)

        nativeDepositAmount?.also {
            val formattedNativeAmount = wallet.formatAmount(it, btcSymbol)
            deposit(wallet, btcSymbol, formattedNativeAmount)
            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(mintSymbol, formattedMintAmount, formattedMintAmount),
                    ExpectedBalance(btcSymbol, formattedNativeAmount, formattedNativeAmount),
                ),
            )
            wsClient.assertLimitsMessageReceived(marketId)
        }

        return Triple(apiClient, wallet, wsClient)
    }

    private fun deposit(wallet: Wallet, asset: String, amount: BigInteger) {
        // deposit onchain and update sequencer
        if (asset.contains("BTC")) {
            wallet.depositNative(amount)
        } else {
            wallet.depositERC20(asset, amount)
        }
    }

    private fun waitForSettlementToFinish(tradeIds: List<TradeId>, expectedStatus: SettlementStatus = SettlementStatus.Completed) {
        await
            .withAlias("Waiting for trade settlement to finish. TradeIds: ${tradeIds.joinToString { it.value }}")
            .pollInSameThread()
            .pollDelay(Duration.ofMillis(100))
            .pollInterval(Duration.ofMillis(100))
            .atMost(Duration.ofMillis(20000L))
            .until {
                Faucet.mine()
                transaction {
                    TradeEntity.count(TradeTable.guid.inList(tradeIds) and TradeTable.settlementStatus.eq(expectedStatus))
                } == tradeIds.size.toLong()
            }
    }

    private fun getTradesForOrders(orderIds: List<OrderId>): List<TradeEntity> {
        return transaction {
            OrderExecutionEntity.findForOrders(orderIds).map { it.trade }
        }
    }

    private fun validateNonceAndSignatureStored(orderId: OrderId, nonce: String, signature: EvmSignature) {
        transaction {
            val orderEntity = OrderEntity[orderId]
            assertEquals(BigInteger(orderEntity.nonce, 16), BigInteger(nonce, 16))
            assertEquals(orderEntity.signature, signature.value)
        }
    }
}
