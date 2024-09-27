package xyz.funkybit.integrationtests.api

import kotlinx.datetime.Clock
import org.http4k.client.WebsocketClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import xyz.funkybit.apps.api.model.BatchOrdersApiRequest
import xyz.funkybit.apps.api.model.CancelOrderApiRequest
import xyz.funkybit.apps.api.model.CreateOrderApiRequest
import xyz.funkybit.apps.api.model.MarketLimits
import xyz.funkybit.apps.api.model.Order
import xyz.funkybit.apps.api.model.OrderAmount
import xyz.funkybit.apps.api.model.RequestStatus
import xyz.funkybit.apps.api.model.websocket.OHLC
import xyz.funkybit.apps.api.model.websocket.OrderBook
import xyz.funkybit.core.model.EvmSignature
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.ExecutionRole
import xyz.funkybit.core.model.db.OHLCDuration
import xyz.funkybit.core.model.db.OrderId
import xyz.funkybit.core.model.db.OrderSide
import xyz.funkybit.core.model.db.OrderStatus
import xyz.funkybit.core.model.db.SettlementStatus
import xyz.funkybit.core.utils.generateOrderNonce
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.testutils.OrderBaseTest
import xyz.funkybit.integrationtests.utils.AssetAmount
import xyz.funkybit.integrationtests.utils.EvmExchangeContractManager
import xyz.funkybit.integrationtests.utils.ExpectedBalance
import xyz.funkybit.integrationtests.utils.MyExpectedTrade
import xyz.funkybit.integrationtests.utils.assertAmount
import xyz.funkybit.integrationtests.utils.assertBalances
import xyz.funkybit.integrationtests.utils.assertBalancesMessageReceived
import xyz.funkybit.integrationtests.utils.assertFee
import xyz.funkybit.integrationtests.utils.assertLimitsMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyLimitOrderCreatedMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyMarketOrderCreatedMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyOrderCreatedMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyOrderUpdatedMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyOrdersMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyTradesCreatedMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyTradesMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyTradesUpdatedMessageReceived
import xyz.funkybit.integrationtests.utils.assertOrderBookMessageReceived
import xyz.funkybit.integrationtests.utils.assertPricesMessageReceived
import xyz.funkybit.integrationtests.utils.blocking
import xyz.funkybit.integrationtests.utils.ofAsset
import xyz.funkybit.integrationtests.utils.subscribeToLimits
import xyz.funkybit.integrationtests.utils.subscribeToMyOrders
import xyz.funkybit.integrationtests.utils.subscribeToMyTrades
import xyz.funkybit.integrationtests.utils.subscribeToOrderBook
import xyz.funkybit.integrationtests.utils.sum
import xyz.funkybit.integrationtests.utils.toCancelOrderRequest
import xyz.funkybit.integrationtests.utils.verifyApiReturnsSameLimits
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs

@ExtendWith(AppUnderTestRunner::class)
class OrderExecutionTest : OrderBaseTest() {
    @ParameterizedTest
    @MethodSource("chainIndices")
    fun `order execution`(chainIndex: Int) {
        val (market, baseSymbol, quoteSymbol) = if (chainIndex == 0) {
            Triple(btcEthMarket, btc, eth)
        } else {
            Triple(btc2Eth2Market, btc2, eth2)
        }

        val evmExchangeContractManager = EvmExchangeContractManager()

        val initialFeeAccountBalance = evmExchangeContractManager.getFeeBalance(quoteSymbol)

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
            amount = BigDecimal("0.00012345"),
            price = BigDecimal("17.500"),
            makerWallet,
        )
        makerWsClient.assertMyLimitOrderCreatedMessageReceived(limitBuyOrderApiResponse)

        listOf(makerWsClient, takerWsClient).forEach { wsClient ->
            wsClient.assertOrderBookMessageReceived(
                market.id,
                OrderBook(
                    marketId = market.id,
                    buy = listOf(
                        OrderBook.Entry(price = "17.500", size = "0.00012345".toBigDecimal()),
                    ),
                    sell = emptyList(),
                    last = OrderBook.LastTrade("0.000", OrderBook.LastTradeDirection.Unchanged),
                ),
            )
        }

        makerWsClient.assertLimitsMessageReceived(
            if (market == btcEthMarket) {
                listOfNotNull(
                    btcbtcArchMarket?.let {
                        MarketLimits(it.id, base = makerStartingBaseBalance.inFundamentalUnits, quote = BigInteger.ZERO)
                    },
                    MarketLimits(btcbtc2Market.id, base = makerStartingBaseBalance.inFundamentalUnits, quote = BigInteger.ZERO),
                    MarketLimits(btcEthMarket.id, base = makerStartingBaseBalance.inFundamentalUnits, quote = AssetAmount(eth, "1.99781802125").inFundamentalUnits),
                    MarketLimits(btcEth2Market.id, base = makerStartingBaseBalance.inFundamentalUnits, quote = BigInteger.ZERO),
                    MarketLimits(btcUsdcMarket.id, base = makerStartingBaseBalance.inFundamentalUnits, quote = BigInteger.ZERO),
                    MarketLimits(btc2Eth2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(btc2Usdc2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(usdcDaiMarket.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(usdc2Dai2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                )
            } else {
                listOfNotNull(
                    btcbtcArchMarket?.let {
                        MarketLimits(it.id, base = BigInteger.ZERO, quote = BigInteger.ZERO)
                    },
                    MarketLimits(btcbtc2Market.id, base = BigInteger.ZERO, quote = makerStartingBaseBalance.inFundamentalUnits),
                    MarketLimits(btcEthMarket.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(btcEth2Market.id, base = BigInteger.ZERO, quote = makerStartingQuoteBalance.inFundamentalUnits),
                    MarketLimits(btcUsdcMarket.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(btc2Eth2Market.id, base = makerStartingBaseBalance.inFundamentalUnits, quote = AssetAmount(eth2, "1.99781802125").inFundamentalUnits),
                    MarketLimits(btc2Usdc2Market.id, base = makerStartingBaseBalance.inFundamentalUnits, quote = BigInteger.ZERO),
                    MarketLimits(usdcDaiMarket.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(usdc2Dai2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                )
            },
        ).also { wsMessage -> verifyApiReturnsSameLimits(makerApiClient, wsMessage) }

        // place a sell order
        val limitSellOrderApiResponse = makerApiClient.createLimitOrder(
            market,
            OrderSide.Sell,
            amount = BigDecimal("0.00054321"),
            price = BigDecimal("17.550"),
            makerWallet,
        )

        makerWsClient.assertMyLimitOrderCreatedMessageReceived(limitSellOrderApiResponse)

        listOf(makerWsClient, takerWsClient).forEach { wsClient ->
            wsClient.assertOrderBookMessageReceived(
                market.id,
                OrderBook(
                    marketId = market.id,
                    buy = listOf(
                        OrderBook.Entry(price = "17.500", size = "0.00012345".toBigDecimal()),
                    ),
                    sell = listOf(
                        OrderBook.Entry(price = "17.550", size = "0.00054321".toBigDecimal()),
                    ),
                    last = OrderBook.LastTrade("0.000", OrderBook.LastTradeDirection.Unchanged),
                ),
            )
        }

        makerWsClient.assertLimitsMessageReceived(
            if (market == btcEthMarket) {
                listOfNotNull(
                    btcbtcArchMarket?.let {
                        MarketLimits(it.id, base = makerStartingBaseBalance.inFundamentalUnits, quote = BigInteger.ZERO)
                    },
                    MarketLimits(btcbtc2Market.id, base = makerStartingBaseBalance.inFundamentalUnits, quote = BigInteger.ZERO),
                    MarketLimits(btcEthMarket.id, base = AssetAmount(btc, "0.19945679").inFundamentalUnits, quote = AssetAmount(eth, "1.99781802125").inFundamentalUnits),
                    MarketLimits(btcEth2Market.id, base = makerStartingBaseBalance.inFundamentalUnits, quote = BigInteger.ZERO),
                    MarketLimits(btcUsdcMarket.id, base = makerStartingBaseBalance.inFundamentalUnits, quote = BigInteger.ZERO),
                    MarketLimits(btc2Eth2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(btc2Usdc2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(usdcDaiMarket.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(usdc2Dai2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                )
            } else {
                listOfNotNull(
                    btcbtcArchMarket?.let {
                        MarketLimits(it.id, base = BigInteger.ZERO, quote = BigInteger.ZERO)
                    },
                    MarketLimits(btcbtc2Market.id, base = BigInteger.ZERO, quote = makerStartingBaseBalance.inFundamentalUnits),
                    MarketLimits(btcEthMarket.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(btcEth2Market.id, base = BigInteger.ZERO, quote = makerStartingQuoteBalance.inFundamentalUnits),
                    MarketLimits(btcUsdcMarket.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(btc2Eth2Market.id, base = AssetAmount(btc2, "0.19945679").inFundamentalUnits, quote = AssetAmount(eth2, "1.99781802125").inFundamentalUnits),
                    MarketLimits(btc2Usdc2Market.id, base = makerStartingBaseBalance.inFundamentalUnits, quote = BigInteger.ZERO),
                    MarketLimits(usdcDaiMarket.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    MarketLimits(usdc2Dai2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                )
            },
        ).also { wsMessage -> verifyApiReturnsSameLimits(makerApiClient, wsMessage) }

        // place a buy order and see it gets executed
        val marketBuyOrderApiResponse = takerApiClient.createMarketOrder(
            market,
            OrderSide.Buy,
            BigDecimal("0.00043210"),
            takerWallet,
        )

        takerWsClient.apply {
            assertMyMarketOrderCreatedMessageReceived(marketBuyOrderApiResponse)
            assertMyTradesCreatedMessageReceived(
                listOf(
                    MyExpectedTrade(
                        order = marketBuyOrderApiResponse,
                        price = (limitSellOrderApiResponse.order as CreateOrderApiRequest.Limit).price,
                        amount = AssetAmount(baseSymbol, marketBuyOrderApiResponse.order.amount.fixedAmount()),
                        fee = AssetAmount(quoteSymbol, "0.0001516671"),
                        settlementStatus = SettlementStatus.Pending,
                    ),
                ),
            )
            assertMyOrderUpdatedMessageReceived { msg ->
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
            assertMyTradesCreatedMessageReceived(
                listOf(
                    MyExpectedTrade(
                        order = limitSellOrderApiResponse,
                        price = (limitSellOrderApiResponse.order as CreateOrderApiRequest.Limit).price,
                        amount = AssetAmount(baseSymbol, "0.00043210"),
                        fee = AssetAmount(quoteSymbol, "0.00007583355"),
                        settlementStatus = SettlementStatus.Pending,
                    ),
                ),
            )
            assertMyOrderUpdatedMessageReceived { msg ->
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
                        OrderBook.Entry(price = "17.500", size = "0.00012345".toBigDecimal()),
                    ),
                    sell = listOf(
                        OrderBook.Entry(price = "17.550", size = "0.00011111".toBigDecimal()),
                    ),
                    last = OrderBook.LastTrade("17.550", OrderBook.LastTradeDirection.Up),
                ),
            )
        }

        val trade = getTradesForOrders(listOf(marketBuyOrderApiResponse.orderId)).first().also {
            assertAmount(AssetAmount(baseSymbol, "0.00043210"), it.amount)
            assertAmount(AssetAmount(quoteSymbol, "17.550"), it.price)
            assertEquals(takerApiClient.getOrder(marketBuyOrderApiResponse.orderId).executions.first().tradeId, it.id.value)
        }

        takerWsClient.apply {
            assertPricesMessageReceived(market.id) { msg ->
                assertEquals(
                    OHLC(
                        start = OHLCDuration.P5M.durationStart(trade.timestamp),
                        open = BigDecimal("17.55"),
                        high = BigDecimal("17.55"),
                        low = BigDecimal("17.55"),
                        close = BigDecimal("17.55"),
                        duration = OHLCDuration.P5M,
                    ),
                    msg.ohlc.last(),
                )
            }
            assertLimitsMessageReceived(market, base = BigDecimal("0.0004321"), quote = BigDecimal("1.9922649779"))
                .also { wsMessage -> verifyApiReturnsSameLimits(takerApiClient, wsMessage) }
        }

        makerWsClient.apply {
            assertPricesMessageReceived(market.id) { msg ->
                assertEquals(
                    OHLC(
                        start = OHLCDuration.P5M.durationStart(trade.timestamp),
                        open = BigDecimal("17.55"),
                        high = BigDecimal("17.55"),
                        low = BigDecimal("17.55"),
                        close = BigDecimal("17.55"),
                        duration = OHLCDuration.P5M,
                    ),
                    msg.ohlc.last(),
                )
            }
            assertLimitsMessageReceived(
                if (market == btcEthMarket) {
                    listOfNotNull(
                        btcbtcArchMarket?.let {
                            MarketLimits(it.id, base = AssetAmount(btc, "0.1995679").inFundamentalUnits, quote = BigInteger.ZERO)
                        },
                        MarketLimits(btcbtc2Market.id, base = AssetAmount(btc, "0.1995679").inFundamentalUnits, quote = BigInteger.ZERO),
                        MarketLimits(btcEthMarket.id, base = AssetAmount(btc, "0.19945679").inFundamentalUnits, quote = AssetAmount(eth, "2.0053255427").inFundamentalUnits),
                        MarketLimits(btcEth2Market.id, base = AssetAmount(btc, "0.1995679").inFundamentalUnits, quote = BigInteger.ZERO),
                        MarketLimits(btcUsdcMarket.id, base = AssetAmount(btc, "0.1995679").inFundamentalUnits, quote = BigInteger.ZERO),
                        MarketLimits(btc2Eth2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                        MarketLimits(btc2Usdc2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                        MarketLimits(usdcDaiMarket.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                        MarketLimits(usdc2Dai2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    )
                } else {
                    listOfNotNull(
                        btcbtcArchMarket?.let {
                            MarketLimits(it.id, base = BigInteger.ZERO, quote = BigInteger.ZERO)
                        },
                        MarketLimits(btcbtc2Market.id, base = BigInteger.ZERO, quote = AssetAmount(btc2, "0.1995679").inFundamentalUnits),
                        MarketLimits(btcEthMarket.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                        MarketLimits(btcEth2Market.id, base = BigInteger.ZERO, quote = AssetAmount(eth2, "2.00750752145").inFundamentalUnits),
                        MarketLimits(btcUsdcMarket.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                        MarketLimits(btc2Eth2Market.id, base = AssetAmount(btc2, "0.19945679").inFundamentalUnits, quote = AssetAmount(eth2, "2.0053255427").inFundamentalUnits),
                        MarketLimits(btc2Usdc2Market.id, base = AssetAmount(btc2, "0.1995679").inFundamentalUnits, quote = BigInteger.ZERO),
                        MarketLimits(usdcDaiMarket.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                        MarketLimits(usdc2Dai2Market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    )
                },
            ).also { wsMessage -> verifyApiReturnsSameLimits(makerApiClient, wsMessage) }
        }

        waitForSettlementToFinishWithForking(listOf(trade.id.value), rollbackSettlement = false)

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
        takerWsClient.assertMyTradesUpdatedMessageReceived { msg ->
            assertEquals(1, msg.trades.size)
            assertEquals(marketBuyOrderApiResponse.orderId, msg.trades[0].orderId)
            assertEquals(SettlementStatus.Completed, msg.trades[0].settlementStatus)
        }

        makerWsClient.assertBalancesMessageReceived(
            listOf(
                ExpectedBalance(makerStartingBaseBalance - baseQuantity),
                ExpectedBalance(makerStartingQuoteBalance + notional - makerFee),
            ),
        )
        makerWsClient.assertMyTradesUpdatedMessageReceived { msg ->
            assertEquals(1, msg.trades.size)
            assertEquals(limitSellOrderApiResponse.orderId, msg.trades[0].orderId)
            assertEquals(SettlementStatus.Completed, msg.trades[0].settlementStatus)
        }

        // place a sell order and see it gets executed
        val marketSellOrderApiResponse = takerApiClient.createMarketOrder(
            market,
            OrderSide.Sell,
            BigDecimal("0.00012346"),
            takerWallet,
        )

        takerWsClient.apply {
            assertMyMarketOrderCreatedMessageReceived(marketSellOrderApiResponse)
            assertMyTradesCreatedMessageReceived(
                listOf(
                    MyExpectedTrade(
                        order = marketSellOrderApiResponse,
                        price = BigDecimal("17.500"),
                        amount = AssetAmount(baseSymbol, "0.00012345"),
                        fee = AssetAmount(quoteSymbol, "0.0000432075"),
                        settlementStatus = SettlementStatus.Pending,
                    ),
                ),
            )
            assertMyOrderUpdatedMessageReceived { msg ->
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
            assertMyTradesCreatedMessageReceived(
                listOf(
                    MyExpectedTrade(
                        order = limitBuyOrderApiResponse,
                        price = (limitBuyOrderApiResponse.order as CreateOrderApiRequest.Limit).price,
                        amount = AssetAmount(baseSymbol, "0.00012345"),
                        fee = AssetAmount(quoteSymbol, "0.00002160375"),
                        settlementStatus = SettlementStatus.Pending,
                    ),
                ),
            )
            assertMyOrderUpdatedMessageReceived { msg ->
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
                        OrderBook.Entry(price = "17.550", size = "0.00011111".toBigDecimal()),
                    ),
                    last = OrderBook.LastTrade("17.500", OrderBook.LastTradeDirection.Down),
                ),
            )
        }

        val trade2 = getTradesForOrders(listOf(marketSellOrderApiResponse.orderId)).first().also {
            assertAmount(AssetAmount(baseSymbol, "0.00012345"), it.amount)
            assertAmount(AssetAmount(quoteSymbol, "17.500"), it.price)
        }

        waitForSettlementToFinishWithForking(listOf(trade2.id.value), rollbackSettlement = true)

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
                val lastOhlc = msg.ohlc.last()
                assertEquals(OHLCDuration.P5M.durationStart(trade2.timestamp), lastOhlc.start)
                // open and high props are asserted like this since sometimes trade1 and trade2 don't get into the same 5min bucket
                assertContains(listOf(BigDecimal("17.5"), BigDecimal("17.55")), lastOhlc.open)
                assertContains(listOf(BigDecimal("17.5"), BigDecimal("17.55")), lastOhlc.high)
                assertEquals(BigDecimal("17.5"), lastOhlc.low)
                assertEquals(BigDecimal("17.5"), lastOhlc.close)
                assertEquals(OHLCDuration.P5M, lastOhlc.duration)
            }

            assertLimitsMessageReceived(market, base = BigDecimal("0.00030865"), quote = BigDecimal("1.9943821454"))
                .also { wsMessage -> verifyApiReturnsSameLimits(takerApiClient, wsMessage) }

            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(takerStartingBaseBalance + baseQuantity - baseQuantity2),
                    ExpectedBalance(takerStartingQuoteBalance - (notional + takerFee) + (notional2 - takerFee2)),
                ),
            )
            assertMyTradesUpdatedMessageReceived { msg ->
                assertEquals(1, msg.trades.size)
                assertEquals(marketSellOrderApiResponse.orderId, msg.trades[0].orderId)
                assertEquals(SettlementStatus.Completed, msg.trades[0].settlementStatus)
            }
        }

        makerWsClient.apply {
            assertPricesMessageReceived(market.id) { msg ->
                val lastOhlc = msg.ohlc.last()
                assertEquals(OHLCDuration.P5M.durationStart(trade2.timestamp), lastOhlc.start)
                // open and high props are asserted like this since sometimes trade1 and trade2 don't get into the same 5min bucket
                assertContains(listOf(BigDecimal("17.5"), BigDecimal("17.55")), lastOhlc.open)
                assertContains(listOf(BigDecimal("17.5"), BigDecimal("17.55")), lastOhlc.high)
                assertEquals(BigDecimal("17.5"), lastOhlc.low)
                assertEquals(BigDecimal("17.5"), lastOhlc.close)
                assertEquals(OHLCDuration.P5M, lastOhlc.duration)
            }

            assertLimitsMessageReceived(market, base = BigDecimal("0.19958024"), quote = BigDecimal("2.0053255427"))
                .also { wsMessage -> verifyApiReturnsSameLimits(makerApiClient, wsMessage) }

            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(makerStartingBaseBalance - baseQuantity + baseQuantity2),
                    ExpectedBalance(makerStartingQuoteBalance + (notional - makerFee) - (notional2 + makerFee2)),
                ),
            )
            assertMyTradesUpdatedMessageReceived { msg ->
                assertEquals(1, msg.trades.size)
                assertEquals(limitBuyOrderApiResponse.orderId, msg.trades[0].orderId)
                assertEquals(SettlementStatus.Completed, msg.trades[0].settlementStatus)
            }
        }

        val takerOrderCount = takerApiClient.listOrders(statuses = listOf(OrderStatus.Open, OrderStatus.Partial)).orders.filterNot { it is Order.Market }.size
        takerApiClient.cancelOpenOrders()
        repeat(takerOrderCount) {
            takerWsClient.assertMyOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Cancelled, msg.order.status)
            }
        }

        val makerOrderCount = makerApiClient.listOrders(statuses = listOf(OrderStatus.Open, OrderStatus.Partial)).orders.filterNot { it is Order.Market }.size
        makerApiClient.cancelOpenOrders()
        repeat(makerOrderCount) {
            makerWsClient.assertMyOrderUpdatedMessageReceived { msg ->
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
                    last = OrderBook.LastTrade("17.500", OrderBook.LastTradeDirection.Down),
                ),
            )
        }
        makerWsClient
            .assertLimitsMessageReceived()
            .also { wsMessage -> verifyApiReturnsSameLimits(makerApiClient, wsMessage) }

        // verify that client's websocket gets same state on reconnect
        takerWsClient.close()
        WebsocketClient.blocking(takerApiClient.authToken).apply {
            subscribeToMyOrders()
            assertMyOrdersMessageReceived { msg ->
                assertEquals(2, msg.orders.size)
                msg.orders[0].also { order ->
                    assertIs<Order.Market>(order)
                    assertEquals(marketSellOrderApiResponse.orderId, order.id)
                    assertEquals(marketSellOrderApiResponse.order.marketId, order.marketId)
                    assertEquals(marketSellOrderApiResponse.order.side, order.side)
                    assertEquals(marketSellOrderApiResponse.order.amount.fixedAmount(), order.amount)
                    assertEquals(OrderStatus.Partial, order.status)
                }
                msg.orders[1].also { order ->
                    assertIs<Order.Market>(order)
                    assertEquals(marketBuyOrderApiResponse.orderId, order.id)
                    assertEquals(marketBuyOrderApiResponse.order.marketId, order.marketId)
                    assertEquals(marketBuyOrderApiResponse.order.side, order.side)
                    assertEquals(marketBuyOrderApiResponse.order.amount.fixedAmount(), order.amount)
                    assertEquals(OrderStatus.Filled, order.status)
                }
            }

            subscribeToMyTrades()
            assertMyTradesMessageReceived { msg ->
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
                    assertAmount((limitSellOrderApiResponse.order as CreateOrderApiRequest.Limit).price.ofAsset(quoteSymbol), price)
                    assertAmount(AssetAmount(baseSymbol, marketBuyOrderApiResponse.order.amount.fixedAmount()), amount)
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
                    last = OrderBook.LastTrade("17.500", OrderBook.LastTradeDirection.Down),
                ),
            )

            subscribeToLimits()
            assertLimitsMessageReceived(market, base = BigDecimal("0.00030865"), quote = BigDecimal("1.9943821454"))
                .also { wsMessage -> verifyApiReturnsSameLimits(takerApiClient, wsMessage) }
        }.close()

        makerWsClient.close()

        // verify that fees have settled correctly on chain
        assertEquals(
            makerFee + takerFee + makerFee2 + takerFee2,
            evmExchangeContractManager.getFeeBalance(quoteSymbol) - initialFeeAccountBalance,
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
                AssetAmount(quoteSymbol, "5000"),
            ),
            deposits = listOf(
                AssetAmount(quoteSymbol, "5000"),
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
                createOrders = listOf("0.001", "0.002", "0.003").map {
                    makerWallet.signOrder(
                        CreateOrderApiRequest.Limit(
                            nonce = generateOrderNonce(),
                            marketId = market.id,
                            side = OrderSide.Sell,
                            amount = OrderAmount.Fixed(AssetAmount(baseSymbol, it).inFundamentalUnits),
                            price = BigDecimal("68400.000"),
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    )
                },
                cancelOrders = listOf(),
            ),
        )

        assertEquals(3, createBatchLimitOrders.createdOrders.count())
        repeat(3) { makerWsClient.assertMyOrderCreatedMessageReceived() }
        makerWsClient
            .assertLimitsMessageReceived(market, base = BigDecimal("0.194"), quote = BigDecimal("500"))
            .also { wsMessage -> verifyApiReturnsSameLimits(makerApiClient, wsMessage) }

        val batchOrderResponse = makerApiClient.batchOrders(
            BatchOrdersApiRequest(
                marketId = market.id,
                createOrders = listOf("0.004", "0.005", "0.006").map {
                    makerWallet.signOrder(
                        CreateOrderApiRequest.Limit(
                            nonce = generateOrderNonce(),
                            marketId = market.id,
                            side = OrderSide.Sell,
                            amount = OrderAmount.Fixed(AssetAmount(baseSymbol, it).inFundamentalUnits),
                            price = BigDecimal("68400.000"),
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    )
                },
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
        assertEquals(1, batchOrderResponse.canceledOrders.count { it.requestStatus == RequestStatus.Accepted })
        assertEquals(1, batchOrderResponse.canceledOrders.count { it.requestStatus == RequestStatus.Rejected })

        repeat(3) { makerWsClient.assertMyOrderCreatedMessageReceived() }
        makerWsClient.assertMyOrderUpdatedMessageReceived()
        makerWsClient
            .assertLimitsMessageReceived(market, base = BigDecimal("0.182"), quote = BigDecimal("500"))
            .also { wsMessage -> verifyApiReturnsSameLimits(makerApiClient, wsMessage) }

        assertEquals(5, makerApiClient.listOrders(listOf(OrderStatus.Open), null).orders.size)

        // total BTC available is 0.001 + 0.002 + 0.004 + 0.005 + 0.006 = 0.018
        val takerOrderAmount = AssetAmount(baseSymbol, "0.018")
        // create a market orders that should match against the 5 limit orders
        takerApiClient.createMarketOrder(
            market,
            OrderSide.Buy,
            amount = takerOrderAmount.amount,
            takerWallet,
        )

        takerWsClient.apply {
            assertMyOrderCreatedMessageReceived()
            assertMyTradesCreatedMessageReceived { msg ->
                assertEquals(5, msg.trades.size)
            }
            assertMyOrderUpdatedMessageReceived()
            assertBalancesMessageReceived()
            assertPricesMessageReceived(market.id) { msg ->
                assertEquals(
                    OHLC(
                        // initial ohlc in the BTC/USDC market
                        // price is a weighted price across all order execution of market order
                        start = OHLCDuration.P5M.durationStart(Clock.System.now()),
                        open = BigDecimal("68400"),
                        high = BigDecimal("68400"),
                        low = BigDecimal("68400"),
                        close = BigDecimal("68400"),
                        duration = OHLCDuration.P5M,
                    ),
                    msg.ohlc.last(),
                )
            }
            assertLimitsMessageReceived(market, base = BigDecimal("0.018"), quote = BigDecimal("3744.176"))
                .also { wsMessage -> verifyApiReturnsSameLimits(takerApiClient, wsMessage) }
        }

        makerWsClient.apply {
            assertMyTradesCreatedMessageReceived { msg ->
                assertEquals(5, msg.trades.size)
            }
            repeat(5) { assertMyOrderUpdatedMessageReceived() }
            assertBalancesMessageReceived()
            assertPricesMessageReceived(market.id) { msg ->
                assertEquals(
                    OHLC(
                        start = OHLCDuration.P5M.durationStart(Clock.System.now()),
                        open = BigDecimal("68400"),
                        high = BigDecimal("68400"),
                        low = BigDecimal("68400"),
                        close = BigDecimal("68400"),
                        duration = OHLCDuration.P5M,
                    ),
                    msg.ohlc.last(),
                )
            }
            assertLimitsMessageReceived(market, base = BigDecimal("0.182"), quote = BigDecimal("1718.888"))
                .also { wsMessage -> verifyApiReturnsSameLimits(makerApiClient, wsMessage) }
        }

        val takerOrders = takerApiClient.listOrders(emptyList(), market.id).orders
        assertEquals(1, takerOrders.count { it.status == OrderStatus.Filled })
        // should be 5 filled maker orders
        assertEquals(5, makerApiClient.listOrders(listOf(OrderStatus.Filled), market.id).orders.size)

        // now verify the trades

        val expectedAmounts = listOf("0.001", "0.002", "0.004", "0.005", "0.006").map { AssetAmount(baseSymbol, it) }.toSet()
        val trades = getTradesForOrders(takerOrders.map { it.id })
        val prices = trades.map { it.price }.toSet()

        assertEquals(5, trades.size)
        assertEquals(expectedAmounts, trades.map { AssetAmount(baseSymbol, it.amount) }.toSet())
        assertEquals(prices.size, 1)

        waitForSettlementToFinishWithForking(trades.map { it.id.value })

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

    @Test
    fun `cross chain trade execution`() {
        val market = btcbtc2Market
        val baseSymbol = btc
        val quoteSymbol = btc2

        val evmExchangeContractManager = EvmExchangeContractManager()

        val initialFeeAccountBalance = evmExchangeContractManager.getFeeBalance(quoteSymbol)

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
            subscribeToPrices = false,
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
            subscribeToPrices = false,
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
            assertMyLimitOrderCreatedMessageReceived(limitBuyOrderApiResponse)
            assertLimitsMessageReceived(market, base = BigDecimal("0.4"), quote = BigDecimal("0.4789212"))
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
            assertMyLimitOrderCreatedMessageReceived(limitSellOrderApiResponse)
            assertLimitsMessageReceived(market, base = BigDecimal("0.180"), quote = BigDecimal("0.4789212"))
        }

        // place a buy order and see it gets executed
        val marketBuyOrderApiResponse = takerApiClient.createMarketOrder(
            market,
            OrderSide.Buy,
            BigDecimal("0.05"),
            takerWallet,
        )

        takerWsClient.apply {
            assertMyMarketOrderCreatedMessageReceived(marketBuyOrderApiResponse)
            assertMyTradesCreatedMessageReceived(
                listOf(
                    MyExpectedTrade(
                        order = marketBuyOrderApiResponse,
                        price = (limitSellOrderApiResponse.order as CreateOrderApiRequest.Limit).price,
                        amount = AssetAmount(baseSymbol, marketBuyOrderApiResponse.order.amount.fixedAmount()),
                        fee = AssetAmount(quoteSymbol, "0.001002"),
                        settlementStatus = SettlementStatus.Pending,
                    ),
                ),
            )
            assertMyOrderUpdatedMessageReceived { msg ->
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
            assertMyTradesCreatedMessageReceived(
                listOf(
                    MyExpectedTrade(
                        order = limitSellOrderApiResponse,
                        price = (limitSellOrderApiResponse.order as CreateOrderApiRequest.Limit).price,
                        amount = AssetAmount(baseSymbol, "0.05"),
                        fee = AssetAmount(quoteSymbol, "0.000501"),
                        settlementStatus = SettlementStatus.Pending,
                    ),
                ),
            )
            assertMyOrderUpdatedMessageReceived { msg ->
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
            assertLimitsMessageReceived(market, base = BigDecimal("0.18"), quote = BigDecimal("0.5285202"))
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
        takerWsClient.assertMyTradesUpdatedMessageReceived { msg ->
            assertEquals(1, msg.trades.size)
            assertEquals(marketBuyOrderApiResponse.orderId, msg.trades[0].orderId)
            assertEquals(SettlementStatus.Completed, msg.trades[0].settlementStatus)
        }

        makerWsClient.assertBalancesMessageReceived(
            listOf(
                ExpectedBalance(makerStartingBaseBalance - baseQuantity),
                ExpectedBalance(makerStartingQuoteBalance + notional - makerFee),
            ),
        )
        makerWsClient.assertMyTradesUpdatedMessageReceived { msg ->
            assertEquals(1, msg.trades.size)
            assertEquals(limitSellOrderApiResponse.orderId, msg.trades[0].orderId)
            assertEquals(SettlementStatus.Completed, msg.trades[0].settlementStatus)
        }

        // place a sell order and see it gets executed
        val marketSellOrderApiResponse = takerApiClient.createMarketOrder(
            market,
            OrderSide.Sell,
            BigDecimal("0.012345"),
            takerWallet,
        )

        takerWsClient.apply {
            assertMyMarketOrderCreatedMessageReceived(marketSellOrderApiResponse)
            assertMyTradesCreatedMessageReceived(
                listOf(
                    MyExpectedTrade(
                        order = marketSellOrderApiResponse,
                        price = BigDecimal("0.999"),
                        amount = AssetAmount(baseSymbol, "0.012345"),
                        fee = AssetAmount(quoteSymbol, "0.0002466531"),
                        settlementStatus = SettlementStatus.Pending,
                    ),
                ),
            )
            assertMyOrderUpdatedMessageReceived { msg ->
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
            assertMyTradesCreatedMessageReceived(
                listOf(
                    MyExpectedTrade(
                        order = limitBuyOrderApiResponse,
                        price = (limitBuyOrderApiResponse.order as CreateOrderApiRequest.Limit).price,
                        amount = AssetAmount(baseSymbol, "0.012345"),
                        fee = AssetAmount(quoteSymbol, "0.00012332655"),
                        settlementStatus = SettlementStatus.Pending,
                    ),
                ),
            )
            assertMyOrderUpdatedMessageReceived { msg ->
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
            assertMyTradesUpdatedMessageReceived { msg ->
                assertEquals(1, msg.trades.size)
                assertEquals(marketSellOrderApiResponse.orderId, msg.trades[0].orderId)
                assertEquals(SettlementStatus.Completed, msg.trades[0].settlementStatus)
            }
        }

        makerWsClient.apply {
            assertLimitsMessageReceived(market, base = BigDecimal("0.192345"), quote = BigDecimal("0.5285202"))
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(makerStartingBaseBalance - baseQuantity + baseQuantity2),
                    ExpectedBalance(makerStartingQuoteBalance + (notional - makerFee) - (notional2 + makerFee2)),
                ),
            )
            assertMyTradesUpdatedMessageReceived { msg ->
                assertEquals(1, msg.trades.size)
                assertEquals(limitBuyOrderApiResponse.orderId, msg.trades[0].orderId)
                assertEquals(SettlementStatus.Completed, msg.trades[0].settlementStatus)
            }
        }

        // verify that fees have settled correctly on chain
        assertEquals(
            makerFee + takerFee + makerFee2 + takerFee2,
            evmExchangeContractManager.getFeeBalance(quoteSymbol) - initialFeeAccountBalance,
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
            subscribeToPrices = false,
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
            subscribeToPrices = false,
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
                createOrders = listOf("0.001", "0.002", "0.003").map {
                    makerWallet.signOrder(
                        CreateOrderApiRequest.Limit(
                            nonce = generateOrderNonce(),
                            marketId = market.id,
                            side = OrderSide.Sell,
                            amount = OrderAmount.Fixed(AssetAmount(baseSymbol, it).inFundamentalUnits),
                            price = BigDecimal("1.001"),
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    )
                },
                cancelOrders = listOf(),
            ),
        )

        assertEquals(3, createBatchLimitOrders.createdOrders.count())
        repeat(3) { makerWsClient.assertMyOrderCreatedMessageReceived() }
        makerWsClient.assertLimitsMessageReceived(market, base = BigDecimal("0.1940"), quote = BigDecimal("1.8"))

        val batchOrderResponse = makerApiClient.batchOrders(
            BatchOrdersApiRequest(
                marketId = market.id,
                createOrders = listOf("0.004", "0.005", "0.006").map {
                    makerWallet.signOrder(
                        CreateOrderApiRequest.Limit(
                            nonce = generateOrderNonce(),
                            marketId = market.id,
                            side = OrderSide.Sell,
                            amount = OrderAmount.Fixed(AssetAmount(baseSymbol, it).inFundamentalUnits),
                            price = BigDecimal("1.001"),
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    )
                },
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
        assertEquals(1, batchOrderResponse.canceledOrders.count { it.requestStatus == RequestStatus.Accepted })
        assertEquals(1, batchOrderResponse.canceledOrders.count { it.requestStatus == RequestStatus.Rejected })

        repeat(3) { makerWsClient.assertMyOrderCreatedMessageReceived() }
        makerWsClient.assertMyOrderUpdatedMessageReceived()
        makerWsClient.assertLimitsMessageReceived(market, base = BigDecimal("0.182"), quote = BigDecimal("1.8"))

        assertEquals(5, makerApiClient.listOrders().orders.count { it.status == OrderStatus.Open })

        // total BTC available is 0.001 + 0.002 + 0.004 + 0.005 + 0.006 = 0.018
        val takerOrderAmount = AssetAmount(baseSymbol, "0.018")
        // create a market orders that should match against the 5 limit orders
        takerApiClient.createMarketOrder(
            market,
            OrderSide.Buy,
            amount = takerOrderAmount.amount,
            takerWallet,
        )

        takerWsClient.apply {
            assertMyOrderCreatedMessageReceived()
            assertMyTradesCreatedMessageReceived { msg ->
                assertEquals(5, msg.trades.size)
            }
            assertMyOrderUpdatedMessageReceived()
            assertBalancesMessageReceived()
            assertLimitsMessageReceived(market, base = BigDecimal("0.018"), quote = BigDecimal("1.78162164"))
        }

        makerWsClient.apply {
            assertMyTradesCreatedMessageReceived { msg ->
                assertEquals(5, msg.trades.size)
            }
            repeat(5) { assertMyOrderUpdatedMessageReceived() }
            assertBalancesMessageReceived()
            assertLimitsMessageReceived(market, base = BigDecimal("0.182"), quote = BigDecimal("1.81783782"))
        }

        // should be 8 filled orders
        val takerOrders = takerApiClient.listOrders().orders
        assertEquals(1, takerOrders.count { it.status == OrderStatus.Filled })
        assertEquals(5, makerApiClient.listOrders().orders.count { it.status == OrderStatus.Filled })

        // now verify the trades

        val expectedAmounts = listOf("0.001", "0.002", "0.004", "0.005", "0.006").map { AssetAmount(baseSymbol, it) }.toSet()
        val trades = getTradesForOrders(takerOrders.map { it.id })
        val prices = trades.map { it.price }.toSet()

        assertEquals(5, trades.size)
        assertEquals(expectedAmounts, trades.map { AssetAmount(baseSymbol, it.amount) }.toSet())
        assertEquals(prices.size, 1)

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
}
