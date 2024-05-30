package co.chainring.integrationtests.api

import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.BatchOrdersApiRequest
import co.chainring.apps.api.model.CancelOrderApiRequest
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.Market
import co.chainring.apps.api.model.Order
import co.chainring.apps.api.model.ReasonCode
import co.chainring.apps.api.model.RequestStatus
import co.chainring.apps.api.model.SymbolInfo
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
import co.chainring.core.model.db.ExecutionRole
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OHLCDuration
import co.chainring.core.model.db.OrderExecutionEntity
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.OrderStatus
import co.chainring.core.model.db.SettlementBatchEntity
import co.chainring.core.model.db.SettlementBatchStatus
import co.chainring.core.model.db.SettlementBatchTable
import co.chainring.core.model.db.SettlementStatus
import co.chainring.core.model.db.TradeEntity
import co.chainring.core.model.db.TradeId
import co.chainring.core.model.db.TradeTable
import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.model.db.WithdrawalStatus
import co.chainring.core.utils.generateOrderNonce
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.testutils.waitForBalance
import co.chainring.integrationtests.testutils.waitForFinalizedWithdrawal
import co.chainring.integrationtests.utils.AssetAmount
import co.chainring.integrationtests.utils.ExchangeContractManager
import co.chainring.integrationtests.utils.ExpectedBalance
import co.chainring.integrationtests.utils.Faucet
import co.chainring.integrationtests.utils.TestApiClient
import co.chainring.integrationtests.utils.Wallet
import co.chainring.integrationtests.utils.assertAmount
import co.chainring.integrationtests.utils.assertBalances
import co.chainring.integrationtests.utils.assertBalancesMessageReceived
import co.chainring.integrationtests.utils.assertContainsBalancesMessage
import co.chainring.integrationtests.utils.assertContainsLimitsMessage
import co.chainring.integrationtests.utils.assertContainsTradeUpdatedMessage
import co.chainring.integrationtests.utils.assertError
import co.chainring.integrationtests.utils.assertFee
import co.chainring.integrationtests.utils.assertLimitOrderCreatedMessageReceived
import co.chainring.integrationtests.utils.assertLimitOrderUpdatedMessageReceived
import co.chainring.integrationtests.utils.assertLimitsMessageReceived
import co.chainring.integrationtests.utils.assertMarketOrderCreatedMessageReceived
import co.chainring.integrationtests.utils.assertMessagesReceived
import co.chainring.integrationtests.utils.assertOrderBookMessageReceived
import co.chainring.integrationtests.utils.assertOrderCreatedMessageReceived
import co.chainring.integrationtests.utils.assertOrderUpdatedMessageReceived
import co.chainring.integrationtests.utils.assertOrdersMessageReceived
import co.chainring.integrationtests.utils.assertPricesMessageReceived
import co.chainring.integrationtests.utils.assertTradeCreatedMessageReceived
import co.chainring.integrationtests.utils.assertTradeUpdatedMessageReceived
import co.chainring.integrationtests.utils.assertTradesMessageReceived
import co.chainring.integrationtests.utils.inFundamentalUnits
import co.chainring.integrationtests.utils.ofAsset
import co.chainring.integrationtests.utils.sum
import co.chainring.integrationtests.utils.toCancelOrderRequest
import kotlinx.datetime.Clock
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withAlias
import org.http4k.client.WebsocketClient
import org.http4k.websocket.WsClient
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertIs

@ExtendWith(AppUnderTestRunner::class)
class OrderRoutesApiTest {
    companion object {
        lateinit var symbols: Map<String, SymbolInfo>
        lateinit var btc: SymbolInfo
        lateinit var btc2: SymbolInfo
        lateinit var eth: SymbolInfo
        lateinit var eth2: SymbolInfo
        lateinit var usdc: SymbolInfo
        lateinit var usdc2: SymbolInfo
        lateinit var dai: SymbolInfo
        private lateinit var btcEthMarket: Market
        private lateinit var btc2Eth2Market: Market
        private lateinit var btcUsdcMarket: Market
        private lateinit var btc2Usdc2Market: Market
        private lateinit var usdcDaiMarket: Market
        private lateinit var btcbtc2Market: Market
        private lateinit var chainIdBySymbol: Map<String, ChainId>

        @JvmStatic
        @BeforeAll
        fun loadSymbols() {
            val config = TestApiClient.getConfiguration()
            usdcDaiMarket = config.markets.first { it.id.value == "USDC/DAI" }
            btcEthMarket = config.markets.first { it.id.value == "BTC/ETH" }
            btc2Eth2Market = config.markets.first { it.id.value == "BTC2/ETH2" }
            btcUsdcMarket = config.markets.first { it.id.value == "BTC/USDC" }
            btc2Usdc2Market = config.markets.first { it.id.value == "BTC2/USDC2" }
            btcbtc2Market = config.markets.first { it.id.value == "BTC/BTC2" }
            symbols = config.chains.flatMap { it.symbols }.associateBy { it.name }
            btc = symbols.getValue("BTC")
            btc2 = symbols.getValue("BTC2")
            eth = symbols.getValue("ETH")
            eth2 = symbols.getValue("ETH2")
            usdc = symbols.getValue("USDC")
            usdc2 = symbols.getValue("USDC2")
            dai = symbols.getValue("DAI")
            chainIdBySymbol = config.chains.map { chain -> chain.symbols.map { it.name to chain.id } }.flatten().toMap()
        }

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

        wsClient.subscribeToLimits(usdcDaiMarket.id)
        wsClient.assertLimitsMessageReceived(usdcDaiMarket.id)

        Faucet.fund(wallet.address)

        val daiAmountToDeposit = AssetAmount(dai, "14")
        wallet.mintERC20(daiAmountToDeposit)
        wallet.deposit(daiAmountToDeposit)
        waitForBalance(apiClient, wsClient, listOf(ExpectedBalance(daiAmountToDeposit)))

        wsClient.assertLimitsMessageReceived(usdcDaiMarket, base = AssetAmount(usdc, "0"), quote = daiAmountToDeposit)

        val createLimitOrderResponse = apiClient.createLimitOrder(
            usdcDaiMarket,
            OrderSide.Buy,
            amount = BigDecimal("1"),
            price = BigDecimal("2"),
            wallet,
        )

        // client is notified over websocket
        wsClient.assertLimitOrderCreatedMessageReceived(createLimitOrderResponse)
        wsClient.assertLimitsMessageReceived(usdcDaiMarket, base = BigDecimal("0"), quote = BigDecimal("12"))
        wsClient.close()

        // check that order is included in the orders list sent via websocket
        wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToOrders()
        assertEquals(
            listOf(createLimitOrderResponse.orderId) + initialOrdersOverWs.map { it.id },
            wsClient.assertOrdersMessageReceived().orders.map { it.id },
        )

        wsClient.subscribeToLimits(usdcDaiMarket.id)
        wsClient.assertLimitsMessageReceived(usdcDaiMarket.id)

        // update order
        val updatedOrderApiResponse = apiClient.updateOrder(
            usdcDaiMarket,
            createLimitOrderResponse,
            amount = BigDecimal("3"),
            price = BigDecimal("2.01"),
            wallet,
        )
        wsClient.assertLimitOrderUpdatedMessageReceived(updatedOrderApiResponse)
        wsClient.assertLimitsMessageReceived(usdcDaiMarket, base = BigDecimal("0"), quote = BigDecimal("7.97"))

        // cancel order is idempotent
        apiClient.cancelOrder(createLimitOrderResponse, wallet)
        wsClient.assertOrderUpdatedMessageReceived { msg ->
            assertEquals(createLimitOrderResponse.orderId, msg.order.id)
            assertEquals(OrderStatus.Cancelled, msg.order.status)
        }
        wsClient.assertLimitsMessageReceived(usdcDaiMarket, base = BigDecimal("0"), quote = BigDecimal("14.0"))

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
        val daiAmountToDeposit = AssetAmount(dai, "20")
        wallet.mintERC20(daiAmountToDeposit)
        wallet.deposit(daiAmountToDeposit)
        waitForBalance(apiClient, wsClient, listOf(ExpectedBalance(daiAmountToDeposit)))

        // operation on non-existent order
        apiClient.tryGetOrder(OrderId.generate())
            .assertError(ApiError(ReasonCode.OrderNotFound, "Requested order does not exist"))

        apiClient.tryUpdateOrder(
            wallet.signOrder(
                UpdateOrderApiRequest(
                    orderId = OrderId.generate(),
                    marketId = usdcDaiMarket.id,
                    side = OrderSide.Buy,
                    amount = BigDecimal("3").inFundamentalUnits(usdc),
                    price = BigDecimal("4"),
                    nonce = generateOrderNonce(),
                    signature = EvmSignature.emptySignature(),
                    verifyingChainId = ChainId.empty,
                ),
            ),
        ).assertError(ApiError(ReasonCode.RejectedBySequencer, "Order does not exist or is already finalized"))

        apiClient.tryCancelOrder(
            wallet.signCancelOrder(
                CancelOrderApiRequest(
                    orderId = OrderId.generate(),
                    marketId = usdcDaiMarket.id,
                    amount = BigInteger.ZERO,
                    side = OrderSide.Buy,
                    nonce = generateOrderNonce(),
                    signature = EvmSignature.emptySignature(),
                    verifyingChainId = ChainId.empty,
                ),
            ),
        ).assertError(ApiError(ReasonCode.RejectedBySequencer, "Order does not exist or is already finalized"))

        // invalid signature (malformed signature)
        apiClient.tryUpdateOrder(
            UpdateOrderApiRequest(
                orderId = OrderId.generate(),
                marketId = usdcDaiMarket.id,
                side = OrderSide.Buy,
                amount = BigDecimal("3").inFundamentalUnits(usdc),
                price = BigDecimal("4"),
                nonce = generateOrderNonce(),
                signature = EvmSignature.emptySignature(),
                verifyingChainId = wallet.currentChainId,
            ),
        ).assertError(ApiError(ReasonCode.SignatureNotValid, "Invalid signature"))

        // try creating a limit order not a multiple of tick size
        apiClient.tryCreateOrder(
            wallet.signOrder(
                CreateOrderApiRequest.Limit(
                    nonce = generateOrderNonce(),
                    marketId = usdcDaiMarket.id,
                    side = OrderSide.Buy,
                    amount = BigDecimal("1").inFundamentalUnits(usdc),
                    price = BigDecimal("2.015"),
                    signature = EvmSignature.emptySignature(),
                    verifyingChainId = ChainId.empty,
                ),
            ),
        ).assertError(
            ApiError(ReasonCode.ProcessingError, "Order price is not a multiple of tick size"),
        )

        val createLimitOrderResponse2 = apiClient.createLimitOrder(
            usdcDaiMarket,
            OrderSide.Buy,
            amount = BigDecimal("1"),
            price = BigDecimal("2"),
            wallet,
        )

        wsClient.apply {
            assertLimitOrderCreatedMessageReceived(createLimitOrderResponse2)
        }

        // try updating the price to something not a tick size
        apiClient.tryUpdateOrder(
            wallet.signOrder(
                UpdateOrderApiRequest(
                    orderId = createLimitOrderResponse2.orderId,
                    amount = BigDecimal("1").inFundamentalUnits(usdc),
                    marketId = createLimitOrderResponse2.order.marketId,
                    side = createLimitOrderResponse2.order.side,
                    price = BigDecimal("2.015"),
                    nonce = generateOrderNonce(),
                    signature = EvmSignature.emptySignature(),
                    verifyingChainId = ChainId.empty,
                ),
            ),
        ).assertError(
            ApiError(ReasonCode.ProcessingError, "Order price is not a multiple of tick size"),
        )

        // try updating and cancelling an order not created by this wallet - signature should fail
        val apiClient2 = TestApiClient()
        val wallet2 = Wallet(apiClient2)
        apiClient2.tryUpdateOrder(
            wallet.signOrder(
                UpdateOrderApiRequest(
                    orderId = createLimitOrderResponse2.orderId,
                    amount = BigDecimal("1").inFundamentalUnits(usdc),
                    marketId = createLimitOrderResponse2.order.marketId,
                    side = createLimitOrderResponse2.order.side,
                    price = BigDecimal("2.01"),
                    nonce = generateOrderNonce(),
                    signature = EvmSignature.emptySignature(),
                    verifyingChainId = ChainId.empty,
                ),
            ),
        ).assertError(
            ApiError(ReasonCode.SignatureNotValid, "Invalid signature"),
        )
        apiClient2.tryCancelOrder(createLimitOrderResponse2, wallet2).assertError(
            ApiError(ReasonCode.RejectedBySequencer, "Order not created by this wallet"),
        )

        // invalid signature
        apiClient.tryCancelOrder(createLimitOrderResponse2, wallet2).assertError(
            ApiError(ReasonCode.SignatureNotValid, "Invalid signature"),
        )

        // try update cancelled order
        apiClient.cancelOrder(createLimitOrderResponse2, wallet)
        wsClient.apply {
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(createLimitOrderResponse2.orderId, msg.order.id)
                assertEquals(OrderStatus.Cancelled, msg.order.status)
            }
        }
        apiClient.tryUpdateOrder(
            wallet.signOrder(
                UpdateOrderApiRequest(
                    orderId = createLimitOrderResponse2.orderId,
                    amount = BigDecimal("3").inFundamentalUnits(usdc),
                    marketId = createLimitOrderResponse2.order.marketId,
                    side = createLimitOrderResponse2.order.side,
                    price = BigDecimal("2.01"),
                    nonce = generateOrderNonce(),
                    signature = EvmSignature.emptySignature(),
                    verifyingChainId = ChainId.empty,
                ),
            ),
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
        wsClient.assertOrdersMessageReceived().orders

        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        wsClient.subscribeToLimits(usdcDaiMarket.id)
        wsClient.assertLimitsMessageReceived(usdcDaiMarket.id)

        Faucet.fund(wallet.address)
        val daiAmountToDeposit = AssetAmount(dai, "30")
        wallet.mintERC20(daiAmountToDeposit)
        wallet.deposit(daiAmountToDeposit)
        waitForBalance(apiClient, wsClient, listOf(ExpectedBalance(daiAmountToDeposit)))
        wsClient.assertLimitsMessageReceived(usdcDaiMarket.id)

        repeat(times = 10) {
            apiClient.createLimitOrder(
                usdcDaiMarket,
                OrderSide.Buy,
                amount = BigDecimal("1"),
                price = BigDecimal("2"),
                wallet,
            )
            wsClient.assertOrderCreatedMessageReceived()
            wsClient.assertLimitsMessageReceived(usdcDaiMarket.id)
        }
        assertEquals(10, apiClient.listOrders(listOf(OrderStatus.Open, OrderStatus.Partial, OrderStatus.Filled), usdcDaiMarket.id).orders.size)

        apiClient.cancelOpenOrders()

        repeat(times = 10) {
            wsClient.assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Cancelled, msg.order.status)
            }
        }
        wsClient.assertLimitsMessageReceived(usdcDaiMarket.id)

        assertTrue(apiClient.listOrders(emptyList(), usdcDaiMarket.id).orders.all { it.status == OrderStatus.Cancelled })

        wsClient.close()
    }

    @ParameterizedTest
    @MethodSource("chainIndices")
    fun `order execution`(chainIndex: Int) {
        val (market, baseSymbol, quoteSymbol) = if (chainIndex == 0) {
            Triple(btcEthMarket, btc, eth)
        } else {
            Triple(btc2Eth2Market, btc2, eth2)
        }

        val exchangeContractManager = ExchangeContractManager()

        val initialFeeAccountBalance = exchangeContractManager.getFeeBalance(quoteSymbol)

        val (takerApiClient, takerWallet, takerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "0.5"),
                AssetAmount(quoteSymbol, "2"),
            ),
            deposits = listOf(
                AssetAmount(quoteSymbol, "2"),
            ),
        )

        val (makerApiClient, makerWallet, makerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "0.5"),
                AssetAmount(quoteSymbol, "2"),
            ),
            deposits = listOf(
                AssetAmount(baseSymbol, "0.2"),
                AssetAmount(quoteSymbol, "2"),
            ),
        )

        // starting onchain balances
        val makerStartingBaseBalance = makerWallet.getExchangeBalance(baseSymbol)
        val makerStartingQuoteBalance = makerWallet.getExchangeBalance(quoteSymbol)
        val takerStartingBaseBalance = takerWallet.getExchangeBalance(baseSymbol)
        val takerStartingQuoteBalance = takerWallet.getExchangeBalance(quoteSymbol)

        // place an order and see that it gets accepted
        val limitBuyOrderApiResponse = makerApiClient.createLimitOrder(
            market,
            OrderSide.Buy,
            amount = BigDecimal("0.00013345"),
            price = BigDecimal("17.45"),
            makerWallet,
        )
        makerWsClient.apply {
            assertLimitOrderCreatedMessageReceived(limitBuyOrderApiResponse)
            assertLimitsMessageReceived(market, base = BigDecimal("0.2"), quote = BigDecimal("1.9976712975"))
        }

        listOf(makerWsClient, takerWsClient).forEach { wsClient ->
            wsClient.assertOrderBookMessageReceived(
                market.id,
                OrderBook(
                    marketId = market.id,
                    buy = listOf(
                        OrderBookEntry(price = "17.450", size = "0.00013345".toBigDecimal()),
                    ),
                    sell = emptyList(),
                    last = LastTrade("0.000", LastTradeDirection.Unchanged),
                ),
            )
        }

        val updatedLimitBuyOrderApiResponse = makerApiClient.updateOrder(
            market,
            limitBuyOrderApiResponse,
            amount = BigDecimal("0.00012345"),
            price = BigDecimal("17.50"),
            makerWallet,
        )
        makerWsClient.assertLimitOrderUpdatedMessageReceived(updatedLimitBuyOrderApiResponse)

        listOf(makerWsClient, takerWsClient).forEach { wsClient ->
            wsClient.assertOrderBookMessageReceived(
                market.id,
                OrderBook(
                    marketId = market.id,
                    buy = listOf(
                        OrderBookEntry(price = "17.500", size = "0.00012345".toBigDecimal()),
                    ),
                    sell = emptyList(),
                    last = LastTrade("0.000", LastTradeDirection.Unchanged),
                ),
            )
        }
        makerWsClient.assertLimitsMessageReceived(market, base = BigDecimal("0.2"), quote = BigDecimal("1.997839625"))

        // place a sell order
        val limitSellOrderApiResponse = makerApiClient.createLimitOrder(
            market,
            OrderSide.Sell,
            amount = BigDecimal("0.00154321"),
            price = BigDecimal("17.600"),
            makerWallet,
        )

        makerWsClient.apply {
            assertLimitOrderCreatedMessageReceived(limitSellOrderApiResponse)
            assertLimitsMessageReceived(market, base = BigDecimal("0.19845679"), quote = BigDecimal("1.997839625"))
        }

        listOf(makerWsClient, takerWsClient).forEach { wsClient ->
            wsClient.assertOrderBookMessageReceived(
                market.id,
                OrderBook(
                    marketId = market.id,
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
            market,
            limitSellOrderApiResponse,
            amount = BigDecimal("0.00054321"),
            price = BigDecimal("17.550"),
            makerWallet,
        )
        makerWsClient.assertLimitOrderUpdatedMessageReceived(updatedLimitSellOrderApiResponse)
        listOf(makerWsClient, takerWsClient).forEach { wsClient ->
            wsClient.assertOrderBookMessageReceived(
                market.id,
                OrderBook(
                    marketId = market.id,
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
        makerWsClient.assertLimitsMessageReceived(market, base = BigDecimal("0.19945679"), quote = BigDecimal("1.997839625"))

        // place a buy order and see it gets executed
        val marketBuyOrderApiResponse = takerApiClient.createMarketOrder(
            market,
            OrderSide.Buy,
            BigDecimal("0.00043210"),
            takerWallet,
        )

        takerWsClient.apply {
            assertMarketOrderCreatedMessageReceived(marketBuyOrderApiResponse)
            assertTradeCreatedMessageReceived(
                order = marketBuyOrderApiResponse,
                price = updatedLimitSellOrderApiResponse.order.price,
                amount = AssetAmount(baseSymbol, marketBuyOrderApiResponse.order.amount),
                fee = AssetAmount(quoteSymbol, "0.0001516671"),
                settlementStatus = SettlementStatus.Pending,
            )
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Filled, msg.order.status)
                assertEquals(1, msg.order.executions.size)
                assertAmount(AssetAmount(baseSymbol, "0.00043210"), msg.order.executions[0].amount)
                assertAmount(AssetAmount(quoteSymbol, "17.550"), msg.order.executions[0].price)
                assertEquals(ExecutionRole.Taker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = BigDecimal("0"), available = BigDecimal("0.00043210")),
                    ExpectedBalance(quoteSymbol, total = BigDecimal("2.0"), available = BigDecimal("1.9922649779")),
                ),
            )
        }

        makerWsClient.apply {
            assertTradeCreatedMessageReceived(
                order = updatedLimitSellOrderApiResponse,
                price = updatedLimitSellOrderApiResponse.order.price,
                amount = AssetAmount(baseSymbol, "0.00043210"),
                fee = AssetAmount(quoteSymbol, "0.00007583355"),
                settlementStatus = SettlementStatus.Pending,
            )
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Partial, msg.order.status)
                assertEquals(1, msg.order.executions.size)
                assertAmount(AssetAmount(baseSymbol, "0.00043210"), msg.order.executions[0].amount)
                assertAmount(AssetAmount(quoteSymbol, "17.550"), msg.order.executions[0].price)
                assertEquals(ExecutionRole.Maker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = BigDecimal("0.2"), available = BigDecimal("0.1995679")),
                    ExpectedBalance(quoteSymbol, total = BigDecimal("2.0"), available = BigDecimal("2.00750752145")),
                ),
            )
        }

        listOf(makerWsClient, takerWsClient).forEach { wsClient ->
            wsClient.assertOrderBookMessageReceived(
                market.id,
                OrderBook(
                    marketId = market.id,
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

        val trade = getTradesForOrders(listOf(marketBuyOrderApiResponse.orderId)).first().also {
            assertAmount(AssetAmount(baseSymbol, "0.00043210"), it.amount)
            assertAmount(AssetAmount(quoteSymbol, "17.550"), it.price)
        }

        takerWsClient.apply {
            assertPricesMessageReceived(market.id) { msg ->
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
            assertLimitsMessageReceived(market, base = BigDecimal("0.0004321"), quote = BigDecimal("1.9922649779"))
        }

        makerWsClient.apply {
            assertPricesMessageReceived(market.id) { msg ->
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
            assertLimitsMessageReceived(market, base = BigDecimal("0.19945679"), quote = BigDecimal("2.005347146450000000"))
        }

        waitForSettlementToFinish(listOf(trade.id.value))

        val baseQuantity = AssetAmount(baseSymbol, trade.amount)
        val notional = trade.price.ofAsset(quoteSymbol) * baseQuantity.amount
        val makerFee = notional * BigDecimal("0.01")
        val takerFee = notional * BigDecimal("0.02")

        assertBalances(
            listOf(
                ExpectedBalance(makerStartingBaseBalance - baseQuantity),
                ExpectedBalance(makerStartingQuoteBalance + notional - makerFee),
            ),
            makerApiClient.getBalances().balances,
        )
        assertBalances(
            listOf(
                ExpectedBalance(takerStartingBaseBalance + baseQuantity),
                ExpectedBalance(takerStartingQuoteBalance - notional - takerFee),
            ),
            takerApiClient.getBalances().balances,
        )

        takerWsClient.assertBalancesMessageReceived(
            listOf(
                ExpectedBalance(takerStartingBaseBalance + baseQuantity),
                ExpectedBalance(takerStartingQuoteBalance - notional - takerFee),
            ),
        )
        takerWsClient.assertTradeUpdatedMessageReceived { msg ->
            assertEquals(marketBuyOrderApiResponse.orderId, msg.trade.orderId)
            assertEquals(SettlementStatus.Completed, msg.trade.settlementStatus)
        }

        makerWsClient.assertBalancesMessageReceived(
            listOf(
                ExpectedBalance(makerStartingBaseBalance - baseQuantity),
                ExpectedBalance(makerStartingQuoteBalance + notional - makerFee),
            ),
        )
        makerWsClient.assertTradeUpdatedMessageReceived { msg ->
            assertEquals(updatedLimitSellOrderApiResponse.order.orderId, msg.trade.orderId)
            assertEquals(SettlementStatus.Completed, msg.trade.settlementStatus)
        }

        // place a sell order and see it gets executed
        val marketSellOrderApiResponse = takerApiClient.createMarketOrder(
            market,
            OrderSide.Sell,
            BigDecimal("0.00012346"),
            takerWallet,
        )

        takerWsClient.apply {
            assertMarketOrderCreatedMessageReceived(marketSellOrderApiResponse)
            assertTradeCreatedMessageReceived(
                order = marketSellOrderApiResponse,
                price = BigDecimal("17.500"),
                amount = AssetAmount(baseSymbol, "0.00012345"),
                fee = AssetAmount(quoteSymbol, "0.0000432075"),
                settlementStatus = SettlementStatus.Pending,
            )
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Partial, msg.order.status)
                assertEquals(1, msg.order.executions.size)
                assertAmount(AssetAmount(baseSymbol, "0.00012345"), msg.order.executions[0].amount)
                assertAmount(AssetAmount(quoteSymbol, "17.500"), msg.order.executions[0].price)
                assertEquals(ExecutionRole.Taker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = BigDecimal("0.0004321"), available = BigDecimal("0.00030865")),
                    ExpectedBalance(quoteSymbol, total = BigDecimal("1.9922649779"), available = BigDecimal("1.9943821454")),
                ),
            )
        }

        makerWsClient.apply {
            assertTradeCreatedMessageReceived(
                order = updatedLimitBuyOrderApiResponse,
                price = updatedLimitBuyOrderApiResponse.order.price,
                amount = AssetAmount(baseSymbol, "0.00012345"),
                fee = AssetAmount(quoteSymbol, "0.00002160375"),
                settlementStatus = SettlementStatus.Pending,
            )
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Filled, msg.order.status)
                assertEquals(1, msg.order.executions.size)
                assertAmount(AssetAmount(baseSymbol, "0.00012345"), msg.order.executions[0].amount)
                assertAmount(AssetAmount(quoteSymbol, "17.500"), msg.order.executions[0].price)
                assertEquals(ExecutionRole.Maker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = BigDecimal("0.1995679"), available = BigDecimal("0.19969135")),
                    ExpectedBalance(quoteSymbol, total = BigDecimal("2.00750752145"), available = BigDecimal("2.0053255427")),
                ),
            )
        }

        listOf(makerWsClient, takerWsClient).forEach { wsClient ->
            wsClient.assertOrderBookMessageReceived(
                market.id,
                OrderBook(
                    marketId = market.id,
                    buy = emptyList(),
                    sell = listOf(
                        OrderBookEntry(price = "17.550", size = "0.00011111".toBigDecimal()),
                    ),
                    last = LastTrade("17.500", LastTradeDirection.Down),
                ),
            )
        }

        val trade2 = getTradesForOrders(listOf(marketSellOrderApiResponse.orderId)).first().also {
            assertAmount(AssetAmount(baseSymbol, "0.00012345"), it.amount)
            assertAmount(AssetAmount(quoteSymbol, "17.500"), it.price)
        }

        waitForSettlementToFinish(listOf(trade2.id.value))

        val baseQuantity2 = AssetAmount(baseSymbol, trade2.amount)
        val notional2 = trade2.price.ofAsset(quoteSymbol) * baseQuantity2.amount
        val makerFee2 = notional2 * BigDecimal("0.01")
        val takerFee2 = notional2 * BigDecimal("0.02")

        assertBalances(
            listOf(
                ExpectedBalance(makerStartingBaseBalance - baseQuantity + baseQuantity2),
                ExpectedBalance(makerStartingQuoteBalance + (notional - makerFee) - (notional2 + makerFee2)),
            ),
            makerApiClient.getBalances().balances,
        )
        assertBalances(
            listOf(
                ExpectedBalance(takerStartingBaseBalance + baseQuantity - baseQuantity2),
                ExpectedBalance(takerStartingQuoteBalance - (notional + takerFee) + (notional2 - takerFee2)),
            ),
            takerApiClient.getBalances().balances,
        )

        takerWsClient.apply {
            assertPricesMessageReceived(market.id) { msg ->
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
            assertLimitsMessageReceived(market, base = BigDecimal("0.00030865"), quote = BigDecimal("1.9943821454"))
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(takerStartingBaseBalance + baseQuantity - baseQuantity2),
                    ExpectedBalance(takerStartingQuoteBalance - (notional + takerFee) + (notional2 - takerFee2)),
                ),
            )
            assertTradeUpdatedMessageReceived { msg ->
                assertEquals(marketSellOrderApiResponse.orderId, msg.trade.orderId)
                assertEquals(SettlementStatus.Completed, msg.trade.settlementStatus)
            }
        }

        makerWsClient.apply {
            assertPricesMessageReceived(market.id) { msg ->
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
            assertLimitsMessageReceived(market, base = BigDecimal("0.19958024"), quote = BigDecimal("2.0053255427"))
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(makerStartingBaseBalance - baseQuantity + baseQuantity2),
                    ExpectedBalance(makerStartingQuoteBalance + (notional - makerFee) - (notional2 + makerFee2)),
                ),
            )
            assertTradeUpdatedMessageReceived { msg ->
                assertEquals(updatedLimitBuyOrderApiResponse.order.orderId, msg.trade.orderId)
                assertEquals(SettlementStatus.Completed, msg.trade.settlementStatus)
            }
        }

        val takerOrderCount = takerApiClient.listOrders(statuses = listOf(OrderStatus.Open, OrderStatus.Partial)).orders.filterNot { it is Order.Market }.size
        takerApiClient.cancelOpenOrders()
        repeat(takerOrderCount) {
            takerWsClient.assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Cancelled, msg.order.status)
            }
        }

        val makerOrderCount = makerApiClient.listOrders(statuses = listOf(OrderStatus.Open, OrderStatus.Partial)).orders.filterNot { it is Order.Market }.size
        makerApiClient.cancelOpenOrders()
        repeat(makerOrderCount) {
            makerWsClient.assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Cancelled, msg.order.status)
            }
        }

        listOf(makerWsClient, takerWsClient).forEach { wsClient ->
            wsClient.assertOrderBookMessageReceived(
                market.id,
                OrderBook(
                    marketId = market.id,
                    buy = emptyList(),
                    sell = emptyList(),
                    last = LastTrade("17.500", LastTradeDirection.Down),
                ),
            )
        }
        makerWsClient.assertLimitsMessageReceived(market.id)

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
                    assertAmount(AssetAmount(quoteSymbol, "17.500"), price)
                    assertAmount(AssetAmount(baseSymbol, "0.00012345"), amount)
                    assertFee(AssetAmount(quoteSymbol, "0.0000432075"), feeAmount, feeSymbol)
                    assertEquals(SettlementStatus.Completed, settlementStatus)
                }
                msg.trades[1].apply {
                    assertEquals(marketBuyOrderApiResponse.orderId, orderId)
                    assertEquals(marketBuyOrderApiResponse.order.marketId, marketId)
                    assertEquals(marketBuyOrderApiResponse.order.side, side)
                    assertAmount(updatedLimitSellOrderApiResponse.order.price.ofAsset(quoteSymbol), price)
                    assertAmount(AssetAmount(baseSymbol, marketBuyOrderApiResponse.order.amount), amount)
                    assertFee(AssetAmount(quoteSymbol, "0.0001516671"), feeAmount, feeSymbol)
                    assertEquals(SettlementStatus.Completed, settlementStatus)
                }
            }

            subscribeToOrderBook(market.id)
            assertOrderBookMessageReceived(
                market.id,
                OrderBook(
                    marketId = market.id,
                    buy = emptyList(),
                    sell = emptyList(),
                    last = LastTrade("17.500", LastTradeDirection.Down),
                ),
            )

            subscribeToLimits(market.id)
            assertLimitsMessageReceived(market, base = BigDecimal("0.00030865"), quote = BigDecimal("1.9943821454"))
        }.close()

        makerWsClient.close()

        // verify that fees have settled correctly on chain
        assertEquals(
            makerFee + takerFee + makerFee2 + takerFee2,
            exchangeContractManager.getFeeBalance(quoteSymbol) - initialFeeAccountBalance,
        )
    }

    @ParameterizedTest
    @MethodSource("chainIndices")
    fun `order batches`(chainIndex: Int) {
        val (market, baseSymbol, quoteSymbol) = if (chainIndex == 0) {
            Triple(btcUsdcMarket, btc, usdc)
        } else {
            Triple(btc2Usdc2Market, btc2, usdc2)
        }

        val (makerApiClient, makerWallet, makerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "0.5"),
                AssetAmount(quoteSymbol, "500"),
            ),
            deposits = listOf(
                AssetAmount(baseSymbol, "0.2"),
                AssetAmount(quoteSymbol, "500"),
            ),
            subscribeToOrderBook = false,
        )

        val (takerApiClient, takerWallet, takerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "0.5"),
                AssetAmount(quoteSymbol, "500"),
            ),
            deposits = listOf(
                AssetAmount(quoteSymbol, "500"),
            ),
            subscribeToOrderBook = false,
        )

        // starting onchain balances
        val makerStartingBaseBalance = makerWallet.getExchangeBalance(baseSymbol)
        val makerStartingQuoteBalance = makerWallet.getExchangeBalance(quoteSymbol)
        val takerStartingBaseBalance = takerWallet.getExchangeBalance(baseSymbol)
        val takerStartingQuoteBalance = takerWallet.getExchangeBalance(quoteSymbol)

        // place 3 orders
        val createBatchLimitOrders = makerApiClient.batchOrders(
            BatchOrdersApiRequest(
                marketId = market.id,
                createOrders = listOf("0.00001", "0.00002", "0.0003").map {
                    makerWallet.signOrder(
                        CreateOrderApiRequest.Limit(
                            nonce = generateOrderNonce(),
                            marketId = market.id,
                            side = OrderSide.Sell,
                            amount = AssetAmount(baseSymbol, it).inFundamentalUnits,
                            price = BigDecimal("68400.000"),
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    )
                },
                updateOrders = listOf(),
                cancelOrders = listOf(),
            ),
        )

        assertEquals(3, createBatchLimitOrders.createdOrders.count())
        repeat(3) { makerWsClient.assertOrderCreatedMessageReceived() }
        makerWsClient.assertLimitsMessageReceived(market, base = BigDecimal("0.19967"), quote = BigDecimal("500"))

        val batchOrderResponse = makerApiClient.batchOrders(
            BatchOrdersApiRequest(
                marketId = market.id,
                createOrders = listOf("0.0004", "0.0005", "0.0006").map {
                    makerWallet.signOrder(
                        CreateOrderApiRequest.Limit(
                            nonce = generateOrderNonce(),
                            marketId = market.id,
                            side = OrderSide.Sell,
                            amount = AssetAmount(baseSymbol, it).inFundamentalUnits,
                            price = BigDecimal("68400.000"),
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    )
                },
                updateOrders = listOf(
                    makerWallet.signOrder(
                        UpdateOrderApiRequest(
                            orderId = createBatchLimitOrders.createdOrders[0].orderId,
                            amount = AssetAmount(baseSymbol, "0.0001").inFundamentalUnits,
                            marketId = createBatchLimitOrders.createdOrders[0].order.marketId,
                            side = createBatchLimitOrders.createdOrders[0].order.side,
                            price = BigDecimal("68405.000"),
                            nonce = generateOrderNonce(),
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    ),
                    makerWallet.signOrder(
                        UpdateOrderApiRequest(
                            orderId = createBatchLimitOrders.createdOrders[1].orderId,
                            amount = AssetAmount(baseSymbol, "0.0002").inFundamentalUnits,
                            marketId = createBatchLimitOrders.createdOrders[1].order.marketId,
                            side = createBatchLimitOrders.createdOrders[2].order.side,
                            price = BigDecimal("68405.000"),
                            nonce = generateOrderNonce(),
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    ),
                    makerWallet.signOrder(
                        UpdateOrderApiRequest(
                            orderId = OrderId.generate(),
                            amount = AssetAmount(baseSymbol, "0.0002").inFundamentalUnits,
                            marketId = createBatchLimitOrders.createdOrders[1].order.marketId,
                            side = createBatchLimitOrders.createdOrders[2].order.side,
                            price = BigDecimal("68405.000"),
                            nonce = generateOrderNonce(),
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    ),
                ),
                cancelOrders = listOf(
                    createBatchLimitOrders.createdOrders[2].toCancelOrderRequest(makerWallet),
                    makerWallet.signCancelOrder(
                        CancelOrderApiRequest(
                            orderId = OrderId.generate(),
                            marketId = market.id,
                            amount = BigInteger.ZERO,
                            side = OrderSide.Buy,
                            nonce = generateOrderNonce(),
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(3, batchOrderResponse.createdOrders.count { it.requestStatus == RequestStatus.Accepted })
        assertEquals(2, batchOrderResponse.updatedOrders.count { it.requestStatus == RequestStatus.Accepted })
        assertEquals(1, batchOrderResponse.updatedOrders.count { it.requestStatus == RequestStatus.Rejected })
        assertEquals(1, batchOrderResponse.canceledOrders.count { it.requestStatus == RequestStatus.Accepted })
        assertEquals(1, batchOrderResponse.canceledOrders.count { it.requestStatus == RequestStatus.Rejected })

        repeat(3) { makerWsClient.assertOrderCreatedMessageReceived() }
        makerWsClient.assertLimitsMessageReceived(market, base = BigDecimal("0.1979"), quote = BigDecimal("500"))
        repeat(3) { makerWsClient.assertOrderUpdatedMessageReceived() }
        makerWsClient.assertLimitsMessageReceived(market, base = BigDecimal("0.1982"), quote = BigDecimal("500"))

        assertEquals(5, makerApiClient.listOrders(listOf(OrderStatus.Open), null).orders.size)

        // total BTC available is 0.0001 + 0.0002 + 0.0004 + 0.0005 + 0.0006 = 0.0018
        val takerOrderAmount = AssetAmount(baseSymbol, "0.0018")
        // create a market orders that should match against the 5 limit orders
        takerApiClient.createMarketOrder(
            market,
            OrderSide.Buy,
            amount = takerOrderAmount.amount,
            takerWallet,
        )

        takerWsClient.apply {
            assertOrderCreatedMessageReceived()
            repeat(5) { assertTradeCreatedMessageReceived() }
            assertOrderUpdatedMessageReceived()
            assertBalancesMessageReceived()
            assertPricesMessageReceived(market.id) { msg ->
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
            assertLimitsMessageReceived(market, base = BigDecimal("0.0018"), quote = BigDecimal("374.416988"))
        }

        makerWsClient.apply {
            repeat(5) { assertTradeCreatedMessageReceived() }
            repeat(5) { assertOrderUpdatedMessageReceived() }
            assertBalancesMessageReceived()
            assertPricesMessageReceived(market.id) { msg ->
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
            assertLimitsMessageReceived(market, base = BigDecimal("0.1982"), quote = BigDecimal("621.889395"))
        }

        val takerOrders = takerApiClient.listOrders(emptyList(), market.id).orders
        assertEquals(1, takerOrders.count { it.status == OrderStatus.Filled })
        // should be 5 filled maker orders
        assertEquals(5, makerApiClient.listOrders(listOf(OrderStatus.Filled), market.id).orders.size)

        // now verify the trades

        val expectedAmounts = listOf("0.0001", "0.0002", "0.0004", "0.0005", "0.0006").map { AssetAmount(baseSymbol, it) }.toSet()
        val trades = getTradesForOrders(takerOrders.map { it.id })
        val prices = trades.map { it.price }.toSet()

        assertEquals(5, trades.size)
        assertEquals(expectedAmounts, trades.map { AssetAmount(baseSymbol, it.amount) }.toSet())
        assertEquals(prices.size, 2)

        waitForSettlementToFinish(trades.map { it.id.value })

        val notionals = trades.map { it.price.ofAsset(quoteSymbol) * AssetAmount(baseSymbol, it.amount).amount }
        val notional = notionals.sum()
        val makerFees = notionals.map { it * BigDecimal("0.01") }.sum()
        val takerFees = notionals.map { it * BigDecimal("0.02") }.sum()

        assertBalances(
            listOf(
                ExpectedBalance(makerStartingBaseBalance - takerOrderAmount),
                ExpectedBalance(makerStartingQuoteBalance + notional - makerFees),
            ),
            makerApiClient.getBalances().balances,
        )
        assertBalances(
            listOf(
                ExpectedBalance(takerStartingBaseBalance + takerOrderAmount),
                ExpectedBalance(takerStartingQuoteBalance - notional - takerFees),
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
        val (market, baseSymbol, quoteSymbol) = if (chainIndex == 0) {
            Triple(btcEthMarket, btc, eth)
        } else {
            Triple(btc2Eth2Market, btc2, eth2)
        }

        val (takerApiClient, takerWallet, takerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "0.5"),
                AssetAmount(quoteSymbol, "2"),
            ),
            deposits = listOf(
                AssetAmount(baseSymbol, "0.1"),
                AssetAmount(quoteSymbol, "2"),
            ),
        )

        val (makerApiClient, makerWallet, makerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "0.5"),
                AssetAmount(quoteSymbol, "2"),
            ),
            deposits = listOf(
                AssetAmount(quoteSymbol, "2"),
            ),
        )

        // starting onchain balances
        val makerStartingBaseBalance = makerWallet.getExchangeBalance(baseSymbol)
        val makerStartingQuoteBalance = makerWallet.getExchangeBalance(quoteSymbol)
        val takerStartingBaseBalance = takerWallet.getExchangeBalance(baseSymbol)
        val takerStartingQuoteBalance = takerWallet.getExchangeBalance(quoteSymbol)

        val baseWithdrawalAmount = AssetAmount(baseSymbol, "0.015")

        val pendingBaseWithdrawal = takerApiClient.createWithdrawal(takerWallet.signWithdraw(baseSymbol.name, baseWithdrawalAmount.inFundamentalUnits)).withdrawal
        assertEquals(WithdrawalStatus.Pending, pendingBaseWithdrawal.status)

        takerWsClient.apply {
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = takerStartingBaseBalance, available = takerStartingBaseBalance - baseWithdrawalAmount),
                    ExpectedBalance(quoteSymbol, total = takerStartingQuoteBalance, available = takerStartingQuoteBalance),
                ),
            )
            assertLimitsMessageReceived(market, base = takerStartingBaseBalance - baseWithdrawalAmount, quote = takerStartingQuoteBalance)
        }

        waitForFinalizedWithdrawal(pendingBaseWithdrawal.id)

        takerWsClient.apply {
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = takerStartingBaseBalance - baseWithdrawalAmount, available = takerStartingBaseBalance - baseWithdrawalAmount),
                    ExpectedBalance(quoteSymbol, total = takerStartingQuoteBalance, available = takerStartingQuoteBalance),
                ),
            )
        }

        // hack to resubmit the same transaction again bypassing the sequencer. This way taker will not have a
        // sufficient on chain balance for the order so settlement will fail.
        transaction {
            WithdrawalEntity[pendingBaseWithdrawal.id].status = WithdrawalStatus.Sequenced
        }

        waitForFinalizedWithdrawal(pendingBaseWithdrawal.id)

        takerWsClient.apply {
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = takerStartingBaseBalance - baseWithdrawalAmount * BigDecimal(2), available = takerStartingBaseBalance - baseWithdrawalAmount),
                    ExpectedBalance(quoteSymbol, total = takerStartingQuoteBalance, available = takerStartingQuoteBalance),
                ),
            )
        }

        // place a limit order
        val limitBuyOrderApiResponse = makerApiClient.createLimitOrder(
            market,
            OrderSide.Buy,
            amount = BigDecimal("0.08"),
            price = BigDecimal("17.55"),
            makerWallet,
        )
        makerWsClient.apply {
            assertLimitOrderCreatedMessageReceived(limitBuyOrderApiResponse)
            assertLimitsMessageReceived(market, base = BigDecimal("0"), quote = BigDecimal("0.596"))
        }

        listOf(makerWsClient, takerWsClient).forEach { wsClient ->
            wsClient.assertOrderBookMessageReceived(
                market.id,
                OrderBook(
                    marketId = market.id,
                    buy = listOf(
                        OrderBookEntry(price = "17.550", size = "0.08".toBigDecimal()),
                    ),
                    sell = emptyList(),
                    last = LastTrade("0.000", LastTradeDirection.Unchanged),
                ),
            )
        }

        // place a sell order
        val marketBuyOrderApiResponse = takerApiClient.createMarketOrder(
            market,
            OrderSide.Sell,
            BigDecimal("0.08"),
            takerWallet,
        )

        takerWsClient.apply {
            assertMarketOrderCreatedMessageReceived(marketBuyOrderApiResponse)
            assertTradeCreatedMessageReceived(
                order = marketBuyOrderApiResponse,
                price = BigDecimal("17.55"),
                amount = AssetAmount(baseSymbol, marketBuyOrderApiResponse.order.amount),
                fee = AssetAmount(quoteSymbol, "0.02808"),
                settlementStatus = SettlementStatus.Pending,
            )
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Filled, msg.order.status)
                assertEquals(1, msg.order.executions.size)
                assertAmount(AssetAmount(baseSymbol, "0.08"), msg.order.executions[0].amount)
                assertAmount(AssetAmount(quoteSymbol, "17.550"), msg.order.executions[0].price)
                assertEquals(ExecutionRole.Taker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = BigDecimal("0.07"), available = BigDecimal("0.005")),
                    ExpectedBalance(quoteSymbol, total = BigDecimal("2.0"), available = BigDecimal("3.37592")),
                ),
            )
        }

        makerWsClient.apply {
            assertTradeCreatedMessageReceived(
                order = limitBuyOrderApiResponse,
                price = BigDecimal("17.55"),
                amount = AssetAmount(baseSymbol, "0.08"),
                fee = AssetAmount(quoteSymbol, "0.01404"),
                settlementStatus = SettlementStatus.Pending,
            )
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Filled, msg.order.status)
                assertEquals(1, msg.order.executions.size)
                assertAmount(AssetAmount(baseSymbol, "0.08"), msg.order.executions[0].amount)
                assertAmount(AssetAmount(quoteSymbol, "17.550"), msg.order.executions[0].price)
                assertEquals(ExecutionRole.Maker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = BigDecimal("0"), available = BigDecimal("0.08")),
                    ExpectedBalance(quoteSymbol, total = BigDecimal("2.0"), available = BigDecimal("0.58196")),
                ),
            )
        }

        listOf(makerWsClient, takerWsClient).forEach { wsClient ->
            wsClient.assertOrderBookMessageReceived(
                market.id,
                OrderBook(
                    marketId = market.id,
                    buy = listOf(),
                    sell = listOf(),
                    last = LastTrade("17.550", LastTradeDirection.Up),
                ),
            )
        }
        val trade = getTradesForOrders(listOf(marketBuyOrderApiResponse.orderId)).first()

        takerWsClient.apply {
            assertPricesMessageReceived(market.id)
            assertLimitsMessageReceived(market, base = BigDecimal("0.005"), quote = BigDecimal("3.37592"))
        }

        makerWsClient.apply {
            assertPricesMessageReceived(market.id)
            assertLimitsMessageReceived(market, base = BigDecimal("0.08"), quote = BigDecimal("0.58196"))
        }

        assertAmount(AssetAmount(baseSymbol, "0.08"), trade.amount)
        assertAmount(AssetAmount(quoteSymbol, "17.550"), trade.price)

        waitForSettlementToFinish(listOf(trade.id.value), SettlementStatus.Failed)

        takerWsClient.apply {
            assertMessagesReceived(3) { messages ->
                assertContainsTradeUpdatedMessage(messages) { msg ->
                    assertEquals(marketBuyOrderApiResponse.orderId, msg.trade.orderId)
                    assertEquals(SettlementStatus.Failed, msg.trade.settlementStatus)
                    assertEquals("Insufficient Balance", msg.trade.error)
                }
                assertContainsBalancesMessage(
                    messages,
                    listOf(
                        ExpectedBalance(baseSymbol, total = AssetAmount(baseSymbol, "0.07"), available = takerStartingBaseBalance - baseWithdrawalAmount),
                        ExpectedBalance(takerStartingQuoteBalance),
                    ),
                )
                assertContainsLimitsMessage(messages, market, base = takerStartingBaseBalance - baseWithdrawalAmount, quote = takerStartingQuoteBalance)
            }
        }

        makerWsClient.apply {
            assertMessagesReceived(3) { messages ->
                assertContainsTradeUpdatedMessage(messages) { msg ->
                    assertEquals(limitBuyOrderApiResponse.orderId, msg.trade.orderId)
                    assertEquals(SettlementStatus.Failed, msg.trade.settlementStatus)
                    assertEquals("Insufficient Balance", msg.trade.error)
                }
                assertContainsBalancesMessage(
                    messages,
                    listOf(
                        ExpectedBalance(makerStartingBaseBalance),
                        ExpectedBalance(makerStartingQuoteBalance),
                    ),
                )
                assertContainsLimitsMessage(messages, market, base = makerStartingBaseBalance, quote = makerStartingQuoteBalance)
            }
        }

        makerWsClient.close()
    }

    @Test
    fun `cross chain trade execution`() {
        val market = btcbtc2Market
        val baseSymbol = btc
        val quoteSymbol = btc2

        val exchangeContractManager = ExchangeContractManager()

        val initialFeeAccountBalance = exchangeContractManager.getFeeBalance(quoteSymbol)

        val (takerApiClient, takerWallet, takerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "0.5"),
                AssetAmount(quoteSymbol, "0.6"),
            ),
            deposits = listOf(
                AssetAmount(quoteSymbol, "0.5"),
            ),
            subscribeToOrderBook = false,
            subscribeToOrderPrices = false,
        )

        val (makerApiClient, makerWallet, makerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "0.5"),
                AssetAmount(quoteSymbol, "0.7"),
            ),
            deposits = listOf(
                AssetAmount(baseSymbol, "0.4"),
                AssetAmount(quoteSymbol, "0.6"),
            ),
            subscribeToOrderBook = false,
            subscribeToOrderPrices = false,
        )

        // starting onchain balances
        val makerStartingBaseBalance = makerWallet.getExchangeBalance(baseSymbol)
        val makerStartingQuoteBalance = makerWallet.getExchangeBalance(quoteSymbol)
        val takerStartingBaseBalance = takerWallet.getExchangeBalance(baseSymbol)
        val takerStartingQuoteBalance = takerWallet.getExchangeBalance(quoteSymbol)

        // place an order and see that it gets accepted
        val limitBuyOrderApiResponse = makerApiClient.createLimitOrder(
            market,
            OrderSide.Buy,
            amount = BigDecimal("0.12"),
            price = BigDecimal("0.999"),
            makerWallet,
        )
        makerWsClient.apply {
            assertLimitOrderCreatedMessageReceived(limitBuyOrderApiResponse)
            assertLimitsMessageReceived(market, base = BigDecimal("0.4"), quote = BigDecimal("0.48012"))
        }

        // place a sell order
        val limitSellOrderApiResponse = makerApiClient.createLimitOrder(
            market,
            OrderSide.Sell,
            amount = BigDecimal("0.22"),
            price = BigDecimal("1.002"),
            makerWallet,
        )

        makerWsClient.apply {
            assertLimitOrderCreatedMessageReceived(limitSellOrderApiResponse)
            assertLimitsMessageReceived(market, base = BigDecimal("0.180"), quote = BigDecimal("0.48012"))
        }

        // place a buy order and see it gets executed
        val marketBuyOrderApiResponse = takerApiClient.createMarketOrder(
            market,
            OrderSide.Buy,
            BigDecimal("0.05"),
            takerWallet,
        )

        takerWsClient.apply {
            assertMarketOrderCreatedMessageReceived(marketBuyOrderApiResponse)
            assertTradeCreatedMessageReceived(
                order = marketBuyOrderApiResponse,
                price = (limitSellOrderApiResponse.order as CreateOrderApiRequest.Limit).price,
                amount = AssetAmount(baseSymbol, marketBuyOrderApiResponse.order.amount),
                fee = AssetAmount(quoteSymbol, "0.001002"),
                settlementStatus = SettlementStatus.Pending,
            )
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Filled, msg.order.status)
                assertEquals(1, msg.order.executions.size)
                assertAmount(AssetAmount(baseSymbol, "0.05"), msg.order.executions[0].amount)
                assertAmount(AssetAmount(quoteSymbol, "1.002"), msg.order.executions[0].price)
                assertEquals(ExecutionRole.Taker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = BigDecimal("0"), available = BigDecimal("0.05")),
                    ExpectedBalance(quoteSymbol, total = BigDecimal("0.5"), available = BigDecimal("0.448898")),
                ),
            )
        }

        makerWsClient.apply {
            assertTradeCreatedMessageReceived(
                order = limitSellOrderApiResponse,
                price = (limitSellOrderApiResponse.order as CreateOrderApiRequest.Limit).price,
                amount = AssetAmount(baseSymbol, "0.05"),
                fee = AssetAmount(quoteSymbol, "0.000501"),
                settlementStatus = SettlementStatus.Pending,
            )
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Partial, msg.order.status)
                assertEquals(1, msg.order.executions.size)
                assertAmount(AssetAmount(baseSymbol, "0.05"), msg.order.executions[0].amount)
                assertAmount(AssetAmount(quoteSymbol, "1.002"), msg.order.executions[0].price)
                assertEquals(ExecutionRole.Maker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = BigDecimal("0.4"), available = BigDecimal("0.35")),
                    ExpectedBalance(quoteSymbol, total = BigDecimal("0.6"), available = BigDecimal("0.649599")),
                ),
            )
        }

        val trade = getTradesForOrders(listOf(marketBuyOrderApiResponse.orderId)).first().also {
            assertAmount(AssetAmount(baseSymbol, "0.05"), it.amount)
            assertAmount(AssetAmount(quoteSymbol, "1.0020"), it.price)
        }

        takerWsClient.apply {
            assertLimitsMessageReceived(market, base = BigDecimal("0.05"), quote = BigDecimal("0.448898"))
        }

        makerWsClient.apply {
            assertLimitsMessageReceived(market, base = BigDecimal("0.18"), quote = BigDecimal("0.529719"))
        }

        waitForSettlementToFinish(listOf(trade.id.value))

        val baseQuantity = AssetAmount(baseSymbol, trade.amount)
        val notional = trade.price.ofAsset(quoteSymbol) * baseQuantity.amount
        val makerFee = notional * BigDecimal("0.01")
        val takerFee = notional * BigDecimal("0.02")

        assertBalances(
            listOf(
                ExpectedBalance(makerStartingBaseBalance - baseQuantity),
                ExpectedBalance(makerStartingQuoteBalance + notional - makerFee),
            ),
            makerApiClient.getBalances().balances,
        )
        assertBalances(
            listOf(
                ExpectedBalance(takerStartingBaseBalance + baseQuantity),
                ExpectedBalance(takerStartingQuoteBalance - notional - takerFee),
            ),
            takerApiClient.getBalances().balances,
        )

        takerWsClient.assertBalancesMessageReceived(
            listOf(
                ExpectedBalance(takerStartingBaseBalance + baseQuantity),
                ExpectedBalance(takerStartingQuoteBalance - notional - takerFee),
            ),
        )
        takerWsClient.assertTradeUpdatedMessageReceived { msg ->
            assertEquals(marketBuyOrderApiResponse.orderId, msg.trade.orderId)
            assertEquals(SettlementStatus.Completed, msg.trade.settlementStatus)
        }

        makerWsClient.assertBalancesMessageReceived(
            listOf(
                ExpectedBalance(makerStartingBaseBalance - baseQuantity),
                ExpectedBalance(makerStartingQuoteBalance + notional - makerFee),
            ),
        )
        makerWsClient.assertTradeUpdatedMessageReceived { msg ->
            assertEquals(limitSellOrderApiResponse.orderId, msg.trade.orderId)
            assertEquals(SettlementStatus.Completed, msg.trade.settlementStatus)
        }

        // place a sell order and see it gets executed
        val marketSellOrderApiResponse = takerApiClient.createMarketOrder(
            market,
            OrderSide.Sell,
            BigDecimal("0.012345"),
            takerWallet,
        )

        takerWsClient.apply {
            assertMarketOrderCreatedMessageReceived(marketSellOrderApiResponse)
            assertTradeCreatedMessageReceived(
                order = marketSellOrderApiResponse,
                price = BigDecimal("0.999"),
                amount = AssetAmount(baseSymbol, "0.012345"),
                fee = AssetAmount(quoteSymbol, "0.0002466531"),
                settlementStatus = SettlementStatus.Pending,
            )
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Filled, msg.order.status)
                assertEquals(1, msg.order.executions.size)
                assertAmount(AssetAmount(baseSymbol, "0.012345"), msg.order.executions[0].amount)
                assertAmount(AssetAmount(quoteSymbol, "0.999"), msg.order.executions[0].price)
                assertEquals(ExecutionRole.Taker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = BigDecimal("0.05"), available = BigDecimal("0.037655")),
                    ExpectedBalance(quoteSymbol, total = BigDecimal("0.448898"), available = BigDecimal("0.4609840019")),
                ),
            )
        }

        makerWsClient.apply {
            assertTradeCreatedMessageReceived(
                order = limitBuyOrderApiResponse,
                price = (limitBuyOrderApiResponse.order as CreateOrderApiRequest.Limit).price,
                amount = AssetAmount(baseSymbol, "0.012345"),
                fee = AssetAmount(quoteSymbol, "0.00012332655"),
                settlementStatus = SettlementStatus.Pending,
            )
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Partial, msg.order.status)
                assertEquals(1, msg.order.executions.size)
                assertAmount(AssetAmount(baseSymbol, "0.012345"), msg.order.executions[0].amount)
                assertAmount(AssetAmount(quoteSymbol, "0.999"), msg.order.executions[0].price)
                assertEquals(ExecutionRole.Maker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = BigDecimal("0.35"), available = BigDecimal("0.362345")),
                    ExpectedBalance(quoteSymbol, total = BigDecimal("0.649599"), available = BigDecimal("0.63714301845")),
                ),
            )
        }

        val trade2 = getTradesForOrders(listOf(marketSellOrderApiResponse.orderId)).first().also {
            assertAmount(AssetAmount(baseSymbol, "0.012345"), it.amount)
            assertAmount(AssetAmount(quoteSymbol, "0.999"), it.price)
        }

        waitForSettlementToFinish(listOf(trade2.id.value))

        val baseQuantity2 = AssetAmount(baseSymbol, trade2.amount)
        val notional2 = trade2.price.ofAsset(quoteSymbol) * baseQuantity2.amount
        val makerFee2 = notional2 * BigDecimal("0.01")
        val takerFee2 = notional2 * BigDecimal("0.02")

        assertBalances(
            listOf(
                ExpectedBalance(makerStartingBaseBalance - baseQuantity + baseQuantity2),
                ExpectedBalance(makerStartingQuoteBalance + (notional - makerFee) - (notional2 + makerFee2)),
            ),
            makerApiClient.getBalances().balances,
        )
        assertBalances(
            listOf(
                ExpectedBalance(takerStartingBaseBalance + baseQuantity - baseQuantity2),
                ExpectedBalance(takerStartingQuoteBalance - (notional + takerFee) + (notional2 - takerFee2)),
            ),
            takerApiClient.getBalances().balances,
        )

        takerWsClient.apply {
            assertLimitsMessageReceived(market, base = BigDecimal("0.037655"), quote = BigDecimal("0.4609840019"))
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(takerStartingBaseBalance + baseQuantity - baseQuantity2),
                    ExpectedBalance(takerStartingQuoteBalance - (notional + takerFee) + (notional2 - takerFee2)),
                ),
            )
            assertTradeUpdatedMessageReceived { msg ->
                assertEquals(marketSellOrderApiResponse.orderId, msg.trade.orderId)
                assertEquals(SettlementStatus.Completed, msg.trade.settlementStatus)
            }
        }

        makerWsClient.apply {
            assertLimitsMessageReceived(market, base = BigDecimal("0.192345"), quote = BigDecimal("0.52959567345"))
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(makerStartingBaseBalance - baseQuantity + baseQuantity2),
                    ExpectedBalance(makerStartingQuoteBalance + (notional - makerFee) - (notional2 + makerFee2)),
                ),
            )
            assertTradeUpdatedMessageReceived { msg ->
                assertEquals(limitBuyOrderApiResponse.orderId, msg.trade.orderId)
                assertEquals(SettlementStatus.Completed, msg.trade.settlementStatus)
            }
        }

        // verify that fees have settled correctly on chain
        assertEquals(
            makerFee + takerFee + makerFee2 + takerFee2,
            exchangeContractManager.getFeeBalance(quoteSymbol) - initialFeeAccountBalance,
        )

        takerApiClient.cancelOpenOrders()
        makerApiClient.cancelOpenOrders()

        makerWsClient.close()
        takerWsClient.close()

        Thread.sleep(100)
    }

    @Test
    fun `cross chain order batches`() {
        val market = btcbtc2Market
        val baseSymbol = btc
        val quoteSymbol = btc2

        val (makerApiClient, makerWallet, makerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "0.5"),
                AssetAmount(quoteSymbol, "2"),
            ),
            deposits = listOf(
                AssetAmount(baseSymbol, "0.2"),
                AssetAmount(quoteSymbol, "1.8"),
            ),
            subscribeToOrderBook = false,
            subscribeToOrderPrices = false,
        )

        val (takerApiClient, takerWallet, takerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "0.5"),
                AssetAmount(quoteSymbol, "2"),
            ),
            deposits = listOf(
                AssetAmount(quoteSymbol, "1.8"),
            ),
            subscribeToOrderBook = false,
            subscribeToOrderPrices = false,
        )

        // starting onchain balances
        val makerStartingBaseBalance = makerWallet.getExchangeBalance(baseSymbol)
        val makerStartingQuoteBalance = makerWallet.getExchangeBalance(quoteSymbol)
        val takerStartingBaseBalance = takerWallet.getExchangeBalance(baseSymbol)
        val takerStartingQuoteBalance = takerWallet.getExchangeBalance(quoteSymbol)

        // place 3 orders
        val createBatchLimitOrders = makerApiClient.batchOrders(
            BatchOrdersApiRequest(
                marketId = market.id,
                createOrders = listOf("0.00001", "0.00002", "0.0003").map {
                    makerWallet.signOrder(
                        CreateOrderApiRequest.Limit(
                            nonce = generateOrderNonce(),
                            marketId = market.id,
                            side = OrderSide.Sell,
                            amount = AssetAmount(baseSymbol, it).inFundamentalUnits,
                            price = BigDecimal("1.001"),
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    )
                },
                updateOrders = listOf(),
                cancelOrders = listOf(),
            ),
        )

        assertEquals(3, createBatchLimitOrders.createdOrders.count())
        repeat(3) { makerWsClient.assertOrderCreatedMessageReceived() }
        makerWsClient.assertLimitsMessageReceived(market, base = BigDecimal("0.19967"), quote = BigDecimal("1.8"))

        val batchOrderResponse = makerApiClient.batchOrders(
            BatchOrdersApiRequest(
                marketId = market.id,
                createOrders = listOf("0.0004", "0.0005", "0.0006").map {
                    makerWallet.signOrder(
                        CreateOrderApiRequest.Limit(
                            nonce = generateOrderNonce(),
                            marketId = market.id,
                            side = OrderSide.Sell,
                            amount = AssetAmount(baseSymbol, it).inFundamentalUnits,
                            price = BigDecimal("1.001"),
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    )
                },
                updateOrders = listOf(
                    makerWallet.signOrder(
                        UpdateOrderApiRequest(
                            orderId = createBatchLimitOrders.createdOrders[0].orderId,
                            amount = AssetAmount(baseSymbol, "0.0001").inFundamentalUnits,
                            marketId = createBatchLimitOrders.createdOrders[0].order.marketId,
                            side = createBatchLimitOrders.createdOrders[0].order.side,
                            price = BigDecimal("1.002"),
                            nonce = generateOrderNonce(),
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    ),
                    makerWallet.signOrder(
                        UpdateOrderApiRequest(
                            orderId = createBatchLimitOrders.createdOrders[1].orderId,
                            amount = AssetAmount(baseSymbol, "0.0002").inFundamentalUnits,
                            marketId = createBatchLimitOrders.createdOrders[1].order.marketId,
                            side = createBatchLimitOrders.createdOrders[2].order.side,
                            price = BigDecimal("1.002"),
                            nonce = generateOrderNonce(),
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    ),
                    makerWallet.signOrder(
                        UpdateOrderApiRequest(
                            orderId = OrderId.generate(),
                            amount = AssetAmount(baseSymbol, "0.0002").inFundamentalUnits,
                            marketId = createBatchLimitOrders.createdOrders[1].order.marketId,
                            side = createBatchLimitOrders.createdOrders[2].order.side,
                            price = BigDecimal("1.002"),
                            nonce = generateOrderNonce(),
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    ),
                ),
                cancelOrders = listOf(
                    createBatchLimitOrders.createdOrders[2].toCancelOrderRequest(makerWallet),
                    makerWallet.signCancelOrder(
                        CancelOrderApiRequest(
                            orderId = OrderId.generate(),
                            marketId = market.id,
                            amount = BigInteger.ZERO,
                            side = OrderSide.Buy,
                            nonce = generateOrderNonce(),
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(3, batchOrderResponse.createdOrders.count { it.requestStatus == RequestStatus.Accepted })
        assertEquals(2, batchOrderResponse.updatedOrders.count { it.requestStatus == RequestStatus.Accepted })
        assertEquals(1, batchOrderResponse.updatedOrders.count { it.requestStatus == RequestStatus.Rejected })
        assertEquals(1, batchOrderResponse.canceledOrders.count { it.requestStatus == RequestStatus.Accepted })
        assertEquals(1, batchOrderResponse.canceledOrders.count { it.requestStatus == RequestStatus.Rejected })

        repeat(3) { makerWsClient.assertOrderCreatedMessageReceived() }
        makerWsClient.assertLimitsMessageReceived(market, base = BigDecimal("0.1979"), quote = BigDecimal("1.80"))
        repeat(3) { makerWsClient.assertOrderUpdatedMessageReceived() }
        makerWsClient.assertLimitsMessageReceived(market, base = BigDecimal("0.1982"), quote = BigDecimal("1.8"))

        assertEquals(5, makerApiClient.listOrders().orders.count { it.status == OrderStatus.Open })

        // total BTC available is 0.0001 + 0.0002 + 0.0004 + 0.0005 + 0.0006 = 0.0018
        val takerOrderAmount = AssetAmount(baseSymbol, "0.0018")
        // create a market orders that should match against the 5 limit orders
        takerApiClient.createMarketOrder(
            market,
            OrderSide.Buy,
            amount = takerOrderAmount.amount,
            takerWallet,
        )

        takerWsClient.apply {
            assertOrderCreatedMessageReceived()
            repeat(5) { assertTradeCreatedMessageReceived() }
            assertOrderUpdatedMessageReceived()
            assertBalancesMessageReceived()
            assertLimitsMessageReceived(market, base = BigDecimal("0.0018"), quote = BigDecimal("1.798161858"))
        }

        makerWsClient.apply {
            repeat(5) { assertTradeCreatedMessageReceived() }
            repeat(5) { assertOrderUpdatedMessageReceived() }
            assertBalancesMessageReceived()
            assertLimitsMessageReceived(market, base = BigDecimal("0.1982"), quote = BigDecimal("1.801784079"))
        }

        // should be 8 filled orders
        val takerOrders = takerApiClient.listOrders().orders
        assertEquals(1, takerOrders.count { it.status == OrderStatus.Filled })
        assertEquals(5, makerApiClient.listOrders().orders.count { it.status == OrderStatus.Filled })

        // now verify the trades

        val expectedAmounts = listOf("0.0001", "0.0002", "0.0004", "0.0005", "0.0006").map { AssetAmount(baseSymbol, it) }.toSet()
        val trades = getTradesForOrders(takerOrders.map { it.id })
        val prices = trades.map { it.price }.toSet()

        assertEquals(5, trades.size)
        assertEquals(expectedAmounts, trades.map { AssetAmount(baseSymbol, it.amount) }.toSet())
        assertEquals(prices.size, 2)

        waitForSettlementToFinish(trades.map { it.id.value })

        val notionals = trades.map { it.price.ofAsset(quoteSymbol) * AssetAmount(baseSymbol, it.amount).amount }
        val notional = notionals.sum()
        val makerFees = notionals.map { it * BigDecimal("0.01") }.sum()
        val takerFees = notionals.map { it * BigDecimal("0.02") }.sum()

        assertBalances(
            listOf(
                ExpectedBalance(makerStartingBaseBalance - takerOrderAmount),
                ExpectedBalance(makerStartingQuoteBalance + notional - makerFees),
            ),
            makerApiClient.getBalances().balances,
        )
        assertBalances(
            listOf(
                ExpectedBalance(takerStartingBaseBalance + takerOrderAmount),
                ExpectedBalance(takerStartingQuoteBalance - notional - takerFees),
            ),
            takerApiClient.getBalances().balances,
        )

        takerApiClient.cancelOpenOrders()
        makerApiClient.cancelOpenOrders()

        makerWsClient.close()
        takerWsClient.close()

        Thread.sleep(100)
    }

    @Test
    fun `cross chain settlement failure`() {
        val market = btcbtc2Market
        val baseSymbol = btc
        val quoteSymbol = btc2

        val (takerApiClient, takerWallet, takerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "0.5"),
                AssetAmount(quoteSymbol, "0.6"),
            ),
            deposits = listOf(
                AssetAmount(baseSymbol, "0.3"),
                AssetAmount(quoteSymbol, "0.4"),
            ),
            subscribeToOrderBook = false,
            subscribeToOrderPrices = false,
        )

        val (makerApiClient, makerWallet, makerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "0.5"),
                AssetAmount(quoteSymbol, "0.7"),
            ),
            deposits = listOf(
                AssetAmount(quoteSymbol, "0.55"),
            ),
            subscribeToOrderBook = false,
            subscribeToOrderPrices = false,
        )

        // starting onchain balances
        val makerStartingBaseBalance = makerWallet.getExchangeBalance(baseSymbol)
        val makerStartingQuoteBalance = makerWallet.getExchangeBalance(quoteSymbol)
        val takerStartingBaseBalance = takerWallet.getExchangeBalance(baseSymbol)
        val takerStartingQuoteBalance = takerWallet.getExchangeBalance(quoteSymbol)
        takerWallet.switchChain(chainIdBySymbol.getValue(baseSymbol.name))

        val baseWithdrawalAmount = AssetAmount(baseSymbol, "0.12")

        val pendingBaseWithdrawal = takerApiClient.createWithdrawal(takerWallet.signWithdraw(baseSymbol.name, baseWithdrawalAmount.inFundamentalUnits)).withdrawal
        assertEquals(WithdrawalStatus.Pending, pendingBaseWithdrawal.status)

        takerWsClient.apply {
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = takerStartingBaseBalance, available = takerStartingBaseBalance - baseWithdrawalAmount),
                    ExpectedBalance(quoteSymbol, total = takerStartingQuoteBalance, available = takerStartingQuoteBalance),
                ),
            )
            assertLimitsMessageReceived(market, base = takerStartingBaseBalance - baseWithdrawalAmount, quote = takerStartingQuoteBalance)
        }

        waitForFinalizedWithdrawal(pendingBaseWithdrawal.id)

        takerWsClient.apply {
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = takerStartingBaseBalance - baseWithdrawalAmount, available = takerStartingBaseBalance - baseWithdrawalAmount),
                    ExpectedBalance(quoteSymbol, total = takerStartingQuoteBalance, available = takerStartingQuoteBalance),
                ),
            )
        }

        // hack to resubmit the same transaction again bypassing the sequencer. This way taker will not have a
        // sufficient on chain balance for the order so settlement will fail.
        transaction {
            WithdrawalEntity[pendingBaseWithdrawal.id].status = WithdrawalStatus.Sequenced
        }

        waitForFinalizedWithdrawal(pendingBaseWithdrawal.id)

        takerWsClient.apply {
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = takerStartingBaseBalance - baseWithdrawalAmount * BigDecimal(2), available = takerStartingBaseBalance - baseWithdrawalAmount),
                    ExpectedBalance(quoteSymbol, total = takerStartingQuoteBalance, available = takerStartingQuoteBalance),
                ),
            )
        }

        // place a limit order
        val limitBuyOrderApiResponse = makerApiClient.createLimitOrder(
            market,
            OrderSide.Buy,
            amount = BigDecimal("0.08"),
            price = BigDecimal("0.999"),
            makerWallet,
        )
        makerWsClient.apply {
            assertLimitOrderCreatedMessageReceived(limitBuyOrderApiResponse)
            assertLimitsMessageReceived(market, base = BigDecimal("0"), quote = BigDecimal("0.47008"))
        }

        // place a sell order
        val marketBuyOrderApiResponse = takerApiClient.createMarketOrder(
            market,
            OrderSide.Sell,
            BigDecimal("0.08"),
            takerWallet,
        )

        takerWsClient.apply {
            assertMarketOrderCreatedMessageReceived(marketBuyOrderApiResponse)
            assertTradeCreatedMessageReceived(
                order = marketBuyOrderApiResponse,
                price = BigDecimal("0.999"),
                amount = AssetAmount(baseSymbol, marketBuyOrderApiResponse.order.amount),
                fee = AssetAmount(quoteSymbol, "0.0015984"),
                settlementStatus = SettlementStatus.Pending,
            )
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Filled, msg.order.status)
                assertEquals(1, msg.order.executions.size)
                assertAmount(AssetAmount(baseSymbol, "0.08"), msg.order.executions[0].amount)
                assertAmount(AssetAmount(quoteSymbol, "0.999"), msg.order.executions[0].price)
                assertEquals(ExecutionRole.Taker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = BigDecimal("0.06"), available = BigDecimal("0.1")),
                    ExpectedBalance(quoteSymbol, total = BigDecimal("0.4"), available = BigDecimal("0.4783216")),
                ),
            )
        }

        makerWsClient.apply {
            assertTradeCreatedMessageReceived(
                order = limitBuyOrderApiResponse,
                price = BigDecimal("0.999"),
                amount = AssetAmount(baseSymbol, "0.08"),
                fee = AssetAmount(quoteSymbol, "0.0007992"),
                settlementStatus = SettlementStatus.Pending,
            )
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Filled, msg.order.status)
                assertEquals(1, msg.order.executions.size)
                assertAmount(AssetAmount(baseSymbol, "0.08"), msg.order.executions[0].amount)
                assertAmount(AssetAmount(quoteSymbol, "0.999"), msg.order.executions[0].price)
                assertEquals(ExecutionRole.Maker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = BigDecimal("0"), available = BigDecimal("0.08")),
                    ExpectedBalance(quoteSymbol, total = BigDecimal("0.55"), available = BigDecimal("0.4692808")),
                ),
            )
        }

        val trade = getTradesForOrders(listOf(marketBuyOrderApiResponse.orderId)).first()

        takerWsClient.apply {
            assertLimitsMessageReceived(market, base = BigDecimal("0.1"), quote = BigDecimal("0.4783216"))
        }

        makerWsClient.apply {
            assertLimitsMessageReceived(market, base = BigDecimal("0.08"), quote = BigDecimal("0.4692808"))
        }

        assertAmount(AssetAmount(baseSymbol, "0.08"), trade.amount)
        assertAmount(AssetAmount(quoteSymbol, "0.999"), trade.price)

        waitForSettlementToFinish(listOf(trade.id.value), SettlementStatus.Failed)

        takerWsClient.apply {
            assertMessagesReceived(3) { messages ->
                assertContainsTradeUpdatedMessage(messages) { msg ->
                    assertEquals(marketBuyOrderApiResponse.orderId, msg.trade.orderId)
                    assertEquals(SettlementStatus.Failed, msg.trade.settlementStatus)
                    assertEquals("Insufficient Balance", msg.trade.error)
                }
                assertContainsBalancesMessage(
                    messages,
                    listOf(
                        ExpectedBalance(baseSymbol, total = AssetAmount(baseSymbol, "0.06"), available = takerStartingBaseBalance - baseWithdrawalAmount),
                        ExpectedBalance(takerStartingQuoteBalance),
                    ),
                )
                assertContainsLimitsMessage(messages, market, base = takerStartingBaseBalance - baseWithdrawalAmount, quote = takerStartingQuoteBalance)
            }
        }

        makerWsClient.apply {
            assertMessagesReceived(3) { messages ->
                assertContainsTradeUpdatedMessage(messages) { msg ->
                    assertEquals(limitBuyOrderApiResponse.orderId, msg.trade.orderId)
                    assertEquals(SettlementStatus.Failed, msg.trade.settlementStatus)
                    assertEquals("Insufficient Balance", msg.trade.error)
                }
                assertContainsBalancesMessage(
                    messages,
                    listOf(
                        ExpectedBalance(makerStartingBaseBalance),
                        ExpectedBalance(makerStartingQuoteBalance),
                    ),
                )
                assertContainsLimitsMessage(messages, market, base = makerStartingBaseBalance, quote = makerStartingQuoteBalance)
            }
        }

        makerWsClient.close()

        waitForSettlementBatchToFinish()
    }
    private fun setupTrader(
        marketId: MarketId,
        airdrops: List<AssetAmount>,
        deposits: List<AssetAmount>,
        subscribeToOrderBook: Boolean = true,
        subscribeToOrderPrices: Boolean = true,
    ): Triple<TestApiClient, Wallet, WsClient> {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)

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

        airdrops.forEach {
            val chainId = chainIdBySymbol.getValue(it.symbol.name)
            if (chainId != wallet.currentChainId) {
                wallet.switchChain(chainId)
            }
            if (it.symbol.contractAddress == null) {
                Faucet.fund(wallet.address, it.inFundamentalUnits, chainId)
            } else {
                wallet.mintERC20(it)
            }
        }

        deposits.forEach {
            val chainId = chainIdBySymbol.getValue(it.symbol.name)
            if (chainId != wallet.currentChainId) {
                wallet.switchChain(chainId)
            }
            wallet.deposit(it)
            wsClient.assertBalancesMessageReceived()
            wsClient.assertLimitsMessageReceived(marketId)
        }

        assertBalances(deposits.map { ExpectedBalance(it) }, apiClient.getBalances().balances)

        return Triple(apiClient, wallet, wsClient)
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

    private fun waitForSettlementBatchToFinish() {
        await
            .pollInSameThread()
            .pollDelay(Duration.ofMillis(100))
            .pollInterval(Duration.ofMillis(100))
            .atMost(Duration.ofMillis(20000L))
            .until {
                transaction {
                    SettlementBatchEntity.count(SettlementBatchTable.status.neq(SettlementBatchStatus.Completed)) == 0L
                }
            }
    }

    private fun getTradesForOrders(orderIds: List<OrderId>): List<TradeEntity> {
        return transaction {
            OrderExecutionEntity.findForOrders(orderIds).map { it.trade }
        }
    }
}
