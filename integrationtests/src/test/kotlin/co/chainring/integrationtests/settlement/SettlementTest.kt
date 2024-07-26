package co.chainring.integrationtests.settlement

import co.chainring.apps.api.model.BatchOrdersApiRequest
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.OrderAmount
import co.chainring.apps.api.model.websocket.LastTrade
import co.chainring.apps.api.model.websocket.LastTradeDirection
import co.chainring.apps.api.model.websocket.OrderBook
import co.chainring.apps.api.model.websocket.OrderBookEntry
import co.chainring.apps.api.model.websocket.SubscriptionTopic
import co.chainring.apps.api.model.websocket.TradesUpdated
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.ExecutionRole
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.OrderStatus
import co.chainring.core.model.db.SettlementStatus
import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.model.db.WithdrawalStatus
import co.chainring.core.utils.generateOrderNonce
import co.chainring.core.utils.toFundamentalUnits
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.testutils.OrderBaseTest
import co.chainring.integrationtests.testutils.waitForFinalizedWithdrawal
import co.chainring.integrationtests.utils.AssetAmount
import co.chainring.integrationtests.utils.ExpectedBalance
import co.chainring.integrationtests.utils.ExpectedTrade
import co.chainring.integrationtests.utils.assertAmount
import co.chainring.integrationtests.utils.assertBalancesMessageReceived
import co.chainring.integrationtests.utils.assertContainsBalancesMessage
import co.chainring.integrationtests.utils.assertContainsLimitsMessage
import co.chainring.integrationtests.utils.assertContainsTradesUpdatedMessage
import co.chainring.integrationtests.utils.assertLimitOrderCreatedMessageReceived
import co.chainring.integrationtests.utils.assertLimitsMessageReceived
import co.chainring.integrationtests.utils.assertMarketOrderCreatedMessageReceived
import co.chainring.integrationtests.utils.assertMessagesReceived
import co.chainring.integrationtests.utils.assertOrderBookMessageReceived
import co.chainring.integrationtests.utils.assertOrderCreatedMessageReceived
import co.chainring.integrationtests.utils.assertOrderUpdatedMessageReceived
import co.chainring.integrationtests.utils.assertPricesMessageReceived
import co.chainring.integrationtests.utils.assertTradesCreatedMessageReceived
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import kotlin.test.Test

@ExtendWith(AppUnderTestRunner::class)
class SettlementTest : OrderBaseTest() {

    @Test
    fun `settlement success - net balance change is calculated from singe increments and decrements`() {
        val market = btcEthMarket
        val baseSymbol = btc
        val quoteSymbol = eth

        val (takerApiClient, takerWallet, takerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "1"),
                AssetAmount(quoteSymbol, "17"),
            ),
            deposits = listOf(
                AssetAmount(baseSymbol, "0.2"),
                AssetAmount(quoteSymbol, "3.4"),
            ),
        )

        val (makerApiClient, makerWallet, makerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "2"),
                AssetAmount(quoteSymbol, "34"),
            ),
            deposits = listOf(
                AssetAmount(baseSymbol, "1.5"),
                AssetAmount(quoteSymbol, "25"),
            ),
        )

        // setup market
        val limitBuyOrderApiResponse = makerApiClient.createLimitOrder(
            market,
            OrderSide.Buy,
            amount = BigDecimal("1"),
            price = BigDecimal("17.50"),
            makerWallet,
        )
        makerWsClient.assertLimitOrderCreatedMessageReceived(limitBuyOrderApiResponse)
        listOf(makerWsClient, takerWsClient).forEach { wsClient ->
            wsClient.assertOrderBookMessageReceived(market.id) {}
        }
        makerWsClient.assertLimitsMessageReceived()

        val limitSellOrderApiResponse = makerApiClient.createLimitOrder(
            market,
            OrderSide.Sell,
            amount = BigDecimal("1"),
            price = BigDecimal("17.6"),
            makerWallet,
        )
        makerWsClient.assertLimitOrderCreatedMessageReceived(limitSellOrderApiResponse)
        listOf(makerWsClient, takerWsClient).forEach { wsClient ->
            wsClient.assertOrderBookMessageReceived(market.id) {}
        }
        makerWsClient.assertLimitsMessageReceived()

        // Send order batches that will result im multiple buy/sell trades.
        // The amounts on each side separately would result into insufficient balance.
        fun marketOrder(side: OrderSide, amount: BigDecimal): CreateOrderApiRequest.Market {
            return CreateOrderApiRequest.Market(
                nonce = generateOrderNonce(),
                marketId = market.id,
                side = side,
                amount = amount.let { OrderAmount.Fixed(it.toFundamentalUnits(market.baseDecimals)) },
                signature = EvmSignature.emptySignature(),
                verifyingChainId = ChainId.empty,
            ).let { takerWallet.signOrder(it) }
        }

        val batches = (1..10).toList()
        val createOrderApiResponses = batches.map {
            val response = takerApiClient.batchOrders(
                apiRequest = BatchOrdersApiRequest(
                    marketId = market.id,
                    createOrders = listOf(
                        marketOrder(OrderSide.Buy, BigDecimal("0.1")),
                        marketOrder(OrderSide.Sell, BigDecimal("0.1")),
                    ),
                    cancelOrders = listOf(),
                ),
            )

            response.createdOrders
        }.flatten()

        takerWsClient.apply {
            repeat(batches.size) {
                repeat(2) { assertOrderCreatedMessageReceived() }
                assertTradesCreatedMessageReceived {
                    assertEquals(setOf(SettlementStatus.Pending), it.trades.map { trade -> trade.settlementStatus }.toSet())
                }
                repeat(2) {
                    assertOrderUpdatedMessageReceived { msg ->
                        assertEquals(OrderStatus.Filled, msg.order.status)
                    }
                }
                assertBalancesMessageReceived {}
                assertOrderBookMessageReceived(market.id) {}
                repeat(2) {
                    assertPricesMessageReceived(market.id) {}
                }
                assertLimitsMessageReceived()
            }
        }

        // collect all trades and wait until settled
        val tradeIds = getTradesForOrders(createOrderApiResponses.map { it.orderId }).map { it.id.value }
        waitForSettlementToFinish(tradeIds, SettlementStatus.Completed)

        makerWsClient.close()
        takerWsClient.close()
    }

    @Test
    fun `settlement failure - all trades in batch fail`() {
        val market = btcEthMarket
        val baseSymbol = btc
        val quoteSymbol = eth

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
        makerWsClient.assertLimitOrderCreatedMessageReceived(limitBuyOrderApiResponse)

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
        makerWsClient.assertLimitsMessageReceived(market, base = BigDecimal("0"), quote = BigDecimal("0.58196"))

        // place a sell order
        val marketSellOrderApiResponse = takerApiClient.createMarketOrder(
            market,
            OrderSide.Sell,
            BigDecimal("0.08"),
            takerWallet,
        )

        takerWsClient.apply {
            assertMarketOrderCreatedMessageReceived(marketSellOrderApiResponse)
            assertTradesCreatedMessageReceived(
                listOf(
                    ExpectedTrade(
                        order = marketSellOrderApiResponse,
                        price = BigDecimal("17.55"),
                        amount = AssetAmount(baseSymbol, marketSellOrderApiResponse.order.amount.fixedAmount()),
                        fee = AssetAmount(quoteSymbol, "0.02808"),
                        settlementStatus = SettlementStatus.Pending,
                    ),
                ),
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
            assertTradesCreatedMessageReceived(
                listOf(
                    ExpectedTrade(
                        order = limitBuyOrderApiResponse,
                        price = BigDecimal("17.55"),
                        amount = AssetAmount(baseSymbol, "0.08"),
                        fee = AssetAmount(quoteSymbol, "0.01404"),
                        settlementStatus = SettlementStatus.Pending,
                    ),
                ),
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
        val trade = getTradesForOrders(listOf(marketSellOrderApiResponse.orderId)).first()

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
                assertContainsTradesUpdatedMessage(messages) { msg ->
                    assertEquals(1, msg.trades.size)
                    assertEquals(marketSellOrderApiResponse.orderId, msg.trades[0].orderId)
                    assertEquals(SettlementStatus.Failed, msg.trades[0].settlementStatus)
                    assertEquals("Insufficient Balance", msg.trades[0].error)
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
                assertContainsTradesUpdatedMessage(messages) { msg ->
                    assertEquals(1, msg.trades.size)
                    assertEquals(limitBuyOrderApiResponse.orderId, msg.trades[0].orderId)
                    assertEquals(SettlementStatus.Failed, msg.trades[0].settlementStatus)
                    assertEquals("Insufficient Balance", msg.trades[0].error)
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
    fun `settlement failure - some trades in batch fail, some succeed`() {
        val market = btcEthMarket
        val baseSymbol = btc
        val quoteSymbol = eth

        val (takerApiClient, takerWallet, takerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "0.5"),
                AssetAmount(quoteSymbol, "10"),
            ),
            deposits = listOf(
                AssetAmount(quoteSymbol, "10"),
            ),
            subscribeToOrderBook = false,
            subscribeToOrderPrices = false,
        )

        val (makerApiClient, makerWallet, makerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "0.1"),
            ),
            deposits = listOf(
                AssetAmount(baseSymbol, "0.05"),
            ),
            subscribeToOrderBook = false,
            subscribeToOrderPrices = false,
        )

        val (maker2ApiClient, maker2Wallet, maker2WsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "0.1"),
            ),
            deposits = listOf(
                AssetAmount(baseSymbol, "0.03"),
            ),
            subscribeToOrderBook = false,
            subscribeToOrderPrices = false,
        )

        // starting onchain balances
        val makerStartingBaseBalance = makerWallet.getExchangeBalance(baseSymbol)
        val makerStartingQuoteBalance = makerWallet.getExchangeBalance(quoteSymbol)
        val maker2StartingBaseBalance = maker2Wallet.getExchangeBalance(baseSymbol)
        val baseSymbolMakerTradeAmount = AssetAmount(baseSymbol, "0.03")
        val makerNotionalMinusFee = AssetAmount(quoteSymbol, "0.51975")

        val baseWithdrawalAmount = AssetAmount(baseSymbol, "0.02")

        val pendingBaseWithdrawal = makerApiClient.createWithdrawal(makerWallet.signWithdraw(baseSymbol.name, baseWithdrawalAmount.inFundamentalUnits)).withdrawal
        assertEquals(WithdrawalStatus.Pending, pendingBaseWithdrawal.status)

        makerWsClient.apply {
            assertBalancesMessageReceived()
            assertLimitsMessageReceived(market, base = makerStartingBaseBalance - baseWithdrawalAmount, quote = makerStartingQuoteBalance)
        }

        waitForFinalizedWithdrawal(pendingBaseWithdrawal.id)

        makerWsClient.apply {
            assertBalancesMessageReceived()
        }

        // hack to resubmit the same transaction again bypassing the sequencer. This way taker will not have a
        // sufficient on chain balance for the order so settlement will fail.
        transaction {
            WithdrawalEntity[pendingBaseWithdrawal.id].status = WithdrawalStatus.Sequenced
        }

        waitForFinalizedWithdrawal(pendingBaseWithdrawal.id)

        makerWsClient.apply {
            assertBalancesMessageReceived()
        }

        // place a limit order
        val limitSellOrderApiResponse = makerApiClient.createLimitOrder(
            market,
            OrderSide.Sell,
            amount = BigDecimal("0.03"),
            price = BigDecimal("17.50"),
            makerWallet,
        )
        makerWsClient.apply {
            assertLimitOrderCreatedMessageReceived(limitSellOrderApiResponse)
            assertLimitsMessageReceived()
        }

        val limitSellOrder2ApiResponse = maker2ApiClient.createLimitOrder(
            market,
            OrderSide.Sell,
            amount = BigDecimal("0.03"),
            price = BigDecimal("17.50"),
            maker2Wallet,
        )
        maker2WsClient.apply {
            assertLimitOrderCreatedMessageReceived(limitSellOrder2ApiResponse)
            assertLimitsMessageReceived()
        }

        // place a sell order
        val marketBuyOrderApiResponse = takerApiClient.createMarketOrder(
            market,
            OrderSide.Buy,
            BigDecimal("0.06"),
            takerWallet,
        )

        takerWsClient.apply {
            assertMarketOrderCreatedMessageReceived(marketBuyOrderApiResponse)
            assertTradesCreatedMessageReceived(
                listOf(
                    ExpectedTrade(
                        order = marketBuyOrderApiResponse,
                        price = BigDecimal("17.50"),
                        amount = baseSymbolMakerTradeAmount,
                        fee = AssetAmount(quoteSymbol, "0.0105"),
                        settlementStatus = SettlementStatus.Pending,
                    ),
                    ExpectedTrade(
                        order = marketBuyOrderApiResponse,
                        price = BigDecimal("17.50"),
                        amount = baseSymbolMakerTradeAmount,
                        fee = AssetAmount(quoteSymbol, "0.0105"),
                        settlementStatus = SettlementStatus.Pending,
                    ),
                ),
            )
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Filled, msg.order.status)
                assertEquals(2, msg.order.executions.size)
                assertAmount(baseSymbolMakerTradeAmount, msg.order.executions[0].amount)
                assertAmount(AssetAmount(quoteSymbol, "17.500"), msg.order.executions[0].price)
                assertAmount(baseSymbolMakerTradeAmount, msg.order.executions[1].amount)
                assertAmount(AssetAmount(quoteSymbol, "17.500"), msg.order.executions[1].price)
                assertEquals(ExecutionRole.Taker, msg.order.executions[0].role)
                assertEquals(ExecutionRole.Taker, msg.order.executions[1].role)
            }
            assertBalancesMessageReceived()
        }

        makerWsClient.apply {
            assertTradesCreatedMessageReceived(
                listOf(
                    ExpectedTrade(
                        order = limitSellOrderApiResponse,
                        price = BigDecimal("17.50"),
                        amount = baseSymbolMakerTradeAmount,
                        fee = AssetAmount(quoteSymbol, "0.00525"),
                        settlementStatus = SettlementStatus.Pending,
                    ),
                ),
            )
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Filled, msg.order.status)
                assertEquals(1, msg.order.executions.size)
                assertAmount(AssetAmount(baseSymbol, "0.03"), msg.order.executions[0].amount)
                assertAmount(AssetAmount(quoteSymbol, "17.500"), msg.order.executions[0].price)
                assertEquals(ExecutionRole.Maker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived()
        }

        maker2WsClient.apply {
            assertTradesCreatedMessageReceived(
                listOf(
                    ExpectedTrade(
                        order = limitSellOrder2ApiResponse,
                        price = BigDecimal("17.50"),
                        amount = baseSymbolMakerTradeAmount,
                        fee = AssetAmount(quoteSymbol, "0.00525"),
                        settlementStatus = SettlementStatus.Pending,
                    ),
                ),
            )
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Filled, msg.order.status)
                assertEquals(1, msg.order.executions.size)
                assertAmount(AssetAmount(baseSymbol, "0.03"), msg.order.executions[0].amount)
                assertAmount(AssetAmount(quoteSymbol, "17.500"), msg.order.executions[0].price)
                assertEquals(ExecutionRole.Maker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived()
        }

        val trades = getTradesForOrders(listOf(marketBuyOrderApiResponse.orderId))
        assertEquals(2, trades.size)

        takerWsClient.apply {
            assertLimitsMessageReceived()
        }

        makerWsClient.apply {
            assertLimitsMessageReceived()
        }

        assertAmount(AssetAmount(baseSymbol, "0.03"), trades[0].amount)
        assertAmount(AssetAmount(quoteSymbol, "17.500"), trades[0].price)
        waitForSettlementToFinish(listOf(trades[0].id.value), SettlementStatus.Failed)

        assertAmount(AssetAmount(baseSymbol, "0.03"), trades[1].amount)
        assertAmount(AssetAmount(quoteSymbol, "17.500"), trades[1].price)
        waitForSettlementToFinish(listOf(trades[1].id.value))

        var failedTradeUpdates = 0
        var completedTradeUpdates = 0
        takerWsClient.apply {
            assertMessagesReceived(5) { messages ->
                println(messages)
                messages.filter { it.topic == SubscriptionTopic.Trades && it.data is TradesUpdated }.map { it.data as TradesUpdated }.forEach { msg ->
                    msg.trades.forEach { trade ->
                        when (trade.settlementStatus) {
                            SettlementStatus.Failed -> {
                                failedTradeUpdates++
                                assertEquals("Insufficient Balance", trade.error)
                            }
                            SettlementStatus.Completed -> completedTradeUpdates++
                            else -> {}
                        }
                    }
                }
                assertContainsLimitsMessage(messages, market, base = baseSymbolMakerTradeAmount, quote = AssetAmount(quoteSymbol, "9.4645"))
            }
        }
        assertEquals(1, failedTradeUpdates)
        assertEquals(1, completedTradeUpdates)

        makerWsClient.apply {
            assertMessagesReceived(3) { messages ->
                assertContainsTradesUpdatedMessage(messages) { msg ->
                    assertEquals(1, msg.trades.size)
                    assertEquals(limitSellOrderApiResponse.orderId, msg.trades[0].orderId)
                    assertEquals(SettlementStatus.Failed, msg.trades[0].settlementStatus)
                    assertEquals("Insufficient Balance", msg.trades[0].error)
                }
                assertContainsBalancesMessage(
                    messages,
                    listOf(
                        ExpectedBalance(baseSymbol, total = AssetAmount(baseSymbol, "0.01"), available = makerStartingBaseBalance - baseWithdrawalAmount),
                        ExpectedBalance(makerStartingQuoteBalance),
                    ),
                )
                assertContainsLimitsMessage(messages, market, base = makerStartingBaseBalance - baseWithdrawalAmount, quote = makerStartingQuoteBalance)
            }
        }

        maker2WsClient.apply {
            assertMessagesReceived(3) { messages ->
                assertContainsTradesUpdatedMessage(messages) { msg ->
                    assertEquals(1, msg.trades.size)
                    assertEquals(limitSellOrder2ApiResponse.orderId, msg.trades[0].orderId)
                    assertEquals(SettlementStatus.Completed, msg.trades[0].settlementStatus)
                }
                assertContainsBalancesMessage(
                    messages,
                    listOf(
                        ExpectedBalance(baseSymbol, total = maker2StartingBaseBalance - baseSymbolMakerTradeAmount, available = maker2StartingBaseBalance - baseSymbolMakerTradeAmount),
                        ExpectedBalance(quoteSymbol, total = makerNotionalMinusFee, available = makerNotionalMinusFee),
                    ),
                )
                assertContainsLimitsMessage(messages, market, base = maker2StartingBaseBalance - baseSymbolMakerTradeAmount, quote = makerNotionalMinusFee)
            }
        }

        makerWsClient.close()
        maker2WsClient.close()
    }

    @Test
    fun `cross chain settlement failure - all trades in batch fail`() {
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
            assertLimitsMessageReceived(market, base = BigDecimal("0"), quote = BigDecimal("0.4692808"))
        }

        // place a sell order
        val marketSellOrderApiResponse = takerApiClient.createMarketOrder(
            market,
            OrderSide.Sell,
            BigDecimal("0.08"),
            takerWallet,
        )

        takerWsClient.apply {
            assertMarketOrderCreatedMessageReceived(marketSellOrderApiResponse)
            assertTradesCreatedMessageReceived(
                listOf(
                    ExpectedTrade(
                        order = marketSellOrderApiResponse,
                        price = BigDecimal("0.999"),
                        amount = AssetAmount(baseSymbol, marketSellOrderApiResponse.order.amount.fixedAmount()),
                        fee = AssetAmount(quoteSymbol, "0.0015984"),
                        settlementStatus = SettlementStatus.Pending,
                    ),
                ),
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
            assertTradesCreatedMessageReceived(
                listOf(
                    ExpectedTrade(
                        order = limitBuyOrderApiResponse,
                        price = BigDecimal("0.999"),
                        amount = AssetAmount(baseSymbol, "0.08"),
                        fee = AssetAmount(quoteSymbol, "0.0007992"),
                        settlementStatus = SettlementStatus.Pending,
                    ),
                ),
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

        val trade = getTradesForOrders(listOf(marketSellOrderApiResponse.orderId)).first()

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
                assertContainsTradesUpdatedMessage(messages) { msg ->
                    assertEquals(1, msg.trades.size)
                    assertEquals(marketSellOrderApiResponse.orderId, msg.trades[0].orderId)
                    assertEquals(SettlementStatus.Failed, msg.trades[0].settlementStatus)
                    assertEquals("Insufficient Balance", msg.trades[0].error)
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
                assertContainsTradesUpdatedMessage(messages) { msg ->
                    assertEquals(1, msg.trades.size)
                    assertEquals(limitBuyOrderApiResponse.orderId, msg.trades[0].orderId)
                    assertEquals(SettlementStatus.Failed, msg.trades[0].settlementStatus)
                    assertEquals("Insufficient Balance", msg.trades[0].error)
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

    @Test
    fun `cross chain settlement failure - some trades in batch fail, some succeed`() {
        val market = btcbtc2Market
        val baseSymbol = btc
        val quoteSymbol = btc2

        val (takerApiClient, takerWallet, takerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "0.22"),
                AssetAmount(quoteSymbol, "0.2"),
            ),
            deposits = listOf(
                AssetAmount(quoteSymbol, "0.1"),
            ),
            subscribeToOrderBook = false,
            subscribeToOrderPrices = false,
        )

        val (makerApiClient, makerWallet, makerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "0.1"),
            ),
            deposits = listOf(
                AssetAmount(baseSymbol, "0.05"),
            ),
            subscribeToOrderBook = false,
            subscribeToOrderPrices = false,
        )

        val (maker2ApiClient, maker2Wallet, maker2WsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "0.1"),
            ),
            deposits = listOf(
                AssetAmount(baseSymbol, "0.03"),
            ),
            subscribeToOrderBook = false,
            subscribeToOrderPrices = false,
        )

        // starting onchain balances
        val makerStartingBaseBalance = makerWallet.getExchangeBalance(baseSymbol)
        val makerStartingQuoteBalance = makerWallet.getExchangeBalance(quoteSymbol)
        val maker2StartingBaseBalance = maker2Wallet.getExchangeBalance(baseSymbol)
        val baseSymbolMakerTradeAmount = AssetAmount(baseSymbol, "0.03")
        val makerNotionalMinusFee = AssetAmount(quoteSymbol, "0.0296703")

        val baseWithdrawalAmount = AssetAmount(baseSymbol, "0.02")

        val pendingBaseWithdrawal = makerApiClient.createWithdrawal(makerWallet.signWithdraw(baseSymbol.name, baseWithdrawalAmount.inFundamentalUnits)).withdrawal
        assertEquals(WithdrawalStatus.Pending, pendingBaseWithdrawal.status)

        makerWsClient.apply {
            assertBalancesMessageReceived()
            assertLimitsMessageReceived(market, base = makerStartingBaseBalance - baseWithdrawalAmount, quote = makerStartingQuoteBalance)
        }

        waitForFinalizedWithdrawal(pendingBaseWithdrawal.id)

        makerWsClient.apply {
            assertBalancesMessageReceived()
        }

        // hack to resubmit the same transaction again bypassing the sequencer. This way taker will not have a
        // sufficient on chain balance for the order so settlement will fail.
        transaction {
            WithdrawalEntity[pendingBaseWithdrawal.id].status = WithdrawalStatus.Sequenced
        }

        waitForFinalizedWithdrawal(pendingBaseWithdrawal.id)

        makerWsClient.apply {
            assertBalancesMessageReceived()
        }

        // place a limit order
        val limitSellOrderApiResponse = makerApiClient.createLimitOrder(
            market,
            OrderSide.Sell,
            amount = BigDecimal("0.03"),
            price = BigDecimal("0.999"),
            makerWallet,
        )
        makerWsClient.apply {
            assertLimitOrderCreatedMessageReceived(limitSellOrderApiResponse)
            assertLimitsMessageReceived()
        }

        val limitSellOrder2ApiResponse = maker2ApiClient.createLimitOrder(
            market,
            OrderSide.Sell,
            amount = BigDecimal("0.03"),
            price = BigDecimal("0.999"),
            maker2Wallet,
        )
        maker2WsClient.apply {
            assertLimitOrderCreatedMessageReceived(limitSellOrder2ApiResponse)
            assertLimitsMessageReceived()
        }

        // place a sell order
        val marketBuyOrderApiResponse = takerApiClient.createMarketOrder(
            market,
            OrderSide.Buy,
            BigDecimal("0.06"),
            takerWallet,
        )

        takerWsClient.apply {
            assertMarketOrderCreatedMessageReceived(marketBuyOrderApiResponse)
            assertTradesCreatedMessageReceived(
                listOf(
                    ExpectedTrade(
                        order = marketBuyOrderApiResponse,
                        price = BigDecimal("0.999"),
                        amount = baseSymbolMakerTradeAmount,
                        fee = AssetAmount(quoteSymbol, "0.0005994"),
                        settlementStatus = SettlementStatus.Pending,
                    ),
                    ExpectedTrade(
                        order = marketBuyOrderApiResponse,
                        price = BigDecimal("0.999"),
                        amount = baseSymbolMakerTradeAmount,
                        fee = AssetAmount(quoteSymbol, "0.0005994"),
                        settlementStatus = SettlementStatus.Pending,
                    ),
                ),
            )
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Filled, msg.order.status)
                assertEquals(2, msg.order.executions.size)
                assertAmount(baseSymbolMakerTradeAmount, msg.order.executions[0].amount)
                assertAmount(AssetAmount(quoteSymbol, "0.999"), msg.order.executions[0].price)
                assertAmount(baseSymbolMakerTradeAmount, msg.order.executions[1].amount)
                assertAmount(AssetAmount(quoteSymbol, "0.999"), msg.order.executions[1].price)
                assertEquals(ExecutionRole.Taker, msg.order.executions[0].role)
                assertEquals(ExecutionRole.Taker, msg.order.executions[1].role)
            }
            assertBalancesMessageReceived()
        }

        makerWsClient.apply {
            assertTradesCreatedMessageReceived(
                listOf(
                    ExpectedTrade(
                        order = limitSellOrderApiResponse,
                        price = BigDecimal("0.999"),
                        amount = baseSymbolMakerTradeAmount,
                        fee = AssetAmount(quoteSymbol, "0.0002997"),
                        settlementStatus = SettlementStatus.Pending,
                    ),
                ),
            )
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Filled, msg.order.status)
                assertEquals(1, msg.order.executions.size)
                assertAmount(baseSymbolMakerTradeAmount, msg.order.executions[0].amount)
                assertAmount(AssetAmount(quoteSymbol, "0.999"), msg.order.executions[0].price)
                assertEquals(ExecutionRole.Maker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived()
        }

        maker2WsClient.apply {
            assertTradesCreatedMessageReceived(
                listOf(
                    ExpectedTrade(
                        order = limitSellOrder2ApiResponse,
                        price = BigDecimal("0.999"),
                        amount = baseSymbolMakerTradeAmount,
                        fee = AssetAmount(quoteSymbol, "0.0002997"),
                        settlementStatus = SettlementStatus.Pending,
                    ),
                ),
            )
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Filled, msg.order.status)
                assertEquals(1, msg.order.executions.size)
                assertAmount(AssetAmount(baseSymbol, "0.03"), msg.order.executions[0].amount)
                assertAmount(AssetAmount(quoteSymbol, "0.999"), msg.order.executions[0].price)
                assertEquals(ExecutionRole.Maker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived()
        }

        val trades = getTradesForOrders(listOf(marketBuyOrderApiResponse.orderId))
        assertEquals(2, trades.size)

        takerWsClient.apply {
            assertLimitsMessageReceived()
        }

        makerWsClient.apply {
            assertLimitsMessageReceived()
        }

        assertAmount(AssetAmount(baseSymbol, "0.03"), trades[0].amount)
        assertAmount(AssetAmount(quoteSymbol, "0.999"), trades[0].price)
        waitForSettlementToFinish(listOf(trades[0].id.value), SettlementStatus.Failed)

        assertAmount(AssetAmount(baseSymbol, "0.03"), trades[1].amount)
        assertAmount(AssetAmount(quoteSymbol, "0.999"), trades[1].price)
        waitForSettlementToFinish(listOf(trades[1].id.value))

        var failedTradeUpdates = 0
        var completedTradeUpdates = 0
        takerWsClient.apply {
            assertMessagesReceived(5) { messages ->
                println(messages)
                messages.filter { it.topic == SubscriptionTopic.Trades && it.data is TradesUpdated }.map { it.data as TradesUpdated }.forEach { msg ->
                    msg.trades.forEach { trade ->
                        when (trade.settlementStatus) {
                            SettlementStatus.Failed -> {
                                failedTradeUpdates++
                                assertEquals("Insufficient Balance", trade.error)
                            }
                            SettlementStatus.Completed -> completedTradeUpdates++
                            else -> {}
                        }
                    }
                }
                assertContainsLimitsMessage(messages, market, base = baseSymbolMakerTradeAmount, quote = AssetAmount(quoteSymbol, "0.0694306"))
            }
        }
        assertEquals(1, failedTradeUpdates)
        assertEquals(1, completedTradeUpdates)

        makerWsClient.apply {
            assertMessagesReceived(3) { messages ->
                assertContainsTradesUpdatedMessage(messages) { msg ->
                    assertEquals(1, msg.trades.size)
                    assertEquals(limitSellOrderApiResponse.orderId, msg.trades[0].orderId)
                    assertEquals(SettlementStatus.Failed, msg.trades[0].settlementStatus)
                    assertEquals("Insufficient Balance", msg.trades[0].error)
                }
                assertContainsBalancesMessage(
                    messages,
                    listOf(
                        ExpectedBalance(baseSymbol, total = AssetAmount(baseSymbol, "0.01"), available = makerStartingBaseBalance - baseWithdrawalAmount),
                        ExpectedBalance(makerStartingQuoteBalance),
                    ),
                )
                assertContainsLimitsMessage(messages, market, base = makerStartingBaseBalance - baseWithdrawalAmount, quote = makerStartingQuoteBalance)
            }
        }

        maker2WsClient.apply {
            assertMessagesReceived(3) { messages ->
                assertContainsTradesUpdatedMessage(messages) { msg ->
                    assertEquals(1, msg.trades.size)
                    assertEquals(limitSellOrder2ApiResponse.orderId, msg.trades[0].orderId)
                    assertEquals(SettlementStatus.Completed, msg.trades[0].settlementStatus)
                }
                assertContainsBalancesMessage(
                    messages,
                    listOf(
                        ExpectedBalance(baseSymbol, total = maker2StartingBaseBalance - baseSymbolMakerTradeAmount, available = maker2StartingBaseBalance - baseSymbolMakerTradeAmount),
                        ExpectedBalance(quoteSymbol, total = makerNotionalMinusFee, available = makerNotionalMinusFee),
                    ),
                )
                assertContainsLimitsMessage(messages, market, base = maker2StartingBaseBalance - baseSymbolMakerTradeAmount, quote = makerNotionalMinusFee)
            }
        }

        makerWsClient.close()
        maker2WsClient.close()
        waitForSettlementBatchToFinish()
    }

    @Test
    fun `settlement failure - manually rollback trade`() {
        Assumptions.assumeTrue((System.getenv("INTEGRATION_RUN") ?: "0") != "1")
        val market = btcEthMarket
        val baseSymbol = btc
        val quoteSymbol = eth

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
            subscribeToOrderBook = false,
            subscribeToOrderPrices = false,
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
            subscribeToOrderBook = false,
            subscribeToOrderPrices = false,
        )

        val makerStartingBaseBalance = makerWallet.getExchangeBalance(baseSymbol)
        val makerStartingQuoteBalance = makerWallet.getExchangeBalance(quoteSymbol)
        val takerStartingBaseBalance = takerWallet.getExchangeBalance(baseSymbol)
        val takerStartingQuoteBalance = takerWallet.getExchangeBalance(quoteSymbol)

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
            assertLimitsMessageReceived(market, base = BigDecimal("0"), quote = BigDecimal("0.58196"))
        }

        // place a sell order
        val marketSellOrderApiResponse = takerApiClient.createMarketOrder(
            market,
            OrderSide.Sell,
            BigDecimal("0.08"),
            takerWallet,
        )
        // wait for trade and mark as pending fail.
        val manuallyRolledBack = waitForPendingTradeAndMarkFailed(marketSellOrderApiResponse.orderId)

        takerWsClient.apply {
            assertMarketOrderCreatedMessageReceived(marketSellOrderApiResponse)
            assertTradesCreatedMessageReceived(
                listOf(
                    ExpectedTrade(
                        order = marketSellOrderApiResponse,
                        price = BigDecimal("17.55"),
                        amount = AssetAmount(baseSymbol, marketSellOrderApiResponse.order.amount.fixedAmount()),
                        fee = AssetAmount(quoteSymbol, "0.02808"),
                        settlementStatus = SettlementStatus.Pending,
                    ),
                ),
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
                    ExpectedBalance(baseSymbol, total = BigDecimal("0.1"), available = BigDecimal("0.02")),
                    ExpectedBalance(quoteSymbol, total = BigDecimal("2.0"), available = BigDecimal("3.37592")),
                ),
            )
        }

        makerWsClient.apply {
            assertTradesCreatedMessageReceived(
                listOf(
                    ExpectedTrade(
                        order = limitBuyOrderApiResponse,
                        price = BigDecimal("17.55"),
                        amount = AssetAmount(baseSymbol, "0.08"),
                        fee = AssetAmount(quoteSymbol, "0.01404"),
                        settlementStatus = SettlementStatus.Pending,
                    ),
                ),
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

        val trade = getTradesForOrders(listOf(marketSellOrderApiResponse.orderId)).first()

        takerWsClient.apply {
            assertLimitsMessageReceived()
        }
        makerWsClient.apply {
            assertLimitsMessageReceived()
        }

        if (manuallyRolledBack) {
            waitForSettlementToFinish(listOf(trade.id.value), SettlementStatus.Failed)

            takerWsClient.apply {
                assertMessagesReceived(3) { messages ->
                    assertContainsTradesUpdatedMessage(messages) { msg ->
                        assertEquals(1, msg.trades.size)
                        assertEquals(marketSellOrderApiResponse.orderId, msg.trades[0].orderId)
                        assertEquals(SettlementStatus.Failed, msg.trades[0].settlementStatus)
                        assertEquals("Manually Rolled Back", msg.trades[0].error)
                    }
                    assertContainsBalancesMessage(
                        messages,
                        listOf(
                            ExpectedBalance(baseSymbol, total = takerStartingBaseBalance, available = takerStartingBaseBalance),
                            ExpectedBalance(takerStartingQuoteBalance),
                        ),
                    )
                }
            }

            makerWsClient.apply {
                assertMessagesReceived(3) { messages ->
                    assertContainsTradesUpdatedMessage(messages) { msg ->
                        assertEquals(1, msg.trades.size)
                        assertEquals(limitBuyOrderApiResponse.orderId, msg.trades[0].orderId)
                        assertEquals(SettlementStatus.Failed, msg.trades[0].settlementStatus)
                        assertEquals("Manually Rolled Back", msg.trades[0].error)
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
        } else {
            waitForSettlementToFinish(listOf(trade.id.value), SettlementStatus.Completed)
        }
        takerWsClient.close()
        makerWsClient.close()
    }

    @Test
    fun `settlements and withdrawals properly sequenced`() {
        val market = btcEthMarket
        val baseSymbol = btc
        val quoteSymbol = eth

        val (takerApiClient, takerWallet, takerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "0.5"),
            ),
            deposits = listOf(
                AssetAmount(baseSymbol, "0.4"),
            ),
            subscribeToOrderBook = false,
            subscribeToOrderPrices = false,
        )

        val (makerApiClient, makerWallet, makerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "0.5"),
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
        val makerStartingQuoteBalance = makerWallet.getExchangeBalance(quoteSymbol)
        val takerStartingBaseBalance = takerWallet.getExchangeBalance(baseSymbol)
        val takerStartingQuoteBalance = takerWallet.getExchangeBalance(quoteSymbol)
        val takerQuoteQuantity = BigDecimal("1.7199")
        val makerQuoteAmount = BigDecimal("1.77255")

        // place a limit order
        val limitBuyOrderApiResponse = makerApiClient.createLimitOrder(
            market,
            OrderSide.Buy,
            amount = BigDecimal("0.25"),
            price = BigDecimal("17.55"),
            makerWallet,
        )
        makerWsClient.apply {
            assertLimitOrderCreatedMessageReceived(limitBuyOrderApiResponse)
            assertLimitsMessageReceived()
        }

        val orderAmount = BigDecimal("0.1")

        // place a sell order
        val marketSellOrderApiResponse = takerApiClient.createMarketOrder(
            market,
            OrderSide.Sell,
            orderAmount,
            takerWallet,
        )
        // withdraw the quote assets the taker will receive for this trade even though not settled yet
        // this will only work on chain if the trade is settled before the withdrawal.
        val pendingWithdrawal = takerApiClient.createWithdrawal(
            takerWallet.signWithdraw(
                quoteSymbol.name,
                takerQuoteQuantity.toFundamentalUnits(quoteSymbol.decimals),
            ),
        ).withdrawal

        takerWsClient.apply {
            assertMarketOrderCreatedMessageReceived(marketSellOrderApiResponse)
            assertTradesCreatedMessageReceived(
                listOf(
                    ExpectedTrade(
                        order = marketSellOrderApiResponse,
                        price = BigDecimal("17.55"),
                        amount = AssetAmount(baseSymbol, marketSellOrderApiResponse.order.amount.fixedAmount()),
                        fee = AssetAmount(quoteSymbol, "0.0351"),
                        settlementStatus = SettlementStatus.Pending,
                    ),
                ),
            )
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Filled, msg.order.status)
                assertEquals(1, msg.order.executions.size)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(
                        baseSymbol,
                        total = takerStartingBaseBalance.amount,
                        available = takerStartingBaseBalance.amount - orderAmount,
                    ),
                    ExpectedBalance(
                        quoteSymbol,
                        total = takerStartingQuoteBalance.amount,
                        available = takerStartingQuoteBalance.amount + takerQuoteQuantity,
                    ),
                ),
            )
        }

        // withdrawal updates
        takerWsClient.apply {
            assertLimitsMessageReceived()
            assertBalancesMessageReceived()
        }

        makerWsClient.apply {
            assertTradesCreatedMessageReceived(
                listOf(
                    ExpectedTrade(
                        order = limitBuyOrderApiResponse,
                        price = BigDecimal("17.55"),
                        amount = AssetAmount(baseSymbol, "0.1"),
                        fee = AssetAmount(quoteSymbol, "0.01755"),
                        settlementStatus = SettlementStatus.Pending,
                    ),
                ),
            )
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Partial, msg.order.status)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(
                        baseSymbol,
                        total = makerStartingBaseBalance.amount,
                        available = makerStartingBaseBalance.amount + orderAmount,
                    ),
                    ExpectedBalance(
                        quoteSymbol,
                        total = makerStartingQuoteBalance.amount,
                        available = makerStartingQuoteBalance.amount - makerQuoteAmount,
                    ),
                ),
            )
        }

        val trade = getTradesForOrders(listOf(marketSellOrderApiResponse.orderId)).first()
        listOf(takerWsClient, makerWsClient).forEach {
            it.assertLimitsMessageReceived()
        }
        waitForSettlementToFinish(listOf(trade.id.value))

        takerWsClient.apply {
            assertMessagesReceived(2) { messages ->
                assertContainsTradesUpdatedMessage(messages) { msg ->
                    assertEquals(1, msg.trades.size)
                    assertEquals(marketSellOrderApiResponse.orderId, msg.trades[0].orderId)
                    assertEquals(SettlementStatus.Completed, msg.trades[0].settlementStatus)
                }
                assertContainsBalancesMessage(
                    messages,
                    listOf(
                        ExpectedBalance(AssetAmount(baseSymbol, takerStartingBaseBalance.amount - orderAmount)),
                        ExpectedBalance(
                            quoteSymbol,
                            total = takerStartingQuoteBalance.amount + takerQuoteQuantity,
                            available = BigDecimal.ZERO,
                        ),
                    ),
                )
            }
        }

        makerWsClient.apply {
            assertMessagesReceived(2) {}
        }

        // wait for the withdrawal to finalize and verify it completed and taker quote assets are zero
        waitForFinalizedWithdrawal(pendingWithdrawal.id)
        assertEquals(WithdrawalStatus.Complete, takerApiClient.getWithdrawal(pendingWithdrawal.id).withdrawal.status)

        takerWsClient.apply {
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(AssetAmount(baseSymbol, takerStartingBaseBalance.amount - orderAmount)),
                    ExpectedBalance(AssetAmount(quoteSymbol, BigDecimal.ZERO)),
                ),
            )
        }

        takerWsClient.close()
        makerWsClient.close()
    }
}
