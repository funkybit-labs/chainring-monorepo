package xyz.funkybit.integrationtests.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.extension.ExtendWith
import xyz.funkybit.apps.api.model.BatchOrdersApiRequest
import xyz.funkybit.apps.api.model.CreateOrderApiRequest
import xyz.funkybit.apps.api.model.OrderAmount
import xyz.funkybit.apps.api.model.RequestStatus
import xyz.funkybit.core.model.EvmSignature
import xyz.funkybit.core.model.Percentage
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.OrderSide
import xyz.funkybit.core.model.db.OrderStatus
import xyz.funkybit.core.utils.generateOrderNonce
import xyz.funkybit.core.utils.toFundamentalUnits
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.testutils.OrderBaseTest
import xyz.funkybit.integrationtests.utils.AssetAmount
import xyz.funkybit.integrationtests.utils.ExpectedBalance
import xyz.funkybit.integrationtests.utils.assertBalances
import xyz.funkybit.integrationtests.utils.assertBalancesMessageReceived
import xyz.funkybit.integrationtests.utils.assertLimitsMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyOrderCreatedMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyOrderUpdatedMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyTradesCreatedMessageReceived
import xyz.funkybit.integrationtests.utils.ofAsset
import xyz.funkybit.sequencer.core.notional
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
            subscribeToPrices = false,
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
            subscribeToPrices = false,
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
                cancelOrders = listOf(),
            ),
        )

        assertEquals(1, limitBuyOrder1.createdOrders.count { it.requestStatus == RequestStatus.Accepted })
        assertEquals(1, limitBuyOrder2.createdOrders.count { it.requestStatus == RequestStatus.Accepted })

        repeat(2) {
            makerWsClient.assertMyOrderCreatedMessageReceived()
            makerWsClient.assertLimitsMessageReceived()
        }

        assertEquals(2, makerApiClient.listOrders(listOf(OrderStatus.Open), null).orders.size)

        takerApiClient.createBackToBackMarketOrder(
            listOf(market, secondMarket),
            OrderSide.Sell,
            amount = null,
            takerWallet,
            percentage = Percentage(100),
        )

        takerWsClient.apply {
            assertMyOrderCreatedMessageReceived()
            assertMyTradesCreatedMessageReceived {
                assertEquals(2, it.trades.size)

                assertEquals(btcbtc2Market.id, it.trades[0].marketId)
                assertEquals(OrderSide.Sell, it.trades[0].side)
                assertEquals(takerStartingBaseBalance.inFundamentalUnits, it.trades[0].amount)
                assertEquals(BigDecimal("0.95").setScale(18), it.trades[0].price)
                assertEquals(BigInteger.ZERO, it.trades[0].feeAmount)
                assertEquals(btc2.name, it.trades[0].feeSymbol.value)

                assertEquals(btc2Eth2Market.id, it.trades[1].marketId)
                assertEquals(OrderSide.Sell, it.trades[1].side)
                assertEquals((BigDecimal("0.95") * BigDecimal("0.6")).toFundamentalUnits(btc2.decimals), it.trades[1].amount)
                assertEquals(BigDecimal("18.00").setScale(18), it.trades[1].price)
                assertEquals(BigDecimal("0.2052").toFundamentalUnits(eth2.decimals), it.trades[1].feeAmount)
                assertEquals(eth2.name, it.trades[1].feeSymbol.value)
            }
            assertMyOrderUpdatedMessageReceived {
                assertEquals(BigDecimal("0.6").toFundamentalUnits(market.baseDecimals), it.order.amount)
                assertEquals(it.order.status, OrderStatus.Filled)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = BigDecimal("0.6"), available = BigDecimal("0")),
                    ExpectedBalance(quoteSymbol, total = BigDecimal("0"), available = BigDecimal("10.0548")),
                ),
            )
            assertLimitsMessageReceived()
        }

        makerWsClient.apply {
            assertMyTradesCreatedMessageReceived {
                assertEquals(2, it.trades.size)
            }
            repeat(2) { assertMyOrderUpdatedMessageReceived() }
            assertBalancesMessageReceived()
            assertLimitsMessageReceived()
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
    fun `back to back - market sell - swap btc for usdc2 - partial fill`() {
        val (market, baseSymbol, bridgeSymbol) = Triple(btcbtc2Market, btc, btc2)
        val (secondMarket, _, quoteSymbol) = Triple(btc2Usdc2Market, btc2, usdc2)

        val (makerApiClient, makerWallet, makerWsClient) = setupTrader(
            secondMarket.id,
            airdrops = listOf(
                AssetAmount(bridgeSymbol, "5.0"),
                AssetAmount(quoteSymbol, "400000"),
            ),
            deposits = listOf(
                AssetAmount(bridgeSymbol, "4.9"),
                AssetAmount(quoteSymbol, "400000"),
            ),
            subscribeToOrderBook = false,
            subscribeToPrices = false,
        )

        val (takerApiClient, takerWallet, takerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "5"),
            ),
            deposits = listOf(
                AssetAmount(baseSymbol, "4.9"),
            ),
            subscribeToOrderBook = false,
            subscribeToPrices = false,
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
                            amount = OrderAmount.Fixed(AssetAmount(baseSymbol, BigDecimal("3.9")).inFundamentalUnits),
                            price = BigDecimal("1.01"),
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    ),
                ),
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
                            amount = OrderAmount.Fixed(AssetAmount(bridgeSymbol, BigDecimal("3")).inFundamentalUnits),
                            price = BigDecimal("66000.000"),
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    ),
                ),
                cancelOrders = listOf(),
            ),
        )

        assertEquals(1, limitBuyOrder1.createdOrders.count { it.requestStatus == RequestStatus.Accepted })
        assertEquals(1, limitBuyOrder2.createdOrders.count { it.requestStatus == RequestStatus.Accepted })

        repeat(2) {
            makerWsClient.assertMyOrderCreatedMessageReceived()
            makerWsClient.assertLimitsMessageReceived()
        }

        assertEquals(2, makerApiClient.listOrders(listOf(OrderStatus.Open), null).orders.size)

        takerApiClient.createBackToBackMarketOrder(
            listOf(market, secondMarket),
            OrderSide.Sell,
            amount = null,
            takerWallet,
            percentage = Percentage(100),
        )

        takerWsClient.apply {
            assertMyOrderCreatedMessageReceived()
            assertMyTradesCreatedMessageReceived {
                assertEquals(2, it.trades.size)

                assertEquals(btcbtc2Market.id, it.trades[0].marketId)
                assertEquals(OrderSide.Sell, it.trades[0].side)
                // assertEquals(takerStartingBaseBalance.inFundamentalUnits, it.trade.amount)
                assertEquals(BigDecimal("1.01").setScale(18), it.trades[0].price)
                assertEquals(BigInteger.ZERO, it.trades[0].feeAmount)
                assertEquals(btc2.name, it.trades[0].feeSymbol.value)

                assertEquals(btc2Usdc2Market.id, it.trades[1].marketId)
                assertEquals(OrderSide.Sell, it.trades[1].side)
                assertEquals(BigDecimal("2.999999999999999999").toFundamentalUnits(btc2.decimals), it.trades[1].amount)
                assertEquals(BigDecimal("66000.00").setScale(18), it.trades[1].price)
                assertEquals(BigDecimal("3959.999999").toFundamentalUnits(usdc2.decimals), it.trades[1].feeAmount)
                assertEquals(usdc2.name, it.trades[1].feeSymbol.value)
            }
            assertMyOrderUpdatedMessageReceived {
                assertEquals(BigDecimal("3.9").toFundamentalUnits(market.baseDecimals), it.order.amount)
                assertEquals(it.order.status, OrderStatus.Partial)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = BigDecimal("4.9"), available = BigDecimal("1.929702970297029703")),
                    ExpectedBalance(quoteSymbol, total = BigDecimal("0"), available = BigDecimal("194040")),
                ),
            )
            assertLimitsMessageReceived()
        }

        makerWsClient.apply {
            assertMyTradesCreatedMessageReceived {
                assertEquals(2, it.trades.size)
            }
            repeat(2) { assertMyOrderUpdatedMessageReceived() }
            assertBalancesMessageReceived()
            assertLimitsMessageReceived()
        }

        val takerOrders = takerApiClient.listOrders(emptyList(), market.id).orders
        assertEquals(1, takerOrders.count { it.status == OrderStatus.Partial })

        assertEquals(2, makerApiClient.listOrders(listOf(OrderStatus.Filled, OrderStatus.Partial)).orders.size)

        // now verify the trades
        val trades = getTradesForOrders(takerOrders.map { it.id })
        assertEquals(2, trades.size)

        waitForSettlementToFinish(trades.map { it.id.value })

        val trade1 = trades.first { it.marketGuid.value == market.id }
        val trade2 = trades.first { it.marketGuid.value == secondMarket.id }

        assertEquals(trade1.amount, BigDecimal("2.970297029702970297").toFundamentalUnits(btc.decimals))
        assertEquals(trade1.price, BigDecimal("1.01").setScale(18))

        assertEquals(trade2.amount, BigDecimal("2.999999999999999999").toFundamentalUnits(btc2.decimals))
        assertEquals(trade2.price, BigDecimal("66000.00").setScale(18))

        val notionalTrade1 = trade1.price.ofAsset(bridgeSymbol) * AssetAmount(baseSymbol, trade1.amount).amount
        val notionalTrade2 = trade2.price.ofAsset(quoteSymbol) * AssetAmount(bridgeSymbol, trade2.amount).amount

        val makerFeeTrade1 = notionalTrade1 * BigDecimal("0.01")
        val makerFeeTrade2 = notionalTrade2 * BigDecimal("0.01").setScale(18)
        val takerFee = notionalTrade2 * BigDecimal("0.02")

        assertBalances(
            listOf(
                ExpectedBalance(makerStartingBaseBalance + AssetAmount(btc, BigDecimal("2.970297029702970297"))),
                ExpectedBalance(makerStartingBridgeBalance - makerFeeTrade1),
                ExpectedBalance(makerStartingQuoteBalance - notionalTrade2 - makerFeeTrade2),
            ),
            makerApiClient.getBalances().balances,
        )
        assertBalances(
            listOf(
                ExpectedBalance(takerStartingBaseBalance - AssetAmount(btc, trade1.amount)),
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
            subscribeToPrices = false,
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
            subscribeToPrices = false,
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
                cancelOrders = listOf(),
            ),
        )

        assertEquals(1, limitSellOrder1.createdOrders.count { it.requestStatus == RequestStatus.Accepted })
        assertEquals(1, limitSellOrder2.createdOrders.count { it.requestStatus == RequestStatus.Accepted })

        repeat(2) {
            makerWsClient.assertMyOrderCreatedMessageReceived()
            makerWsClient.assertLimitsMessageReceived()
        }

        assertEquals(2, makerApiClient.listOrders(listOf(OrderStatus.Open), null).orders.size)

        takerApiClient.createBackToBackMarketOrder(
            listOf(market, secondMarket),
            OrderSide.Buy,
            amount = BigDecimal("0.5"),
            takerWallet,
        )

        takerWsClient.apply {
            assertMyOrderCreatedMessageReceived()
            assertMyTradesCreatedMessageReceived {
                assertEquals(2, it.trades.size)

                assertEquals(btc2Eth2Market.id, it.trades[0].marketId)
                assertEquals(OrderSide.Buy, it.trades[0].side)
                assertEquals(bridgeOrderAmount.inFundamentalUnits, it.trades[0].amount)
                assertEquals(quotePrice.setScale(18), it.trades[0].price)
                assertEquals(BigDecimal("0.189").toFundamentalUnits(quoteSymbol.decimals), it.trades[0].feeAmount)
                assertEquals(eth2.name, it.trades[0].feeSymbol.value)

                assertEquals(btcbtc2Market.id, it.trades[1].marketId)
                assertEquals(OrderSide.Buy, it.trades[1].side)
                assertEquals(baseOrderAmount.inFundamentalUnits, it.trades[1].amount)
                assertEquals(bridgePrice.setScale(18), it.trades[1].price)
                assertEquals(BigInteger.ZERO, it.trades[1].feeAmount)
                assertEquals(btc2.name, it.trades[1].feeSymbol.value)
            }
            assertMyOrderUpdatedMessageReceived {
                assertEquals(baseOrderAmount.inFundamentalUnits, it.order.amount)
                assertEquals(it.order.status, OrderStatus.Filled)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = BigDecimal("0"), available = BigDecimal("0.5")),
                    ExpectedBalance(quoteSymbol, total = BigDecimal("10"), available = BigDecimal("0.361")),
                ),
            )
            assertLimitsMessageReceived()
        }

        makerWsClient.apply {
            assertMyTradesCreatedMessageReceived {
                assertEquals(2, it.trades.size)
            }
            repeat(2) { assertMyOrderUpdatedMessageReceived() }
            assertBalancesMessageReceived()
            assertLimitsMessageReceived()
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

    @Test
    fun `back to back - market buy - swap eth2 for btc - partial fill`() {
        val (market, baseSymbol, bridgeSymbol) = Triple(btcbtc2Market, btc, btc2)
        val (secondMarket, _, quoteSymbol) = Triple(btc2Eth2Market, btc2, eth2)

        val (makerApiClient, makerWallet, makerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(bridgeSymbol, "1.3"),
                AssetAmount(baseSymbol, "1.2"),
            ),
            deposits = listOf(
                AssetAmount(bridgeSymbol, "1.2"),
                AssetAmount(baseSymbol, "1.1"),
            ),
            subscribeToOrderBook = false,
            subscribeToPrices = false,
        )

        val (takerApiClient, takerWallet, takerWsClient) = setupTrader(
            secondMarket.id,
            airdrops = listOf(
                AssetAmount(bridgeSymbol, "0.1"),
                AssetAmount(quoteSymbol, "30"),
            ),
            deposits = listOf(
                AssetAmount(quoteSymbol, "30"),
            ),
            subscribeToOrderBook = false,
            subscribeToPrices = false,
        )

        // starting onchain balances
        val makerStartingBaseBalance = makerWallet.getExchangeBalance(baseSymbol)
        val makerStartingBridgeBalance = makerWallet.getExchangeBalance(bridgeSymbol)
        val makerStartingQuoteBalance = makerWallet.getExchangeBalance(quoteSymbol)
        val takerStartingBaseBalance = takerWallet.getExchangeBalance(baseSymbol)
        val takerStartingQuoteBalance = takerWallet.getExchangeBalance(quoteSymbol)

        val bridgePrice = BigDecimal("1.01")
        val quotePrice = BigDecimal("17.55")
        val baseOrderAmount = AssetAmount(btc, "0.812345678901234567")
        val firstOrderAmount = notional(baseOrderAmount.inFundamentalUnits, bridgePrice, btc.decimals.toInt(), btc2.decimals.toInt())
        val bridgeOrderAmount = AssetAmount(btc2, "1.123456789012345678")

        val limitSellOrder1 = makerApiClient.batchOrders(
            BatchOrdersApiRequest(
                marketId = market.id,
                createOrders = listOf(
                    makerWallet.signOrder(
                        CreateOrderApiRequest.Limit(
                            nonce = generateOrderNonce(),
                            marketId = market.id,
                            side = OrderSide.Sell,
                            amount = OrderAmount.Fixed(baseOrderAmount.inFundamentalUnits),
                            price = bridgePrice,
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    ),
                ),
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
                            amount = OrderAmount.Fixed(bridgeOrderAmount.inFundamentalUnits),
                            price = quotePrice,
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    ),
                ),
                cancelOrders = listOf(),
            ),
        )

        assertEquals(1, limitSellOrder1.createdOrders.count { it.requestStatus == RequestStatus.Accepted })
        assertEquals(1, limitSellOrder2.createdOrders.count { it.requestStatus == RequestStatus.Accepted })

        repeat(2) {
            makerWsClient.assertMyOrderCreatedMessageReceived()
            makerWsClient.assertLimitsMessageReceived()
        }

        assertEquals(2, makerApiClient.listOrders(listOf(OrderStatus.Open), null).orders.size)

        takerApiClient.createBackToBackMarketOrder(
            listOf(market, secondMarket),
            OrderSide.Buy,
            amount = BigDecimal("1.4"),
            takerWallet,
        )

        takerWsClient.apply {
            assertMyOrderCreatedMessageReceived()
            assertMyTradesCreatedMessageReceived {
                assertEquals(2, it.trades.size)

                assertEquals(btc2Eth2Market.id, it.trades[0].marketId)
                assertEquals(OrderSide.Buy, it.trades[0].side)
                assertEquals(AssetAmount(btc2, firstOrderAmount).inFundamentalUnits, it.trades[0].amount)
                assertEquals(quotePrice.setScale(18), it.trades[0].price)
                assertEquals(BigDecimal("0.287984666627276666").toFundamentalUnits(quoteSymbol.decimals), it.trades[0].feeAmount)
                assertEquals(eth2.name, it.trades[0].feeSymbol.value)

                assertEquals(btcbtc2Market.id, it.trades[1].marketId)
                assertEquals(OrderSide.Buy, it.trades[1].side)
                assertEquals(baseOrderAmount.inFundamentalUnits, it.trades[1].amount)
                assertEquals(bridgePrice.setScale(18), it.trades[1].price)
                assertEquals(BigInteger.ZERO, it.trades[1].feeAmount)
                assertEquals(btc2.name, it.trades[1].feeSymbol.value)
            }
            assertMyOrderUpdatedMessageReceived {
                assertEquals(it.order.status, OrderStatus.Partial)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = BigDecimal("0"), available = baseOrderAmount.amount),
                    ExpectedBalance(quoteSymbol, total = BigDecimal("30"), available = BigDecimal("15.312782002008890029")),
                ),
            )
            assertLimitsMessageReceived()
        }

        makerWsClient.apply {
            assertMyTradesCreatedMessageReceived {
                assertEquals(2, it.trades.size)
            }
            repeat(2) { assertMyOrderUpdatedMessageReceived() }
            assertBalancesMessageReceived()
            assertLimitsMessageReceived()
        }

        val takerOrders = takerApiClient.listOrders(emptyList(), market.id).orders
        assertEquals(1, takerOrders.count { it.status == OrderStatus.Partial })

        assertEquals(2, makerApiClient.listOrders(listOf(OrderStatus.Filled, OrderStatus.Partial)).orders.size)

        // now verify the trades
        val trades = getTradesForOrders(takerOrders.map { it.id })
        assertEquals(2, trades.size)

        waitForSettlementToFinish(trades.map { it.id.value })

        val trade1 = trades.first { it.marketGuid.value == secondMarket.id }
        val trade2 = trades.first { it.marketGuid.value == market.id }

        assertEquals(trade1.amount, firstOrderAmount)
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
