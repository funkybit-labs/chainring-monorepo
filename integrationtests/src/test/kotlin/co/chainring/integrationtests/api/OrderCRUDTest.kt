package co.chainring.integrationtests.api

import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.CancelOrderApiRequest
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.MarketLimits
import co.chainring.apps.api.model.OrderAmount
import co.chainring.apps.api.model.ReasonCode
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.MarketEntity
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.OrderStatus
import co.chainring.core.model.db.SymbolEntity
import co.chainring.core.utils.generateOrderNonce
import co.chainring.core.utils.toFundamentalUnits
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.testutils.OrderBaseTest
import co.chainring.integrationtests.testutils.waitForBalance
import co.chainring.integrationtests.utils.AssetAmount
import co.chainring.integrationtests.utils.ExpectedBalance
import co.chainring.integrationtests.utils.Faucet
import co.chainring.integrationtests.utils.TestApiClient
import co.chainring.integrationtests.utils.Wallet
import co.chainring.integrationtests.utils.assertBalancesMessageReceived
import co.chainring.integrationtests.utils.assertError
import co.chainring.integrationtests.utils.assertLimitOrderCreatedMessageReceived
import co.chainring.integrationtests.utils.assertLimitsMessageReceived
import co.chainring.integrationtests.utils.assertOrderCreatedMessageReceived
import co.chainring.integrationtests.utils.assertOrderUpdatedMessageReceived
import co.chainring.integrationtests.utils.assertOrdersMessageReceived
import co.chainring.integrationtests.utils.blocking
import co.chainring.integrationtests.utils.inFundamentalUnits
import co.chainring.integrationtests.utils.subscribeToBalances
import co.chainring.integrationtests.utils.subscribeToLimits
import co.chainring.integrationtests.utils.subscribeToOrders
import co.chainring.integrationtests.utils.verifyApiReturnsSameLimits
import org.http4k.client.WebsocketClient
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.Test

@ExtendWith(AppUnderTestRunner::class)
class OrderCRUDTest : OrderBaseTest() {
    @Test
    fun `CRUD order`() {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)

        var wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToOrders()
        val initialOrdersOverWs = wsClient.assertOrdersMessageReceived().orders

        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        wsClient.subscribeToLimits()
        wsClient
            .assertLimitsMessageReceived(
                listOf(
                    MarketLimits(btcbtc2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(btcEthMarket.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(btcEth2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(btcUsdcMarket.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(btc2Eth2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(btc2Usdc2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(usdcDaiMarket.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(usdc2Dai2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                ),
            )
            .also { wsMessage -> verifyApiReturnsSameLimits(apiClient, wsMessage) }

        Faucet.fundAndMine(wallet.address)

        val daiAmountToDeposit = AssetAmount(dai, "14")
        wallet.mintERC20AndMine(daiAmountToDeposit)
        wallet.depositAndMine(daiAmountToDeposit)
        waitForBalance(apiClient, wsClient, listOf(ExpectedBalance(daiAmountToDeposit)))

        wsClient
            .assertLimitsMessageReceived(
                listOf(
                    MarketLimits(btcbtc2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(btcEthMarket.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(btcEth2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(btcUsdcMarket.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(btc2Eth2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(btc2Usdc2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(usdcDaiMarket.id, base = BigInteger.ZERO, quote = daiAmountToDeposit.inFundamentalUnits),
                    MarketLimits(usdc2Dai2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                ),
            )
            .also { wsMessage -> verifyApiReturnsSameLimits(apiClient, wsMessage) }

        val createLimitOrderResponse = apiClient.createLimitOrder(
            usdcDaiMarket,
            OrderSide.Buy,
            amount = BigDecimal("1"),
            price = BigDecimal("2"),
            wallet,
        )

        // client is notified over websocket
        wsClient.assertLimitOrderCreatedMessageReceived(createLimitOrderResponse)
        wsClient
            .assertLimitsMessageReceived(
                listOf(
                    MarketLimits(btcbtc2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(btcEthMarket.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(btcEth2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(btcUsdcMarket.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(btc2Eth2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(btc2Usdc2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(usdcDaiMarket.id, base = BigInteger.ZERO, quote = AssetAmount(dai, "11.98").inFundamentalUnits),
                    MarketLimits(usdc2Dai2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                ),
            )
            .also { wsMessage -> verifyApiReturnsSameLimits(apiClient, wsMessage) }
        wsClient.close()

        // check that order is included in the orders list sent via websocket
        wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToOrders()
        assertEquals(
            listOf(createLimitOrderResponse.orderId) + initialOrdersOverWs.map { it.id },
            wsClient.assertOrdersMessageReceived().orders.map { it.id },
        )

        wsClient.subscribeToLimits()
        wsClient.assertLimitsMessageReceived()

        // cancel order is idempotent
        apiClient.cancelOrder(createLimitOrderResponse, wallet)
        wsClient.assertOrderUpdatedMessageReceived { msg ->
            assertEquals(createLimitOrderResponse.orderId, msg.order.id)
            assertEquals(OrderStatus.Cancelled, msg.order.status)
        }
        wsClient
            .assertLimitsMessageReceived(
                listOf(
                    MarketLimits(btcbtc2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(btcEthMarket.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(btcEth2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(btcUsdcMarket.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(btc2Eth2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(btc2Usdc2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(usdcDaiMarket.id, base = BigInteger.ZERO, quote = AssetAmount(dai, "14").inFundamentalUnits),
                    MarketLimits(usdc2Dai2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                ),
            )
            .also { wsMessage -> verifyApiReturnsSameLimits(apiClient, wsMessage) }

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

        Faucet.fundAndMine(wallet.address)
        val daiAmountToDeposit = AssetAmount(dai, "200")
        wallet.mintERC20AndMine(daiAmountToDeposit)
        wallet.depositAndMine(daiAmountToDeposit)
        waitForBalance(apiClient, wsClient, listOf(ExpectedBalance(daiAmountToDeposit)))

        // create an order with that does not meet the min fee requirement
        val createTooSmallOrderResponse = apiClient.createLimitOrder(
            usdcDaiMarket,
            OrderSide.Buy,
            amount = BigDecimal("0.01"),
            price = BigDecimal("2"),
            wallet,
        )
        wsClient.assertLimitOrderCreatedMessageReceived(createTooSmallOrderResponse)
        wsClient.assertOrderUpdatedMessageReceived { msg ->
            assertEquals(createTooSmallOrderResponse.orderId, msg.order.id)
            assertEquals(OrderStatus.Rejected, msg.order.status)
        }

        // operation on non-existent order
        apiClient.tryGetOrder(OrderId.generate())
            .assertError(ApiError(ReasonCode.OrderNotFound, "Requested order does not exist"))

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
        apiClient.tryCreateOrder(
            wallet.signOrder(
                CreateOrderApiRequest.Limit(
                    nonce = generateOrderNonce(),
                    marketId = usdcDaiMarket.id,
                    side = OrderSide.Buy,
                    amount = OrderAmount.Fixed(BigDecimal("1").inFundamentalUnits(usdc)),
                    price = BigDecimal("2"),
                    signature = EvmSignature.emptySignature(),
                    verifyingChainId = ChainId.empty,
                ),
            ).copy(
                signature = EvmSignature.emptySignature(),
            ),
        ).assertError(ApiError(ReasonCode.SignatureNotValid, "Invalid signature"))

        // try creating a limit order not a multiple of tick size
        apiClient.tryCreateOrder(
            wallet.signOrder(
                CreateOrderApiRequest.Limit(
                    nonce = generateOrderNonce(),
                    marketId = usdcDaiMarket.id,
                    side = OrderSide.Buy,
                    amount = OrderAmount.Fixed(BigDecimal("1").inFundamentalUnits(usdc)),
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

        // try updating and cancelling an order not created by this wallet - signature should fail
        val apiClient2 = TestApiClient()
        val wallet2 = Wallet(apiClient2)
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

        // try an order for an unknown market - first if market isn't even in DB, we just get a 500 from API
        val badMarketId = MarketId("${dai.name}/${usdc.name}")
        apiClient.tryCreateOrder(
            wallet.signOrder(
                CreateOrderApiRequest.Market(
                    nonce = generateOrderNonce(),
                    marketId = badMarketId,
                    side = OrderSide.Buy,
                    amount = OrderAmount.Fixed(BigDecimal("1").inFundamentalUnits(usdc)),
                    signature = EvmSignature.emptySignature(),
                    verifyingChainId = ChainId.empty,
                ),
            ),
        ).assertError(
            ApiError(ReasonCode.UnexpectedError, "An unexpected error has occurred. Please, contact support if this issue persists."),
        )

        // create market in DB, still isn't known to sequencer, so we should get an UnknownMarket error
        val badMarket = transaction {
            MarketEntity
                .create(
                    SymbolEntity.forName(dai.name),
                    SymbolEntity.forName(usdc.name),
                    "0.01".toBigDecimal(),
                    "2.005".toBigDecimal(),
                    "test",
                    BigDecimal("0.02").toFundamentalUnits(18),
                )
        }
        try {
            apiClient.tryCreateOrder(
                wallet.signOrder(
                    CreateOrderApiRequest.Market(
                        nonce = generateOrderNonce(),
                        marketId = badMarketId,
                        side = OrderSide.Buy,
                        amount = OrderAmount.Fixed(BigDecimal("1").inFundamentalUnits(usdc)),
                        signature = EvmSignature.emptySignature(),
                        verifyingChainId = ChainId.empty,
                    ),
                ),
            ).assertError(
                ApiError(ReasonCode.ProcessingError, "Unable to process request - UnknownMarket"),
            )
        } finally {
            transaction {
                badMarket.delete()
            }
        }
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

        wsClient.subscribeToLimits()
        wsClient.assertLimitsMessageReceived()

        Faucet.fundAndMine(wallet.address)
        val daiAmountToDeposit = AssetAmount(dai, "30")
        wallet.mintERC20AndMine(daiAmountToDeposit)
        wallet.depositAndMine(daiAmountToDeposit)
        waitForBalance(apiClient, wsClient, listOf(ExpectedBalance(daiAmountToDeposit)))
        wsClient.assertLimitsMessageReceived()

        repeat(times = 10) {
            apiClient.createLimitOrder(
                usdcDaiMarket,
                OrderSide.Buy,
                amount = BigDecimal("1"),
                price = BigDecimal("2"),
                wallet,
            )
            wsClient.assertOrderCreatedMessageReceived()
            wsClient.assertLimitsMessageReceived()
        }
        assertEquals(10, apiClient.listOrders(listOf(OrderStatus.Open, OrderStatus.Partial, OrderStatus.Filled), usdcDaiMarket.id).orders.size)

        apiClient.cancelOpenOrders()

        repeat(times = 10) {
            wsClient.assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Cancelled, msg.order.status)
            }
        }
        wsClient.assertLimitsMessageReceived()

        assertTrue(apiClient.listOrders(emptyList(), usdcDaiMarket.id).orders.all { it.status == OrderStatus.Cancelled })

        wsClient.close()
    }
}