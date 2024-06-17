package co.chainring.integrationtests.api

import co.chainring.apps.api.model.BatchOrdersApiRequest
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.OrderAmount
import co.chainring.apps.api.model.RequestStatus
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.Percentage
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.OrderStatus
import co.chainring.core.utils.generateOrderNonce
import co.chainring.core.utils.toFundamentalUnits
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.testutils.OrderBaseTest
import co.chainring.integrationtests.utils.AssetAmount
import co.chainring.integrationtests.utils.ExpectedBalance
import co.chainring.integrationtests.utils.assertBalances
import co.chainring.integrationtests.utils.assertBalancesMessageReceived
import co.chainring.integrationtests.utils.assertLimitsMessageReceived
import co.chainring.integrationtests.utils.assertOrderCreatedMessageReceived
import co.chainring.integrationtests.utils.assertOrderUpdatedMessageReceived
import co.chainring.integrationtests.utils.assertTradeCreatedMessageReceived
import co.chainring.integrationtests.utils.inFundamentalUnits
import co.chainring.integrationtests.utils.ofAsset
import co.chainring.integrationtests.utils.subscribeToOrderBook
import co.chainring.integrationtests.utils.sum
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import kotlin.test.Test

@ExtendWith(AppUnderTestRunner::class)
class OrderPercentageSwapTest : OrderBaseTest() {

    @Test
    fun `order swap with percentages - market sell`() {
        val (market, baseSymbol, quoteSymbol) = Triple(btcUsdcMarket, btc, usdc)

        val (makerApiClient, makerWallet, makerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "0.1"),
                AssetAmount(quoteSymbol, "20000"),
            ),
            deposits = listOf(
                AssetAmount(quoteSymbol, "20000"),
            ),
            subscribeToOrderBook = false,
            subscribeToOrderPrices = false,
        )

        val (takerApiClient, takerWallet, takerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "0.5"),
            ),
            deposits = listOf(
                AssetAmount(baseSymbol, "0.1"),
            ),
            subscribeToOrderBook = false,
            subscribeToOrderPrices = false,
        )

        // starting onchain balances
        val makerStartingBaseBalance = makerWallet.getExchangeBalance(baseSymbol)
        val makerStartingQuoteBalance = makerWallet.getExchangeBalance(quoteSymbol)
        val takerStartingBaseBalance = takerWallet.getExchangeBalance(baseSymbol)
        val takerStartingQuoteBalance = takerWallet.getExchangeBalance(quoteSymbol)

        // place 4 orders
        val createBatchLimitOrders = makerApiClient.batchOrders(
            BatchOrdersApiRequest(
                marketId = market.id,
                createOrders = listOf("0.02", "0.03", "0.04", "0.05").map {
                    makerWallet.signOrder(
                        CreateOrderApiRequest.Limit(
                            nonce = generateOrderNonce(),
                            marketId = market.id,
                            side = OrderSide.Buy,
                            amount = OrderAmount.Fixed(AssetAmount(baseSymbol, it).inFundamentalUnits),
                            price = BigDecimal("68500.000"),
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    )
                },
                updateOrders = listOf(),
                cancelOrders = listOf(),
            ),
        )

        assertEquals(4, createBatchLimitOrders.createdOrders.count { it.requestStatus == RequestStatus.Accepted })

        repeat(4) { makerWsClient.assertOrderCreatedMessageReceived() }
        makerWsClient.assertLimitsMessageReceived(market, base = BigDecimal.ZERO, quote = BigDecimal("10410"))

        assertEquals(4, makerApiClient.listOrders(listOf(OrderStatus.Open), null).orders.size)

        // total BTC available is 0.02 + 0.03 + 0.04 + 0.05 = 0.14
        // taker has 0.1 BTC create a market orders that should match against the 4 limit orders
        takerApiClient.createMarketOrder(
            market,
            OrderSide.Sell,
            amount = null,
            takerWallet,
            percentage = Percentage(100),
        )

        takerWsClient.apply {
            assertOrderCreatedMessageReceived()
            repeat(4) { assertTradeCreatedMessageReceived() }
            assertOrderUpdatedMessageReceived {
                assertEquals(BigDecimal("0.1").toFundamentalUnits(market.baseDecimals), it.order.amount)
            }
            assertBalancesMessageReceived {
                listOf(
                    ExpectedBalance(baseSymbol, total = BigDecimal("0.1"), available = BigDecimal("0")),
                    ExpectedBalance(quoteSymbol, total = BigDecimal("0"), available = BigDecimal("6713")),
                )
            }
            assertLimitsMessageReceived(market, base = BigDecimal.ZERO, quote = BigDecimal("6713"))
        }

        makerWsClient.apply {
            repeat(4) { assertTradeCreatedMessageReceived() }
            repeat(4) { assertOrderUpdatedMessageReceived() }
            assertBalancesMessageReceived()
            assertLimitsMessageReceived(market, base = BigDecimal("0.1"), quote = BigDecimal("10341.50"))
        }

        val takerOrders = takerApiClient.listOrders(emptyList(), market.id).orders
        assertEquals(1, takerOrders.count { it.status == OrderStatus.Filled })
        // should be 4 filled maker orders
        assertEquals(4, makerApiClient.listOrders(listOf(OrderStatus.Filled, OrderStatus.Partial), market.id).orders.size)

        // now verify the trades

        val expectedAmounts = listOf("0.02", "0.03", "0.04", "0.01").map { AssetAmount(baseSymbol, it) }.toSet()
        val trades = getTradesForOrders(takerOrders.map { it.id })

        assertEquals(4, trades.size)
        assertEquals(expectedAmounts, trades.map { AssetAmount(baseSymbol, it.amount) }.toSet())

        waitForSettlementToFinish(trades.map { it.id.value })

        val notionals = trades.map { it.price.ofAsset(quoteSymbol) * AssetAmount(baseSymbol, it.amount).amount }
        val notional = notionals.sum()
        val makerFees = notionals.map { it * BigDecimal("0.01") }.sum()
        val takerFees = notionals.map { it * BigDecimal("0.02") }.sum()

        assertBalances(
            listOf(
                ExpectedBalance(makerStartingBaseBalance + takerStartingBaseBalance),
                ExpectedBalance(makerStartingQuoteBalance - notional - makerFees),
            ),
            makerApiClient.getBalances().balances,
        )
        assertBalances(
            listOf(
                ExpectedBalance(takerStartingBaseBalance - takerStartingBaseBalance),
                ExpectedBalance(takerStartingQuoteBalance + notional - takerFees),
            ),
            takerApiClient.getBalances().balances,
        )

        takerApiClient.cancelOpenOrders()
        makerApiClient.cancelOpenOrders()

        makerWsClient.close()
        takerWsClient.close()
    }

    @Test
    fun `order swap with percentages - market buy`() {
        val (market, baseSymbol, quoteSymbol) = Triple(btcUsdcMarket, btc, usdc)

        val (makerApiClient, makerWallet, makerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "0.5"),
            ),
            deposits = listOf(
                AssetAmount(baseSymbol, "0.2"),
            ),
            subscribeToOrderBook = false,
            subscribeToOrderPrices = false,
        )

        val (takerApiClient, takerWallet, takerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "0.1"),
                AssetAmount(quoteSymbol, "7000"),
            ),
            deposits = listOf(
                AssetAmount(quoteSymbol, "6987"),
            ),
            subscribeToOrderBook = false,
            subscribeToOrderPrices = false,
        )

        // starting onchain balances
        val makerStartingBaseBalance = makerWallet.getExchangeBalance(baseSymbol)
        val makerStartingQuoteBalance = makerWallet.getExchangeBalance(quoteSymbol)
        val takerStartingBaseBalance = takerWallet.getExchangeBalance(baseSymbol)
        val takerStartingQuoteBalance = takerWallet.getExchangeBalance(quoteSymbol)

        // place 4 orders
        val createBatchLimitOrders = makerApiClient.batchOrders(
            BatchOrdersApiRequest(
                marketId = market.id,
                createOrders = listOf("0.02", "0.03", "0.04", "0.05").map {
                    makerWallet.signOrder(
                        CreateOrderApiRequest.Limit(
                            nonce = generateOrderNonce(),
                            marketId = market.id,
                            side = OrderSide.Sell,
                            amount = OrderAmount.Fixed(AssetAmount(baseSymbol, it).inFundamentalUnits),
                            price = BigDecimal("68500.000"),
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    )
                },
                updateOrders = listOf(),
                cancelOrders = listOf(),
            ),
        )

        assertEquals(4, createBatchLimitOrders.createdOrders.count { it.requestStatus == RequestStatus.Accepted })

        repeat(4) { makerWsClient.assertOrderCreatedMessageReceived() }
        makerWsClient.assertLimitsMessageReceived(market, base = BigDecimal("0.06"), quote = BigDecimal("0"))

        assertEquals(4, makerApiClient.listOrders(listOf(OrderStatus.Open), null).orders.size)

        // total BTC available is 0.02 + 0.03 + 0.04 + 0.05 = 0.14
        // val takerOrderAmount = AssetAmount(baseSymbol, "0.0018")
        // create a market orders that should match against the 5 limit orders
        takerApiClient.createMarketOrder(
            market,
            OrderSide.Buy,
            amount = null,
            takerWallet,
            percentage = Percentage(100),
        )

        takerWsClient.apply {
            assertOrderCreatedMessageReceived()
            repeat(4) { assertTradeCreatedMessageReceived() }
            assertOrderUpdatedMessageReceived()
            assertBalancesMessageReceived()
            assertLimitsMessageReceived(market, base = BigDecimal("0.1"), quote = BigDecimal("0"))
        }

        makerWsClient.apply {
            repeat(4) { assertTradeCreatedMessageReceived() }
            repeat(4) { assertOrderUpdatedMessageReceived() }
            assertBalancesMessageReceived()
            assertLimitsMessageReceived(market, base = BigDecimal("0.06"), quote = BigDecimal("6781.5"))
        }

        val takerOrders = takerApiClient.listOrders(emptyList(), market.id).orders
        assertEquals(1, takerOrders.count { it.status == OrderStatus.Filled })
        // should be 5 filled maker orders
        assertEquals(4, makerApiClient.listOrders(listOf(OrderStatus.Filled, OrderStatus.Partial), market.id).orders.size)

        // now verify the trades

        val expectedAmounts = listOf("0.02", "0.03", "0.04", "0.01").map { AssetAmount(baseSymbol, it) }.toSet()
        val trades = getTradesForOrders(takerOrders.map { it.id })

        assertEquals(4, trades.size)
        assertEquals(expectedAmounts, trades.map { AssetAmount(baseSymbol, it.amount) }.toSet())

        waitForSettlementToFinish(trades.map { it.id.value })

        val notionals = trades.map { it.price.ofAsset(quoteSymbol) * AssetAmount(baseSymbol, it.amount).amount }
        val notional = notionals.sum()
        val takerOrderAmount = AssetAmount(baseSymbol, "0.1")
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
}
