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
import co.chainring.integrationtests.utils.ofAsset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.Test

@ExtendWith(AppUnderTestRunner::class)
class BackToBackOrderTest : OrderBaseTest() {

    @Test
    fun `back to back - market sell - swap btc for eth2`() {
        val (market, baseSymbol, bridgeSymbol) = Triple(btcbtc2Market, btc, btc2)
        val (secondMarket, _, quoteSymbol) = Triple(btc2Eth2Market, btc2, eth2)

        val (makerApiClient, makerWallet, makerWsClient) = setupTrader(
            secondMarket.id,
            airdrops = listOf(
                AssetAmount(bridgeSymbol, "1.0"),
                AssetAmount(quoteSymbol, "20"),
            ),
            deposits = listOf(
                AssetAmount(bridgeSymbol, "0.9"),
                AssetAmount(quoteSymbol, "20"),
            ),
            subscribeToOrderBook = false,
            subscribeToOrderPrices = false,
        )

        val (takerApiClient, takerWallet, takerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "0.7"),
            ),
            deposits = listOf(
                AssetAmount(baseSymbol, "0.6"),
            ),
            subscribeToOrderBook = false,
            subscribeToOrderPrices = false,
        )

        // starting onchain balances
        val makerStartingBaseBalance = makerWallet.getExchangeBalance(baseSymbol)
        val makerStartingBridgeBalance = makerWallet.getExchangeBalance(bridgeSymbol)
        val makerStartingQuoteBalance = makerWallet.getExchangeBalance(quoteSymbol)
        val takerStartingBaseBalance = takerWallet.getExchangeBalance(baseSymbol)
        val takerStartingQuoteBalance = takerWallet.getExchangeBalance(quoteSymbol)

        val limitBuyOrder1 = makerApiClient.batchOrders(
            BatchOrdersApiRequest(
                marketId = market.id,
                createOrders = listOf(
                    makerWallet.signOrder(
                        CreateOrderApiRequest.Limit(
                            nonce = generateOrderNonce(),
                            marketId = market.id,
                            side = OrderSide.Buy,
                            amount = OrderAmount.Fixed(AssetAmount(baseSymbol, BigDecimal("0.8")).inFundamentalUnits),
                            price = BigDecimal("0.95"),
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    ),
                ),
                updateOrders = listOf(),
                cancelOrders = listOf(),
            ),
        )
        val limitBuyOrder2 = makerApiClient.batchOrders(
            BatchOrdersApiRequest(
                marketId = secondMarket.id,
                createOrders = listOf(
                    makerWallet.signOrder(
                        CreateOrderApiRequest.Limit(
                            nonce = generateOrderNonce(),
                            marketId = secondMarket.id,
                            side = OrderSide.Buy,
                            amount = OrderAmount.Fixed(AssetAmount(bridgeSymbol, BigDecimal("1")).inFundamentalUnits),
                            price = BigDecimal("18.000"),
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    ),
                ),
                updateOrders = listOf(),
                cancelOrders = listOf(),
            ),
        )

        assertEquals(1, limitBuyOrder1.createdOrders.count { it.requestStatus == RequestStatus.Accepted })
        assertEquals(1, limitBuyOrder2.createdOrders.count { it.requestStatus == RequestStatus.Accepted })

        repeat(2) { makerWsClient.assertOrderCreatedMessageReceived() }
        makerWsClient.assertLimitsMessageReceived(secondMarket.id)

        assertEquals(2, makerApiClient.listOrders(listOf(OrderStatus.Open), null).orders.size)

        takerApiClient.createBackToBackMarketOrder(
            listOf(market, secondMarket),
            OrderSide.Sell,
            amount = null,
            takerWallet,
            percentage = Percentage(100),
        )

        takerWsClient.apply {
            assertOrderCreatedMessageReceived()
            assertTradeCreatedMessageReceived {
                assertEquals(btcbtc2Market.id, it.trade.marketId)
                assertEquals(OrderSide.Sell, it.trade.side)
                assertEquals(takerStartingBaseBalance.inFundamentalUnits, it.trade.amount)
                assertEquals(BigDecimal("0.95").setScale(18), it.trade.price)
                assertEquals(BigInteger.ZERO, it.trade.feeAmount)
                assertEquals(btc2.name, it.trade.feeSymbol.value)
            }
            assertTradeCreatedMessageReceived {
                assertEquals(btc2Eth2Market.id, it.trade.marketId)
                assertEquals(OrderSide.Sell, it.trade.side)
                assertEquals((BigDecimal("0.95") * BigDecimal("0.6")).toFundamentalUnits(btc2.decimals), it.trade.amount)
                assertEquals(BigDecimal("18.00").setScale(18), it.trade.price)
                assertEquals(BigDecimal("0.2052").toFundamentalUnits(eth2.decimals), it.trade.feeAmount)
                assertEquals(eth2.name, it.trade.feeSymbol.value)
            }
            assertOrderUpdatedMessageReceived {
                assertEquals(BigDecimal("0.6").toFundamentalUnits(market.baseDecimals), it.order.amount)
                assertEquals(it.order.status, OrderStatus.Filled)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = BigDecimal("0.6"), available = BigDecimal("0")),
                    ExpectedBalance(quoteSymbol, total = BigDecimal("0"), available = BigDecimal("10.0548")),
                ),
            )
            assertLimitsMessageReceived(market.id)
        }

        makerWsClient.apply {
            repeat(2) { assertTradeCreatedMessageReceived() }
            repeat(2) { assertOrderUpdatedMessageReceived() }
            assertBalancesMessageReceived()
            assertLimitsMessageReceived(secondMarket.id)
        }

        val takerOrders = takerApiClient.listOrders(emptyList(), market.id).orders
        assertEquals(1, takerOrders.count { it.status == OrderStatus.Filled })

        assertEquals(2, makerApiClient.listOrders(listOf(OrderStatus.Filled, OrderStatus.Partial)).orders.size)

        // now verify the trades
        val trades = getTradesForOrders(takerOrders.map { it.id })
        assertEquals(2, trades.size)

        waitForSettlementToFinish(trades.map { it.id.value })

        val trade1 = trades.first { it.marketGuid.value == market.id }
        val trade2 = trades.first { it.marketGuid.value == secondMarket.id }

        assertEquals(trade1.amount, takerStartingBaseBalance.inFundamentalUnits)
        assertEquals(trade1.price, BigDecimal("0.95").setScale(18))

        assertEquals(trade2.amount, (BigDecimal("0.95") * BigDecimal("0.6")).toFundamentalUnits(btc2.decimals))
        assertEquals(trade2.price, BigDecimal("18.00").setScale(18))

        val notionalTrade1 = trade1.price.ofAsset(bridgeSymbol) * AssetAmount(baseSymbol, trade1.amount).amount
        val notionalTrade2 = trade2.price.ofAsset(quoteSymbol) * AssetAmount(bridgeSymbol, trade2.amount).amount

        val makerFeeTrade1 = notionalTrade1 * BigDecimal("0.01")
        val makerFeeTrade2 = notionalTrade2 * BigDecimal("0.01")
        val takerFee = notionalTrade2 * BigDecimal("0.02")

        assertBalances(
            listOf(
                ExpectedBalance(makerStartingBaseBalance + takerStartingBaseBalance),
                ExpectedBalance(makerStartingBridgeBalance - makerFeeTrade1),
                ExpectedBalance(makerStartingQuoteBalance - notionalTrade2 - makerFeeTrade2),
            ),
            makerApiClient.getBalances().balances,
        )
        assertBalances(
            listOf(
                ExpectedBalance(takerStartingBaseBalance - takerStartingBaseBalance),
                ExpectedBalance(takerStartingQuoteBalance + notionalTrade2 - takerFee),
            ),
            takerApiClient.getBalances().balances,
        )

        takerApiClient.cancelOpenOrders()
        makerApiClient.cancelOpenOrders()

        makerWsClient.close()
        takerWsClient.close()
    }

    @Test
    fun `back to back - market buy - swap eth2 for btc`() {
        val (market, baseSymbol, bridgeSymbol) = Triple(btcbtc2Market, btc, btc2)
        val (secondMarket, _, quoteSymbol) = Triple(btc2Eth2Market, btc2, eth2)

        val (makerApiClient, makerWallet, makerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(bridgeSymbol, "1.0"),
                AssetAmount(baseSymbol, "1.0"),
            ),
            deposits = listOf(
                AssetAmount(bridgeSymbol, "0.9"),
                AssetAmount(baseSymbol, "0.9"),
            ),
            subscribeToOrderBook = false,
            subscribeToOrderPrices = false,
        )

        val (takerApiClient, takerWallet, takerWsClient) = setupTrader(
            secondMarket.id,
            airdrops = listOf(
                AssetAmount(bridgeSymbol, "0.1"),
                AssetAmount(quoteSymbol, "10"),
            ),
            deposits = listOf(
                AssetAmount(quoteSymbol, "10"),
            ),
            subscribeToOrderBook = false,
            subscribeToOrderPrices = false,
        )

        // starting onchain balances
        val makerStartingBaseBalance = makerWallet.getExchangeBalance(baseSymbol)
        val makerStartingBridgeBalance = makerWallet.getExchangeBalance(bridgeSymbol)
        val makerStartingQuoteBalance = makerWallet.getExchangeBalance(quoteSymbol)
        val takerStartingBaseBalance = takerWallet.getExchangeBalance(baseSymbol)
        val takerStartingQuoteBalance = takerWallet.getExchangeBalance(quoteSymbol)

        val bridgePrice = BigDecimal("1.05")
        val quotePrice = BigDecimal("18")
        val baseOrderAmount = AssetAmount(btc, BigDecimal("0.5"))
        val bridgeOrderAmount = AssetAmount(btc2, baseOrderAmount.amount * bridgePrice)

        val limitSellOrder1 = makerApiClient.batchOrders(
            BatchOrdersApiRequest(
                marketId = market.id,
                createOrders = listOf(
                    makerWallet.signOrder(
                        CreateOrderApiRequest.Limit(
                            nonce = generateOrderNonce(),
                            marketId = market.id,
                            side = OrderSide.Sell,
                            amount = OrderAmount.Fixed(AssetAmount(baseSymbol, BigDecimal("0.8")).inFundamentalUnits),
                            price = bridgePrice,
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    ),
                ),
                updateOrders = listOf(),
                cancelOrders = listOf(),
            ),
        )
        val limitSellOrder2 = makerApiClient.batchOrders(
            BatchOrdersApiRequest(
                marketId = secondMarket.id,
                createOrders = listOf(
                    makerWallet.signOrder(
                        CreateOrderApiRequest.Limit(
                            nonce = generateOrderNonce(),
                            marketId = secondMarket.id,
                            side = OrderSide.Sell,
                            amount = OrderAmount.Fixed(AssetAmount(bridgeSymbol, BigDecimal("0.8")).inFundamentalUnits),
                            price = quotePrice,
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    ),
                ),
                updateOrders = listOf(),
                cancelOrders = listOf(),
            ),
        )

        assertEquals(1, limitSellOrder1.createdOrders.count { it.requestStatus == RequestStatus.Accepted })
        assertEquals(1, limitSellOrder2.createdOrders.count { it.requestStatus == RequestStatus.Accepted })

        makerWsClient.assertOrderCreatedMessageReceived()
        makerWsClient.assertLimitsMessageReceived(market.id)
        makerWsClient.assertOrderCreatedMessageReceived()

        assertEquals(2, makerApiClient.listOrders(listOf(OrderStatus.Open), null).orders.size)

        takerApiClient.createBackToBackMarketOrder(
            listOf(market, secondMarket),
            OrderSide.Buy,
            amount = BigDecimal("0.5"),
            takerWallet,
        )

        takerWsClient.apply {
            assertOrderCreatedMessageReceived()
            assertTradeCreatedMessageReceived {
                assertEquals(btc2Eth2Market.id, it.trade.marketId)
                assertEquals(OrderSide.Buy, it.trade.side)
                assertEquals(bridgeOrderAmount.inFundamentalUnits, it.trade.amount)
                assertEquals(quotePrice.setScale(18), it.trade.price)
                assertEquals(BigDecimal("0.189").toFundamentalUnits(quoteSymbol.decimals), it.trade.feeAmount)
                assertEquals(eth2.name, it.trade.feeSymbol.value)
            }
            assertTradeCreatedMessageReceived {
                assertEquals(btcbtc2Market.id, it.trade.marketId)
                assertEquals(OrderSide.Buy, it.trade.side)
                assertEquals(baseOrderAmount.inFundamentalUnits, it.trade.amount)
                assertEquals(bridgePrice.setScale(18), it.trade.price)
                assertEquals(BigInteger.ZERO, it.trade.feeAmount)
                assertEquals(btc2.name, it.trade.feeSymbol.value)
            }
            assertOrderUpdatedMessageReceived {
                assertEquals(baseOrderAmount.inFundamentalUnits, it.order.amount)
                assertEquals(it.order.status, OrderStatus.Filled)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = BigDecimal("0"), available = BigDecimal("0.5")),
                    ExpectedBalance(quoteSymbol, total = BigDecimal("10"), available = BigDecimal("0.361")),
                ),
            )
            assertLimitsMessageReceived(secondMarket.id)
        }

        makerWsClient.apply {
            repeat(2) { assertTradeCreatedMessageReceived() }
            repeat(2) { assertOrderUpdatedMessageReceived() }
            assertBalancesMessageReceived()
            assertLimitsMessageReceived(market.id)
        }

        val takerOrders = takerApiClient.listOrders(emptyList(), market.id).orders
        assertEquals(1, takerOrders.count { it.status == OrderStatus.Filled })

        assertEquals(2, makerApiClient.listOrders(listOf(OrderStatus.Filled, OrderStatus.Partial)).orders.size)

        // now verify the trades
        val trades = getTradesForOrders(takerOrders.map { it.id })
        assertEquals(2, trades.size)

        waitForSettlementToFinish(trades.map { it.id.value })

        val trade1 = trades.first { it.marketGuid.value == secondMarket.id }
        val trade2 = trades.first { it.marketGuid.value == market.id }

        assertEquals(trade1.amount, bridgeOrderAmount.inFundamentalUnits)
        assertEquals(trade1.price, quotePrice.setScale(18))

        assertEquals(trade2.amount, baseOrderAmount.inFundamentalUnits)
        assertEquals(trade2.price, bridgePrice.setScale(18))

        val notionalTrade1 = trade1.price.ofAsset(quoteSymbol) * AssetAmount(bridgeSymbol, trade1.amount).amount
        val notionalTrade2 = trade2.price.ofAsset(bridgeSymbol) * AssetAmount(baseSymbol, trade2.amount).amount

        val makerFeeTrade1 = notionalTrade1 * BigDecimal("0.01")
        val makerFeeTrade2 = notionalTrade2 * BigDecimal("0.01")
        val takerFee = notionalTrade1 * BigDecimal("0.02")

        assertBalances(
            listOf(
                ExpectedBalance(makerStartingBaseBalance - baseOrderAmount),
                ExpectedBalance(makerStartingBridgeBalance - makerFeeTrade2),
                ExpectedBalance(makerStartingQuoteBalance + notionalTrade1 - makerFeeTrade1),
            ),
            makerApiClient.getBalances().balances,
        )
        assertBalances(
            listOf(
                ExpectedBalance(takerStartingBaseBalance + baseOrderAmount),
                ExpectedBalance(takerStartingQuoteBalance - notionalTrade1 - takerFee),
            ),
            takerApiClient.getBalances().balances,
        )

        takerApiClient.cancelOpenOrders()
        makerApiClient.cancelOpenOrders()

        makerWsClient.close()
        takerWsClient.close()
    }
}
