package xyz.funkybit.integrationtests.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
import xyz.funkybit.sequencer.core.toBaseAmount
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
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
        val (market, inputSymbol, bridgeSymbol) = Triple(btcbtc2Market, btc, btc2)
        val (secondMarket, _, outputSymbol) = Triple(btc2Usdc2Market, btc2, usdc2)

        val (makerApiClient, makerWallet, makerWsClient) = setupTrader(
            secondMarket.id,
            airdrops = listOf(
                AssetAmount(bridgeSymbol, "5.0"),
                AssetAmount(outputSymbol, "400000"),
            ),
            deposits = listOf(
                AssetAmount(bridgeSymbol, "4.9"),
                AssetAmount(outputSymbol, "400000"),
            ),
            subscribeToOrderBook = false,
            subscribeToPrices = false,
        )

        val (takerApiClient, takerWallet, takerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(inputSymbol, "5"),
            ),
            deposits = listOf(
                AssetAmount(inputSymbol, "4.9"),
            ),
            subscribeToOrderBook = false,
            subscribeToPrices = false,
        )

        // starting onchain balances
        val makerStartingInputBalance = makerWallet.getExchangeBalance(inputSymbol)
        val makerStartingBridgeBalance = makerWallet.getExchangeBalance(bridgeSymbol)
        val makerStartingOutputBalance = makerWallet.getExchangeBalance(outputSymbol)
        val takerStartingInputBalance = takerWallet.getExchangeBalance(inputSymbol)
        val takerStartingOutputBalance = takerWallet.getExchangeBalance(outputSymbol)

        val limitBuyOrder1 = makerApiClient.batchOrders(
            BatchOrdersApiRequest(
                marketId = market.id,
                createOrders = listOf(
                    makerWallet.signOrder(
                        CreateOrderApiRequest.Limit(
                            nonce = generateOrderNonce(),
                            marketId = market.id,
                            side = OrderSide.Buy,
                            amount = OrderAmount.Fixed(AssetAmount(inputSymbol, BigDecimal("3.9")).inFundamentalUnits),
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
                assertEquals(BigDecimal("1.01").setScale(18), it.trades[0].price)
                assertEquals(BigInteger.ZERO, it.trades[0].feeAmount)
                assertEquals(btc2.name, it.trades[0].feeSymbol.value)

                assertEquals(btc2Usdc2Market.id, it.trades[1].marketId)
                assertEquals(OrderSide.Sell, it.trades[1].side)
                assertEquals(BigDecimal("2.999999999999999999").toFundamentalUnits(btc2.decimals), it.trades[1].amount)
                assertEquals(BigDecimal("66000.00").setScale(18), it.trades[1].price)
                assertEquals(BigDecimal("3960.0").toFundamentalUnits(usdc2.decimals), it.trades[1].feeAmount)
                assertEquals(usdc2.name, it.trades[1].feeSymbol.value)
            }
            assertMyOrderUpdatedMessageReceived {
                assertEquals(BigDecimal("3.9").toFundamentalUnits(market.baseDecimals), it.order.amount)
                assertEquals(it.order.status, OrderStatus.Partial)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(inputSymbol, total = BigDecimal("4.9"), available = BigDecimal("1.929702970297029703")),
                    ExpectedBalance(outputSymbol, total = BigDecimal("0"), available = BigDecimal("194039.999999")),
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

        val notionalTrade1 = trade1.price.ofAsset(bridgeSymbol) * AssetAmount(inputSymbol, trade1.amount).amount
        val notionalTrade2 = trade2.price.ofAsset(outputSymbol) * AssetAmount(bridgeSymbol, trade2.amount).amount

        val makerFeeTrade1 = notionalTrade1 * BigDecimal("0.01")
        val makerFeeTrade2 = notionalTrade2 * BigDecimal("0.01").setScale(18)
        val takerFee = notionalTrade2 * BigDecimal("0.02")

        val btc2RoundingAdjustment = AssetAmount(btc2, BigInteger.ONE)
        val usdc2RoundingAdjustment = AssetAmount(usdc2, BigInteger.ONE)

        assertBalances(
            listOf(
                ExpectedBalance(makerStartingInputBalance + AssetAmount(btc, BigDecimal("2.970297029702970297"))),
                ExpectedBalance(makerStartingBridgeBalance - makerFeeTrade1 - btc2RoundingAdjustment),
                ExpectedBalance(makerStartingOutputBalance - notionalTrade2 - makerFeeTrade2 - usdc2RoundingAdjustment),
            ),
            makerApiClient.getBalances().balances,
        )
        assertBalances(
            listOf(
                ExpectedBalance(takerStartingInputBalance - AssetAmount(btc, trade1.amount)),
                ExpectedBalance(takerStartingOutputBalance + notionalTrade2 - takerFee - usdc2RoundingAdjustment),
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
        val (market, _, inputSymbol) = Triple(btc2Eth2Market, btc2, eth2)
        val (secondMarket, outputSymbol, bridgeSymbol) = Triple(btcbtc2Market, btc, btc2)

        val (makerApiClient, makerWallet, makerWsClient) = setupTrader(
            secondMarket.id,
            airdrops = listOf(
                AssetAmount(bridgeSymbol, "1.0"),
                AssetAmount(outputSymbol, "1.0"),
            ),
            deposits = listOf(
                AssetAmount(bridgeSymbol, "0.9"),
                AssetAmount(outputSymbol, "0.9"),
            ),
            subscribeToOrderBook = false,
            subscribeToPrices = false,
        )

        val (takerApiClient, takerWallet, takerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(bridgeSymbol, "0.1"),
                AssetAmount(inputSymbol, "10"),
            ),
            deposits = listOf(
                AssetAmount(inputSymbol, "10"),
            ),
            subscribeToOrderBook = false,
            subscribeToPrices = false,
        )

        // starting onchain balances
        val makerStartingOutputBalance = makerWallet.getExchangeBalance(outputSymbol)
        val makerStartingBridgeBalance = makerWallet.getExchangeBalance(bridgeSymbol)
        val makerStartingInputBalance = makerWallet.getExchangeBalance(inputSymbol)
        val takerStartingOutputBalance = takerWallet.getExchangeBalance(outputSymbol)
        val takerStartingInputBalance = takerWallet.getExchangeBalance(inputSymbol)
        val takerStartingBridgeBalance = takerWallet.getExchangeBalance(bridgeSymbol)

        val outputPrice = BigDecimal("1.05")
        val bridgePrice = BigDecimal("18")
        val firstLegOrderAmount = AssetAmount(btc2, BigDecimal("0.5"))
        val secondLegOrderAmount = AssetAmount(
            btc,
            firstLegOrderAmount.amount *
                // notional -> base adjustment
                BigDecimal.ONE.setScale(18) / outputPrice /
                // fee adjustment
                BigDecimal("1.02") -
                // rounding adjustment
                BigDecimal(1).movePointLeft(18),
        )

        val limitSellOrder1 = makerApiClient.batchOrders(
            BatchOrdersApiRequest(
                marketId = market.id,
                createOrders = listOf(
                    makerWallet.signOrder(
                        CreateOrderApiRequest.Limit(
                            nonce = generateOrderNonce(),
                            marketId = market.id,
                            side = OrderSide.Sell,
                            amount = OrderAmount.Fixed(AssetAmount(bridgeSymbol, BigDecimal("0.8")).inFundamentalUnits),
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
                            amount = OrderAmount.Fixed(AssetAmount(outputSymbol, BigDecimal("0.8")).inFundamentalUnits),
                            price = outputPrice,
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
                assertTrue((firstLegOrderAmount.inFundamentalUnits - it.trades[0].amount).abs() <= BigInteger.ONE)
                assertEquals(bridgePrice.setScale(18), it.trades[0].price)
                assertEquals(BigInteger.ZERO, it.trades[0].feeAmount)
                assertEquals(eth2.name, it.trades[0].feeSymbol.value)

                assertEquals(btcbtc2Market.id, it.trades[1].marketId)
                assertEquals(OrderSide.Buy, it.trades[1].side)
                assertEquals(secondLegOrderAmount.inFundamentalUnits, it.trades[1].amount)
                assertEquals(outputPrice.setScale(18), it.trades[1].price)
                assertEquals(BigDecimal("0.009803921568627451").toFundamentalUnits(bridgeSymbol.decimals), it.trades[1].feeAmount)
                assertEquals(btc2.name, it.trades[1].feeSymbol.value)
            }
            assertMyOrderUpdatedMessageReceived {
                assertEquals(firstLegOrderAmount.inFundamentalUnits, it.order.amount)
                assertEquals(it.order.status, OrderStatus.Partial)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(outputSymbol, total = BigDecimal("0"), available = BigDecimal("0.466853408029878617")),
                    ExpectedBalance(inputSymbol, total = BigDecimal("10"), available = BigDecimal("1.000000000000000018")),
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

        assertTrue((trade1.amount - firstLegOrderAmount.inFundamentalUnits).abs() <= BigInteger.ONE)
        assertEquals(trade1.price, bridgePrice.setScale(18))

        assertTrue((trade2.amount - secondLegOrderAmount.inFundamentalUnits).abs() <= BigInteger.ONE)
        assertEquals(trade2.price, outputPrice.setScale(18))

        val baseTrade1 = AssetAmount(bridgeSymbol, trade1.amount)
        val notionalTrade1 = trade1.price.ofAsset(inputSymbol) * AssetAmount(inputSymbol, trade1.amount).amount
        val notionalTrade2 = trade2.price.ofAsset(bridgeSymbol) * AssetAmount(bridgeSymbol, trade2.amount).amount

        val makerFeeTrade1 = notionalTrade1 * BigDecimal("0.01")
        val makerFeeTrade2 = notionalTrade2 * BigDecimal("0.01")
        val takerFee = notionalTrade2 * BigDecimal("0.02")

        val eth2RoundingAdjustment = AssetAmount(eth2, BigInteger.ONE)
        val btc2RoundingAdjustment = AssetAmount(btc2, BigInteger.ONE)

        assertBalances(
            listOf(
                ExpectedBalance(makerStartingInputBalance + notionalTrade1 - makerFeeTrade1 - eth2RoundingAdjustment),
                ExpectedBalance(makerStartingBridgeBalance - baseTrade1 + notionalTrade2 - makerFeeTrade2),
                ExpectedBalance(makerStartingOutputBalance - AssetAmount(outputSymbol, trade2.amount)),
            ),
            makerApiClient.getBalances().balances,
        )
        assertBalances(
            listOf(
                ExpectedBalance(takerStartingOutputBalance + AssetAmount(outputSymbol, trade2.amount)),
                ExpectedBalance(takerStartingInputBalance - notionalTrade1),
                ExpectedBalance(takerStartingBridgeBalance + baseTrade1 - notionalTrade2 - takerFee - btc2RoundingAdjustment),
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
        val (market, _, inputSymbol) = Triple(btc2Eth2Market, btc2, eth2)
        val (secondMarket, outputSymbol, bridgeSymbol) = Triple(btcbtc2Market, btc, btc2)

        val (makerApiClient, makerWallet, makerWsClient) = setupTrader(
            secondMarket.id,
            airdrops = listOf(
                AssetAmount(bridgeSymbol, "1.3"),
                AssetAmount(outputSymbol, "1.2"),
            ),
            deposits = listOf(
                AssetAmount(bridgeSymbol, "1.2"),
                AssetAmount(outputSymbol, "1.1"),
            ),
            subscribeToOrderBook = false,
            subscribeToPrices = false,
        )

        val (takerApiClient, takerWallet, takerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(bridgeSymbol, "0.1"),
                AssetAmount(inputSymbol, "30"),
            ),
            deposits = listOf(
                AssetAmount(inputSymbol, "30"),
            ),
            subscribeToOrderBook = false,
            subscribeToPrices = false,
        )

        // starting onchain balances
        val makerStartingOutputBalance = makerWallet.getExchangeBalance(outputSymbol)
        val makerStartingBridgeBalance = makerWallet.getExchangeBalance(bridgeSymbol)
        val makerStartingInputBalance = makerWallet.getExchangeBalance(inputSymbol)
        val takerStartingOutputBalance = takerWallet.getExchangeBalance(outputSymbol)
        val takerStartingInputBalance = takerWallet.getExchangeBalance(inputSymbol)

        val firstLegPrice = BigDecimal("17.55")
        val secondLegPrice = BigDecimal("1.01")
        val secondLegAvailableAmount = AssetAmount(btc, "0.81")
        val secondLegNotional = notional(secondLegAvailableAmount.inFundamentalUnits.toBaseAmount(), secondLegPrice, btc.decimals.toInt(), btc2.decimals.toInt())
        val secondLegOrderAmount = AssetAmount(
            btc,
            secondLegAvailableAmount.amount /
                // fee adjustment
                BigDecimal("1.02") +
                // rounding adjustment
                BigDecimal(1).movePointLeft(18),
        )
        val firstLegAvailableAmount = AssetAmount(btc2, "1.15")

        val limitSellOrder1 = makerApiClient.batchOrders(
            BatchOrdersApiRequest(
                marketId = market.id,
                createOrders = listOf(
                    makerWallet.signOrder(
                        CreateOrderApiRequest.Limit(
                            nonce = generateOrderNonce(),
                            marketId = market.id,
                            side = OrderSide.Sell,
                            amount = OrderAmount.Fixed(firstLegAvailableAmount.inFundamentalUnits),
                            price = firstLegPrice,
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
                            amount = OrderAmount.Fixed(secondLegAvailableAmount.inFundamentalUnits),
                            price = secondLegPrice,
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    ),
                ),
                cancelOrders = listOf(),
            ),
        )

        assertEquals(1, limitSellOrder2.createdOrders.count { it.requestStatus == RequestStatus.Accepted })
        assertEquals(1, limitSellOrder1.createdOrders.count { it.requestStatus == RequestStatus.Accepted })

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
                assertEquals(AssetAmount(btc2, secondLegNotional.toBigInteger()).inFundamentalUnits, it.trades[0].amount)
                assertEquals(firstLegPrice.setScale(18), it.trades[0].price)
                assertEquals(BigInteger.ZERO, it.trades[0].feeAmount)
                assertEquals(eth2.name, it.trades[0].feeSymbol.value)

                assertEquals(btcbtc2Market.id, it.trades[1].marketId)
                assertEquals(OrderSide.Buy, it.trades[1].side)
                assertEquals(secondLegOrderAmount.inFundamentalUnits, it.trades[1].amount)
                assertEquals(secondLegPrice.setScale(18), it.trades[1].price)
                assertEquals(BigDecimal("0.016041176470588235").toFundamentalUnits(inputSymbol.decimals), it.trades[1].feeAmount)
                assertEquals(btc2.name, it.trades[1].feeSymbol.value)
            }
            assertMyOrderUpdatedMessageReceived {
                assertEquals(it.order.status, OrderStatus.Partial)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(outputSymbol, total = BigDecimal("0"), available = secondLegOrderAmount.amount),
                    ExpectedBalance(inputSymbol, total = BigDecimal("30"), available = BigDecimal("15.642345")),
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

        assertEquals(trade1.amount, secondLegNotional.toBigInteger())
        assertEquals(trade1.price, firstLegPrice.setScale(18))

        assertEquals(trade2.amount, secondLegOrderAmount.inFundamentalUnits)
        assertEquals(trade2.price, secondLegPrice.setScale(18))

        val notionalTrade1 = trade1.price.ofAsset(inputSymbol) * AssetAmount(bridgeSymbol, trade1.amount).amount
        val notionalTrade2 = trade2.price.ofAsset(bridgeSymbol) * AssetAmount(outputSymbol, trade2.amount).amount

        val makerFeeTrade1 = notionalTrade1 * BigDecimal("0.01")
        val makerFeeTrade2 = notionalTrade2 * BigDecimal("0.01")

        val btc2RoundingAdjustment = AssetAmount(btc2, BigInteger.ONE)

        assertBalances(
            listOf(
                ExpectedBalance(makerStartingOutputBalance - secondLegOrderAmount),
                ExpectedBalance(makerStartingBridgeBalance - AssetAmount(bridgeSymbol, trade1.amount) + notionalTrade2 - makerFeeTrade2 - btc2RoundingAdjustment),
                ExpectedBalance(makerStartingInputBalance + notionalTrade1 - makerFeeTrade1),
            ),
            makerApiClient.getBalances().balances,
        )
        assertBalances(
            listOf(
                ExpectedBalance(takerStartingOutputBalance + secondLegOrderAmount),
                ExpectedBalance(takerStartingInputBalance - notionalTrade1),
            ),
            takerApiClient.getBalances().balances,
        )

        takerApiClient.cancelOpenOrders()
        makerApiClient.cancelOpenOrders()

        makerWsClient.close()
        takerWsClient.close()
    }

    @Test
    fun `back to back on quote - market sell - swap btc for btc2 via eth2`() {
        val (market, inputSymbol, bridgeSymbol) = Triple(btcEth2Market, btc, eth2)
        val (secondMarket, outputSymbol, _) = Triple(btc2Eth2Market, btc2, eth2)

        val (makerApiClient, makerWallet, makerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(outputSymbol, "1.5"),
                AssetAmount(inputSymbol, "0.1"),
                AssetAmount(bridgeSymbol, "20.2"),
            ),
            deposits = listOf(
                AssetAmount(outputSymbol, "1.2"),
                AssetAmount(bridgeSymbol, "20.2"),
            ),
            subscribeToOrderBook = false,
            subscribeToPrices = false,
        )

        val (takerApiClient, takerWallet, takerWsClient) = setupTrader(
            secondMarket.id,
            airdrops = listOf(
                AssetAmount(inputSymbol, "1.5"),
            ),
            deposits = listOf(
                AssetAmount(inputSymbol, "1.0"),
            ),
            subscribeToOrderBook = false,
            subscribeToPrices = false,
        )

        // starting onchain balances
        val makerStartingInputBalance = makerWallet.getExchangeBalance(inputSymbol)
        val makerStartingBridgeBalance = makerWallet.getExchangeBalance(bridgeSymbol)
        val makerStartingOutputBalance = makerWallet.getExchangeBalance(outputSymbol)
        val takerStartingInputBalance = takerWallet.getExchangeBalance(inputSymbol)
        val takerStartingBridgeBalance = takerWallet.getExchangeBalance(bridgeSymbol)
        val takerStartingOutputBalance = takerWallet.getExchangeBalance(outputSymbol)

        val bridgePrice = BigDecimal("20.0").setScale(18)
        val inputOrderAmount = AssetAmount(btc, BigDecimal("1.0"))
        val bridgeOrderAmount = AssetAmount(eth2, inputOrderAmount.amount) * bridgePrice / BigDecimal("1.02")
        val outputPrice = BigDecimal("19.5")

        val limitBuyOrder = makerApiClient.batchOrders(
            BatchOrdersApiRequest(
                marketId = market.id,
                createOrders = listOf(
                    makerWallet.signOrder(
                        CreateOrderApiRequest.Limit(
                            nonce = generateOrderNonce(),
                            marketId = market.id,
                            side = OrderSide.Buy,
                            amount = OrderAmount.Fixed(AssetAmount(inputSymbol, BigDecimal("1.0")).inFundamentalUnits),
                            price = bridgePrice,
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    ),
                ),
                cancelOrders = listOf(),
            ),
        )
        val limitSellOrder = makerApiClient.batchOrders(
            BatchOrdersApiRequest(
                marketId = secondMarket.id,
                createOrders = listOf(
                    makerWallet.signOrder(
                        CreateOrderApiRequest.Limit(
                            nonce = generateOrderNonce(),
                            marketId = secondMarket.id,
                            side = OrderSide.Sell,
                            amount = OrderAmount.Fixed(AssetAmount(bridgeSymbol, BigDecimal("1.2")).inFundamentalUnits),
                            price = outputPrice,
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    ),
                ),
                cancelOrders = listOf(),
            ),
        )

        assertEquals(1, limitBuyOrder.createdOrders.count { it.requestStatus == RequestStatus.Accepted })
        assertEquals(1, limitSellOrder.createdOrders.count { it.requestStatus == RequestStatus.Accepted })

        repeat(2) {
            makerWsClient.assertMyOrderCreatedMessageReceived()
            makerWsClient.assertLimitsMessageReceived()
        }

        assertEquals(2, makerApiClient.listOrders(listOf(OrderStatus.Open), null).orders.size)

        takerApiClient.createBackToBackMarketOrder(
            listOf(market, secondMarket),
            OrderSide.Sell,
            amount = BigDecimal("1.0"),
            takerWallet,
        )

        takerWsClient.apply {
            assertMyOrderCreatedMessageReceived()
            assertMyTradesCreatedMessageReceived {
                assertEquals(2, it.trades.size)

                assertEquals(btcEth2Market.id, it.trades[0].marketId)
                assertEquals(OrderSide.Sell, it.trades[0].side)
                assertTrue((inputOrderAmount.inFundamentalUnits - it.trades[0].amount).abs() <= BigInteger.ONE)
                assertEquals(bridgePrice.setScale(18), it.trades[0].price)
                assertEquals(BigInteger.ZERO, it.trades[0].feeAmount)
                assertEquals(eth2.name, it.trades[0].feeSymbol.value)

                assertEquals(btc2Eth2Market.id, it.trades[1].marketId)
                assertEquals(OrderSide.Buy, it.trades[1].side)
                assertEquals(AssetAmount(btc2, bridgeOrderAmount.amount.setScale(18, RoundingMode.HALF_EVEN) / outputPrice.setScale(18)).inFundamentalUnits, it.trades[1].amount)
                assertEquals(outputPrice.setScale(18), it.trades[1].price)
                assertEquals(BigDecimal("0.392156862745098039").toFundamentalUnits(outputSymbol.decimals), it.trades[1].feeAmount)
                assertEquals(eth2.name, it.trades[1].feeSymbol.value)
            }
            assertMyOrderUpdatedMessageReceived {
                assertEquals(inputOrderAmount.inFundamentalUnits, it.order.amount)
                assertEquals(it.order.status, OrderStatus.Filled)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(inputSymbol, total = BigDecimal("1.0"), available = BigDecimal("0.0")),
                    ExpectedBalance(outputSymbol, total = BigDecimal("0"), available = BigDecimal("1.005530417295123177")),
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

        assertEquals(trade1.amount, inputOrderAmount.inFundamentalUnits)
        assertEquals(trade1.price, bridgePrice.setScale(18))

        assertTrue(
            (
                notional(trade2.amount.toBaseAmount(), trade2.price, btc2Eth2Market.baseDecimals, btc2Eth2Market.quoteDecimals).value -
                    bridgeOrderAmount.inFundamentalUnits
                ).abs() < BigInteger.valueOf(100),
        )
        assertEquals(trade2.price, outputPrice.setScale(18))

        val notionalTrade1 = trade1.price.ofAsset(bridgeSymbol) * AssetAmount(inputSymbol, trade1.amount).amount
        val notionalTrade2 = trade2.price.ofAsset(outputSymbol) * AssetAmount(bridgeSymbol, trade2.amount).amount
        val bridgeAmount = AssetAmount(bridgeSymbol, trade2.amount) * trade2.price
        val outputAmount = AssetAmount(outputSymbol, trade2.amount)

        val makerFeeTrade1 = notionalTrade1 * BigDecimal("0.01")
        val makerFeeTrade2 = AssetAmount(bridgeSymbol, notionalTrade2.amount) * BigDecimal("0.01")
        val takerFee = AssetAmount(bridgeSymbol, notionalTrade2.amount) * BigDecimal("0.02")

        val eth2RoundingAdjustment = AssetAmount(eth2, BigInteger.ONE)

        assertBalances(
            listOf(
                ExpectedBalance(makerStartingInputBalance + inputOrderAmount),
                ExpectedBalance(makerStartingBridgeBalance - notionalTrade1 + bridgeAmount - makerFeeTrade1 - makerFeeTrade2 - eth2RoundingAdjustment),
                ExpectedBalance(makerStartingOutputBalance - outputAmount),
            ),
            makerApiClient.getBalances().balances,
        )
        assertBalances(
            listOf(
                ExpectedBalance(takerStartingInputBalance - inputOrderAmount),
                ExpectedBalance(takerStartingBridgeBalance + notionalTrade1 - bridgeAmount - takerFee),
                ExpectedBalance(takerStartingOutputBalance + outputAmount),
            ),
            takerApiClient.getBalances().balances,
        )

        takerApiClient.cancelOpenOrders()
        makerApiClient.cancelOpenOrders()

        makerWsClient.close()
        takerWsClient.close()
    }

    @Test
    fun `back to back on base - market buy - swap eth for eth2 via btc`() {
        val (market, bridgeSymbol, inputSymbol) = Triple(btcEthMarket, btc, eth)
        val (secondMarket, _, outputSymbol) = Triple(btcEth2Market, btc, eth2)

        val (makerApiClient, makerWallet, makerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(bridgeSymbol, "1.5"),
                AssetAmount(inputSymbol, "0.5"),
                AssetAmount(btc2, "0.1"),
                AssetAmount(outputSymbol, "22.5"),
            ),
            deposits = listOf(
                AssetAmount(outputSymbol, "22.4"),
                AssetAmount(bridgeSymbol, "1.4"),
            ),
            subscribeToOrderBook = false,
            subscribeToPrices = false,
        )

        val (takerApiClient, takerWallet, takerWsClient) = setupTrader(
            secondMarket.id,
            airdrops = listOf(
                AssetAmount(bridgeSymbol, "0.1"),
                AssetAmount(inputSymbol, "15.5"),
            ),
            deposits = listOf(
                AssetAmount(inputSymbol, "15.2"),
            ),
            subscribeToOrderBook = false,
            subscribeToPrices = false,
        )

        // starting onchain balances
        val makerStartingInputBalance = makerWallet.getExchangeBalance(inputSymbol)
        val makerStartingBridgeBalance = makerWallet.getExchangeBalance(bridgeSymbol)
        val makerStartingOutputBalance = makerWallet.getExchangeBalance(outputSymbol)
        val takerStartingInputBalance = takerWallet.getExchangeBalance(inputSymbol)
        val takerStartingOutputBalance = takerWallet.getExchangeBalance(outputSymbol)

        val bridgePrice = BigDecimal("17.0").setScale(18)
        val inputNotionalAmount = AssetAmount(eth, "15.0")
        val bridgeOrderAmount = AssetAmount(btc, inputNotionalAmount.amount) / bridgePrice
        val outputPrice = BigDecimal("17.5")

        val limitSellOrder = makerApiClient.batchOrders(
            BatchOrdersApiRequest(
                marketId = market.id,
                createOrders = listOf(
                    makerWallet.signOrder(
                        CreateOrderApiRequest.Limit(
                            nonce = generateOrderNonce(),
                            marketId = market.id,
                            side = OrderSide.Sell,
                            amount = OrderAmount.Fixed(AssetAmount(inputSymbol, BigDecimal("1.1")).inFundamentalUnits),
                            price = bridgePrice,
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    ),
                ),
                cancelOrders = listOf(),
            ),
        )
        val limitBuyOrder = makerApiClient.batchOrders(
            BatchOrdersApiRequest(
                marketId = secondMarket.id,
                createOrders = listOf(
                    makerWallet.signOrder(
                        CreateOrderApiRequest.Limit(
                            nonce = generateOrderNonce(),
                            marketId = secondMarket.id,
                            side = OrderSide.Buy,
                            amount = OrderAmount.Fixed(AssetAmount(bridgeSymbol, BigDecimal("1.2")).inFundamentalUnits),
                            price = outputPrice,
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    ),
                ),
                cancelOrders = listOf(),
            ),
        )

        assertEquals(1, limitBuyOrder.createdOrders.count { it.requestStatus == RequestStatus.Accepted })
        assertEquals(1, limitSellOrder.createdOrders.count { it.requestStatus == RequestStatus.Accepted })

        repeat(2) {
            makerWsClient.assertMyOrderCreatedMessageReceived()
            makerWsClient.assertLimitsMessageReceived()
        }

        assertEquals(2, makerApiClient.listOrders(listOf(OrderStatus.Open), null).orders.size)

        takerApiClient.createBackToBackMarketOrder(
            listOf(market, secondMarket),
            OrderSide.Buy,
            amount = bridgeOrderAmount.amount,
            takerWallet,
        )

        takerWsClient.apply {
            assertMyOrderCreatedMessageReceived()
            assertMyTradesCreatedMessageReceived {
                assertEquals(2, it.trades.size)

                assertEquals(btcEthMarket.id, it.trades[0].marketId)
                assertEquals(OrderSide.Buy, it.trades[0].side)
                assertEquals(bridgeOrderAmount.inFundamentalUnits, it.trades[0].amount)
                assertEquals(bridgePrice.setScale(18), it.trades[0].price)
                assertEquals(BigInteger.ZERO, it.trades[0].feeAmount)
                assertEquals(eth.name, it.trades[0].feeSymbol.value)

                assertEquals(btcEth2Market.id, it.trades[1].marketId)
                assertEquals(OrderSide.Sell, it.trades[1].side)
                assertEquals(it.trades[0].amount, it.trades[1].amount)
                assertEquals(outputPrice.setScale(18), it.trades[1].price)
                assertEquals(BigDecimal("0.308823529411764706").toFundamentalUnits(outputSymbol.decimals), it.trades[1].feeAmount)
                assertEquals(eth2.name, it.trades[1].feeSymbol.value)
            }
            assertMyOrderUpdatedMessageReceived {
                assertEquals(bridgeOrderAmount.inFundamentalUnits, it.order.amount)
                assertEquals(it.order.status, OrderStatus.Filled)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(inputSymbol, total = BigDecimal("15.2"), available = BigDecimal("0.200000000000000004")),
                    ExpectedBalance(outputSymbol, total = BigDecimal("0"), available = BigDecimal("15.132352941176470584")),
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

        assertEquals(trade1.amount, bridgeOrderAmount.inFundamentalUnits)
        assertEquals(trade1.price, bridgePrice.setScale(18))

        assertEquals(trade1.amount, trade2.amount)
        assertEquals(trade2.price, outputPrice.setScale(18))

        val notionalTrade1 = trade1.price.ofAsset(inputSymbol) * AssetAmount(bridgeSymbol, trade1.amount).amount
        val notionalTrade2 = trade2.price.ofAsset(outputSymbol) * AssetAmount(bridgeSymbol, trade2.amount).amount
        val bridgeAmount = AssetAmount(bridgeSymbol, trade2.amount)

        val makerFeeTrade1 = notionalTrade1 * BigDecimal("0.01").setScale(18)
        val makerFeeTrade2 = AssetAmount(outputSymbol, notionalTrade2.amount) * BigDecimal("0.01")
        val takerFee = AssetAmount(outputSymbol, notionalTrade2.amount) * BigDecimal("0.02")

        val ethRoundingAdjustment = AssetAmount(eth, BigInteger.ONE)
        val eth2RoundingAdjustment = AssetAmount(eth2, BigInteger.ONE)

        assertBalances(
            listOf(
                ExpectedBalance(makerStartingInputBalance + notionalTrade1 - makerFeeTrade1 - ethRoundingAdjustment),
                ExpectedBalance(makerStartingBridgeBalance - AssetAmount(bridgeSymbol, trade1.amount) + bridgeAmount),
                ExpectedBalance(makerStartingOutputBalance - notionalTrade2 - makerFeeTrade2 - eth2RoundingAdjustment),
            ),
            makerApiClient.getBalances().balances,
        )
        assertBalances(
            listOf(
                ExpectedBalance(takerStartingInputBalance - notionalTrade1),
                ExpectedBalance(takerStartingOutputBalance + notionalTrade2 - takerFee - eth2RoundingAdjustment),
            ),
            takerApiClient.getBalances().balances,
        )

        takerApiClient.cancelOpenOrders()
        makerApiClient.cancelOpenOrders()

        makerWsClient.close()
        takerWsClient.close()
    }
}
