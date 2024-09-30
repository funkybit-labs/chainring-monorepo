package xyz.funkybit.integrationtests.bitcoin

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import xyz.funkybit.apps.api.model.SymbolInfo
import xyz.funkybit.core.model.db.ArchAccountEntity
import xyz.funkybit.core.model.db.ArchAccountStatus
import xyz.funkybit.core.model.db.ArchAccountTable
import xyz.funkybit.core.model.db.ExecutionRole
import xyz.funkybit.core.model.db.OrderSide
import xyz.funkybit.core.model.db.OrderStatus
import xyz.funkybit.core.model.db.SettlementStatus
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.utils.bitcoin.ArchUtils
import xyz.funkybit.integrationtests.bitcoin.ArchOnboardingTest.Companion.waitForProgramAccount
import xyz.funkybit.integrationtests.bitcoin.ArchOnboardingTest.Companion.waitForProgramStateAccount
import xyz.funkybit.integrationtests.bitcoin.ArchOnboardingTest.Companion.waitForTokenStateAccount
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.testutils.OrderBaseTest
import xyz.funkybit.integrationtests.testutils.getFeeAccountBalanceOnArch
import xyz.funkybit.integrationtests.testutils.isBitcoinDisabled
import xyz.funkybit.integrationtests.utils.AssetAmount
import xyz.funkybit.integrationtests.utils.ExpectedBalance
import xyz.funkybit.integrationtests.utils.MyExpectedTrade
import xyz.funkybit.integrationtests.utils.TestApiClient
import xyz.funkybit.integrationtests.utils.assertAmount
import xyz.funkybit.integrationtests.utils.assertBalances
import xyz.funkybit.integrationtests.utils.assertBalancesMessageReceived
import xyz.funkybit.integrationtests.utils.assertLimitsMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyLimitOrderCreatedMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyMarketOrderCreatedMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyOrderUpdatedMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyTradesCreatedMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyTradesUpdatedMessageReceived
import xyz.funkybit.integrationtests.utils.ofAsset
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

@ExtendWith(AppUnderTestRunner::class)
class ArchSettlementTest : OrderBaseTest() {

    lateinit var btcArch: SymbolInfo

    @BeforeEach
    fun waitForSetup() {
        Assumptions.assumeFalse(isBitcoinDisabled())
        waitForProgramAccount()
        waitForProgramStateAccount()
        waitForTokenStateAccount()
        val config = TestApiClient.getConfiguration()
        val bitcoinChain = config.bitcoinChain
        btcArch = bitcoinChain.symbols.first()
        ArchUtils.tokenAccountSizeThreshold = 10_000_000
    }

    @Test
    fun `settlement success - market buy`() {
        Assumptions.assumeFalse(isBitcoinDisabled())
        val market = btcbtcArchMarket!!
        val baseSymbol = btc
        val quoteSymbol = btcArch

        val (takerApiClient, takerEvmWallet, takerWsClient, takerBitcoinWallet) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(quoteSymbol, "0.00009"),
            ),
            deposits = listOf(
                AssetAmount(quoteSymbol, "0.00005"),
            ),
            subscribeToOrderBook = false,
            subscribeToPrices = false,
        )

        val (makerApiClient, makerEvmWallet, makerWsClient, makerBitcoinWallet) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "0.7"),
            ),
            deposits = listOf(
                AssetAmount(baseSymbol, "0.6"),
            ),
            subscribeToOrderBook = false,
            subscribeToPrices = false,
            setupBitcoinWallet = true,
        )

        // starting onchain balances
        val makerStartingBaseBalance = makerEvmWallet.getExchangeBalance(baseSymbol)
        val makerStartingQuoteBalance = makerBitcoinWallet!!.getExchangeBalance(quoteSymbol)
        val takerStartingBaseBalance = takerEvmWallet.getExchangeBalance(baseSymbol)
        val takerStartingQuoteBalance = takerBitcoinWallet!!.getExchangeBalance(quoteSymbol)

        val startingFeeAccountBalance = getFeeAccountBalanceOnArch(btcArch)

        // place an order and see that it gets accepted
        val limitSellOrderApiResponse = makerApiClient.createLimitOrder(
            market,
            OrderSide.Sell,
            amount = BigDecimal("0.00003"),
            price = BigDecimal("0.999"),
            makerBitcoinWallet,
        )
        makerWsClient.apply {
            assertMyLimitOrderCreatedMessageReceived(limitSellOrderApiResponse)
            assertLimitsMessageReceived(market, base = BigDecimal("0.59997"), quote = BigDecimal("0"))
        }

        // place a sell order
        val marketBuyOrderApiResponse = takerApiClient.createMarketOrder(
            market,
            OrderSide.Buy,
            BigDecimal("0.00003"),
            takerEvmWallet,
        )

        takerWsClient.apply {
            assertMyMarketOrderCreatedMessageReceived(marketBuyOrderApiResponse)
            assertMyTradesCreatedMessageReceived(
                listOf(
                    MyExpectedTrade(
                        order = marketBuyOrderApiResponse,
                        price = BigDecimal("0.999"),
                        amount = AssetAmount(baseSymbol, marketBuyOrderApiResponse.order.amount.fixedAmount()),
                        fee = AssetAmount(quoteSymbol, "0.00000059"),
                        settlementStatus = SettlementStatus.Pending,
                    ),
                ),
            )
            assertMyOrderUpdatedMessageReceived { msg ->
                Assertions.assertEquals(OrderStatus.Filled, msg.order.status)
                Assertions.assertEquals(1, msg.order.executions.size)
                assertAmount(AssetAmount(baseSymbol, "0.00003"), msg.order.executions[0].amount)
                assertAmount(AssetAmount(quoteSymbol, "0.999"), msg.order.executions[0].price)
                Assertions.assertEquals(ExecutionRole.Taker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = BigDecimal("0"), available = BigDecimal("0.00003")),
                    ExpectedBalance(quoteSymbol, total = BigDecimal("0.00005000"), available = BigDecimal("0.00001944")),
                ),
            )
        }

        makerWsClient.apply {
            assertMyTradesCreatedMessageReceived(
                listOf(
                    MyExpectedTrade(
                        order = limitSellOrderApiResponse,
                        price = BigDecimal("0.999"),
                        amount = AssetAmount(baseSymbol, "0.00003"),
                        fee = AssetAmount(quoteSymbol, "0.00000029"),
                        settlementStatus = SettlementStatus.Pending,
                    ),
                ),
            )
            assertMyOrderUpdatedMessageReceived { msg ->
                Assertions.assertEquals(OrderStatus.Filled, msg.order.status)
                Assertions.assertEquals(1, msg.order.executions.size)
                assertAmount(AssetAmount(baseSymbol, "0.00003"), msg.order.executions[0].amount)
                assertAmount(AssetAmount(quoteSymbol, "0.999"), msg.order.executions[0].price)
                Assertions.assertEquals(ExecutionRole.Maker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = BigDecimal("0.6"), available = BigDecimal("0.59997")),
                    ExpectedBalance(quoteSymbol, total = BigDecimal("0"), available = BigDecimal("0.00002968")),
                ),
            )
        }
        val trade = getTradesForOrders(listOf(marketBuyOrderApiResponse.orderId)).first().also {
            assertAmount(AssetAmount(baseSymbol, "0.00003"), it.amount)
            assertAmount(AssetAmount(quoteSymbol, "0.999"), it.price)
        }

        takerWsClient.apply {
            assertLimitsMessageReceived(market, base = BigDecimal("0.00003"), quote = BigDecimal("0.00001944"))
        }

        makerWsClient.apply {
            assertLimitsMessageReceived(market, base = BigDecimal("0.59997"), quote = BigDecimal("0.00002968"))
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
            Assertions.assertEquals(1, msg.trades.size)
            Assertions.assertEquals(marketBuyOrderApiResponse.orderId, msg.trades[0].orderId)
            Assertions.assertEquals(SettlementStatus.Completed, msg.trades[0].settlementStatus)
        }

        makerWsClient.assertBalancesMessageReceived(
            listOf(
                ExpectedBalance(makerStartingBaseBalance - baseQuantity),
                ExpectedBalance(makerStartingQuoteBalance + notional - makerFee),
            ),
        )
        makerWsClient.assertMyTradesUpdatedMessageReceived { msg ->
            Assertions.assertEquals(1, msg.trades.size)
            Assertions.assertEquals(limitSellOrderApiResponse.orderId, msg.trades[0].orderId)
            Assertions.assertEquals(SettlementStatus.Completed, msg.trades[0].settlementStatus)
        }
        assertEquals(
            startingFeeAccountBalance + makerFee + takerFee,
            getFeeAccountBalanceOnArch(btcArch),
        )
    }

    @Test
    fun `settlement success - market sell`() {
        Assumptions.assumeFalse(isBitcoinDisabled())
        val market = btcbtcArchMarket!!
        val baseSymbol = btc
        val quoteSymbol = btcArch

        val (takerApiClient, takerEvmWallet, takerWsClient, takerBitcoinWallet) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "0.7"),
            ),
            deposits = listOf(
                AssetAmount(baseSymbol, "0.6"),
            ),
            subscribeToOrderBook = false,
            subscribeToPrices = false,
            setupBitcoinWallet = true,
        )

        val (makerApiClient, makerEvmWallet, makerWsClient, makerBitcoinWallet) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(quoteSymbol, "0.00009"),
            ),
            deposits = listOf(
                AssetAmount(quoteSymbol, "0.00005"),
            ),
            subscribeToOrderBook = false,
            subscribeToPrices = false,
        )

        // starting onchain balances
        val makerStartingBaseBalance = makerEvmWallet.getExchangeBalance(baseSymbol)
        val makerStartingQuoteBalance = makerBitcoinWallet!!.getExchangeBalance(quoteSymbol)
        val takerStartingBaseBalance = takerEvmWallet.getExchangeBalance(baseSymbol)
        val takerStartingQuoteBalance = takerBitcoinWallet!!.getExchangeBalance(quoteSymbol)

        val startingFeeAccountBalance = getFeeAccountBalanceOnArch(btcArch)

        // place an order and see that it gets accepted
        val limitBuyOrderApiResponse = makerApiClient.createLimitOrder(
            market,
            OrderSide.Buy,
            amount = BigDecimal("0.00003"),
            price = BigDecimal("0.999"),
            makerEvmWallet,
        )
        makerWsClient.apply {
            assertMyLimitOrderCreatedMessageReceived(limitBuyOrderApiResponse)
            assertLimitsMessageReceived(market, base = BigDecimal("0"), quote = BigDecimal("0.00001974"))
        }

        // place a sell order
        val marketSellOrderApiResponse = takerApiClient.createMarketOrder(
            market,
            OrderSide.Sell,
            BigDecimal("0.00003"),
            takerBitcoinWallet,
        )

        takerWsClient.apply {
            assertMyMarketOrderCreatedMessageReceived(marketSellOrderApiResponse)
            assertMyTradesCreatedMessageReceived(
                listOf(
                    MyExpectedTrade(
                        order = marketSellOrderApiResponse,
                        price = BigDecimal("0.999"),
                        amount = AssetAmount(baseSymbol, marketSellOrderApiResponse.order.amount.fixedAmount()),
                        fee = AssetAmount(quoteSymbol, "0.00000059"),
                        settlementStatus = SettlementStatus.Pending,
                    ),
                ),
            )
            assertMyOrderUpdatedMessageReceived { msg ->
                Assertions.assertEquals(OrderStatus.Filled, msg.order.status)
                Assertions.assertEquals(1, msg.order.executions.size)
                assertAmount(AssetAmount(baseSymbol, "0.00003"), msg.order.executions[0].amount)
                assertAmount(AssetAmount(quoteSymbol, "0.999"), msg.order.executions[0].price)
                Assertions.assertEquals(ExecutionRole.Taker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = BigDecimal("0.6"), available = BigDecimal("0.59997")),
                    ExpectedBalance(quoteSymbol, total = BigDecimal("0"), available = BigDecimal("0.00002938")),
                ),
            )
        }

        makerWsClient.apply {
            assertMyTradesCreatedMessageReceived(
                listOf(
                    MyExpectedTrade(
                        order = limitBuyOrderApiResponse,
                        price = BigDecimal("0.999"),
                        amount = AssetAmount(baseSymbol, "0.00003"),
                        fee = AssetAmount(quoteSymbol, "0.00000029"),
                        settlementStatus = SettlementStatus.Pending,
                    ),
                ),
            )
            assertMyOrderUpdatedMessageReceived { msg ->
                Assertions.assertEquals(OrderStatus.Filled, msg.order.status)
                Assertions.assertEquals(1, msg.order.executions.size)
                assertAmount(AssetAmount(baseSymbol, "0.00003"), msg.order.executions[0].amount)
                assertAmount(AssetAmount(quoteSymbol, "0.999"), msg.order.executions[0].price)
                Assertions.assertEquals(ExecutionRole.Maker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = BigDecimal("0"), available = BigDecimal("0.00003")),
                    ExpectedBalance(quoteSymbol, total = BigDecimal("0.00005000"), available = BigDecimal("0.00001974")),
                ),
            )
        }
        val trade = getTradesForOrders(listOf(marketSellOrderApiResponse.orderId)).first().also {
            assertAmount(AssetAmount(baseSymbol, "0.00003"), it.amount)
            assertAmount(AssetAmount(quoteSymbol, "0.999"), it.price)
        }

        takerWsClient.apply {
            assertLimitsMessageReceived(market, base = BigDecimal("0.59997"), quote = BigDecimal("0.00002938"))
        }

        makerWsClient.apply {
            assertLimitsMessageReceived(market, base = BigDecimal("0.00003"), quote = BigDecimal("0.00001974"))
        }

        waitForSettlementToFinish(listOf(trade.id.value))

        val baseQuantity = AssetAmount(baseSymbol, trade.amount)
        val notional = trade.price.ofAsset(quoteSymbol) * baseQuantity.amount
        val makerFee = notional * BigDecimal("0.01")
        val takerFee = notional * BigDecimal("0.02")

        assertBalances(
            listOf(
                ExpectedBalance(makerStartingBaseBalance + baseQuantity),
                ExpectedBalance(makerStartingQuoteBalance - notional - makerFee),
            ),
            makerApiClient.getBalances().balances,
        )
        assertBalances(
            listOf(
                ExpectedBalance(takerStartingBaseBalance - baseQuantity),
                ExpectedBalance(takerStartingQuoteBalance + notional - takerFee),
            ),
            takerApiClient.getBalances().balances,
        )

        takerWsClient.assertBalancesMessageReceived(
            listOf(
                ExpectedBalance(takerStartingBaseBalance - baseQuantity),
                ExpectedBalance(takerStartingQuoteBalance + notional - takerFee),
            ),
        )
        takerWsClient.assertMyTradesUpdatedMessageReceived { msg ->
            Assertions.assertEquals(1, msg.trades.size)
            Assertions.assertEquals(marketSellOrderApiResponse.orderId, msg.trades[0].orderId)
            Assertions.assertEquals(SettlementStatus.Completed, msg.trades[0].settlementStatus)
        }

        makerWsClient.assertBalancesMessageReceived(
            listOf(
                ExpectedBalance(makerStartingBaseBalance + baseQuantity),
                ExpectedBalance(makerStartingQuoteBalance - notional - makerFee),
            ),
        )
        makerWsClient.assertMyTradesUpdatedMessageReceived { msg ->
            Assertions.assertEquals(1, msg.trades.size)
            Assertions.assertEquals(limitBuyOrderApiResponse.orderId, msg.trades[0].orderId)
            Assertions.assertEquals(SettlementStatus.Completed, msg.trades[0].settlementStatus)
        }
        assertEquals(
            startingFeeAccountBalance + makerFee + takerFee,
            getFeeAccountBalanceOnArch(btcArch),
        )
    }

    @Test
    fun `settlement success - market buy - multiple arch accounts`() {
        Assumptions.assumeFalse(isBitcoinDisabled())
        val market = btcbtcArchMarket!!
        val baseSymbol = btc
        val quoteSymbol = btcArch

        transaction {
            ArchAccountTable.deleteWhere {
                symbolGuid.isNotNull() and status.eq(ArchAccountStatus.Full)
            }
        }

        ArchUtils.tokenAccountSizeThreshold = 50

        val (takerApiClient, takerEvmWallet, takerWsClient, takerBitcoinWallet) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(quoteSymbol, "0.00009"),
            ),
            deposits = listOf(
                AssetAmount(quoteSymbol, "0.00005"),
            ),
            subscribeToOrderBook = false,
            subscribeToPrices = false,
        )

        transaction {
            assertEquals(
                ArchAccountStatus.Full,
                ArchAccountEntity.findTokenAccountsForSymbol(SymbolEntity.forName(btcArch.name)).first().status,
            )
        }
        // wait for a new one to be created
        waitForTokenStateAccount()

        transaction {
            assertEquals(2, ArchAccountEntity.findTokenAccountsForSymbol(SymbolEntity.forName(btcArch.name)).size)
        }

        ArchUtils.tokenAccountSizeThreshold = 10_000_000

        val (makerApiClient, makerEvmWallet, makerWsClient, makerBitcoinWallet) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "0.7"),
            ),
            deposits = listOf(
                AssetAmount(baseSymbol, "0.6"),
            ),
            subscribeToOrderBook = false,
            subscribeToPrices = false,
            setupBitcoinWallet = true,
        )

        // starting onchain balances
        val makerStartingBaseBalance = makerEvmWallet.getExchangeBalance(baseSymbol)
        val makerStartingQuoteBalance = makerBitcoinWallet!!.getExchangeBalance(quoteSymbol)
        val takerStartingBaseBalance = takerEvmWallet.getExchangeBalance(baseSymbol)
        val takerStartingQuoteBalance = takerBitcoinWallet!!.getExchangeBalance(quoteSymbol)

        val startingFeeAccountBalance = getFeeAccountBalanceOnArch(btcArch)

        // place an order and see that it gets accepted
        val limitSellOrderApiResponse = makerApiClient.createLimitOrder(
            market,
            OrderSide.Sell,
            amount = BigDecimal("0.00003"),
            price = BigDecimal("0.999"),
            makerBitcoinWallet,
        )
        makerWsClient.apply {
            assertMyLimitOrderCreatedMessageReceived(limitSellOrderApiResponse)
            assertLimitsMessageReceived(market, base = BigDecimal("0.59997"), quote = BigDecimal("0"))
        }

        // place a sell order
        val marketBuyOrderApiResponse = takerApiClient.createMarketOrder(
            market,
            OrderSide.Buy,
            BigDecimal("0.00003"),
            takerEvmWallet,
        )

        takerWsClient.apply {
            assertMyMarketOrderCreatedMessageReceived(marketBuyOrderApiResponse)
            assertMyTradesCreatedMessageReceived(
                listOf(
                    MyExpectedTrade(
                        order = marketBuyOrderApiResponse,
                        price = BigDecimal("0.999"),
                        amount = AssetAmount(baseSymbol, marketBuyOrderApiResponse.order.amount.fixedAmount()),
                        fee = AssetAmount(quoteSymbol, "0.00000059"),
                        settlementStatus = SettlementStatus.Pending,
                    ),
                ),
            )
            assertMyOrderUpdatedMessageReceived { msg ->
                Assertions.assertEquals(OrderStatus.Filled, msg.order.status)
                Assertions.assertEquals(1, msg.order.executions.size)
                assertAmount(AssetAmount(baseSymbol, "0.00003"), msg.order.executions[0].amount)
                assertAmount(AssetAmount(quoteSymbol, "0.999"), msg.order.executions[0].price)
                Assertions.assertEquals(ExecutionRole.Taker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = BigDecimal("0"), available = BigDecimal("0.00003")),
                    ExpectedBalance(quoteSymbol, total = BigDecimal("0.00005000"), available = BigDecimal("0.00001944")),
                ),
            )
        }

        makerWsClient.apply {
            assertMyTradesCreatedMessageReceived(
                listOf(
                    MyExpectedTrade(
                        order = limitSellOrderApiResponse,
                        price = BigDecimal("0.999"),
                        amount = AssetAmount(baseSymbol, "0.00003"),
                        fee = AssetAmount(quoteSymbol, "0.00000029"),
                        settlementStatus = SettlementStatus.Pending,
                    ),
                ),
            )
            assertMyOrderUpdatedMessageReceived { msg ->
                Assertions.assertEquals(OrderStatus.Filled, msg.order.status)
                Assertions.assertEquals(1, msg.order.executions.size)
                assertAmount(AssetAmount(baseSymbol, "0.00003"), msg.order.executions[0].amount)
                assertAmount(AssetAmount(quoteSymbol, "0.999"), msg.order.executions[0].price)
                Assertions.assertEquals(ExecutionRole.Maker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = BigDecimal("0.6"), available = BigDecimal("0.59997")),
                    ExpectedBalance(quoteSymbol, total = BigDecimal("0"), available = BigDecimal("0.00002968")),
                ),
            )
        }
        val trade = getTradesForOrders(listOf(marketBuyOrderApiResponse.orderId)).first().also {
            assertAmount(AssetAmount(baseSymbol, "0.00003"), it.amount)
            assertAmount(AssetAmount(quoteSymbol, "0.999"), it.price)
        }

        takerWsClient.apply {
            assertLimitsMessageReceived(market, base = BigDecimal("0.00003"), quote = BigDecimal("0.00001944"))
        }

        makerWsClient.apply {
            assertLimitsMessageReceived(market, base = BigDecimal("0.59997"), quote = BigDecimal("0.00002968"))
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
            Assertions.assertEquals(1, msg.trades.size)
            Assertions.assertEquals(marketBuyOrderApiResponse.orderId, msg.trades[0].orderId)
            Assertions.assertEquals(SettlementStatus.Completed, msg.trades[0].settlementStatus)
        }

        makerWsClient.assertBalancesMessageReceived(
            listOf(
                ExpectedBalance(makerStartingBaseBalance - baseQuantity),
                ExpectedBalance(makerStartingQuoteBalance + notional - makerFee),
            ),
        )
        makerWsClient.assertMyTradesUpdatedMessageReceived { msg ->
            Assertions.assertEquals(1, msg.trades.size)
            Assertions.assertEquals(limitSellOrderApiResponse.orderId, msg.trades[0].orderId)
            Assertions.assertEquals(SettlementStatus.Completed, msg.trades[0].settlementStatus)
        }
        assertEquals(
            startingFeeAccountBalance + makerFee + takerFee,
            getFeeAccountBalanceOnArch(btcArch),
        )
    }
}
