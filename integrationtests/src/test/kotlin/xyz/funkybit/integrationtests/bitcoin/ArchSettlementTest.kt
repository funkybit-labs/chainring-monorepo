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
import xyz.funkybit.core.model.db.WithdrawalEntity
import xyz.funkybit.core.model.db.WithdrawalStatus
import xyz.funkybit.core.utils.bitcoin.ArchUtils
import xyz.funkybit.integrationtests.bitcoin.ArchDepositAndWithdrawalTest.Companion.initiateWithdrawal
import xyz.funkybit.integrationtests.bitcoin.ArchDepositAndWithdrawalTest.Companion.waitForWithdrawal
import xyz.funkybit.integrationtests.bitcoin.ArchOnboardingTest.Companion.waitForProgramAccount
import xyz.funkybit.integrationtests.bitcoin.ArchOnboardingTest.Companion.waitForProgramStateAccount
import xyz.funkybit.integrationtests.bitcoin.ArchOnboardingTest.Companion.waitForTokenStateAccount
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.testutils.OrderBaseTest
import xyz.funkybit.integrationtests.testutils.getFeeAccountBalanceOnArch
import xyz.funkybit.integrationtests.testutils.isBitcoinDisabled
import xyz.funkybit.integrationtests.testutils.waitForFinalizedWithdrawal
import xyz.funkybit.integrationtests.utils.AssetAmount
import xyz.funkybit.integrationtests.utils.ExpectedBalance
import xyz.funkybit.integrationtests.utils.MyExpectedTrade
import xyz.funkybit.integrationtests.utils.TestApiClient
import xyz.funkybit.integrationtests.utils.assertAmount
import xyz.funkybit.integrationtests.utils.assertBalances
import xyz.funkybit.integrationtests.utils.assertBalancesMessageReceived
import xyz.funkybit.integrationtests.utils.assertContainsMyTradesUpdatedMessage
import xyz.funkybit.integrationtests.utils.assertLimitsMessageReceived
import xyz.funkybit.integrationtests.utils.assertMessagesReceived
import xyz.funkybit.integrationtests.utils.assertMyLimitOrdersCreatedMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyMarketOrdersCreatedMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyOrdersUpdatedMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyTradesCreatedMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyTradesUpdatedMessageReceived
import xyz.funkybit.integrationtests.utils.ofAsset
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

@ExtendWith(AppUnderTestRunner::class)
class ArchSettlementTest : OrderBaseTest() {

    private lateinit var btcArch: SymbolInfo

    @BeforeEach
    fun waitForSetup() {
        Assumptions.assumeFalse(isBitcoinDisabled())
        waitForProgramAccount()
        waitForProgramStateAccount()
        waitForTokenStateAccount()
        val config = TestApiClient.getConfiguration()
        val bitcoinChain = config.bitcoinChain
        btcArch = bitcoinChain.symbols.first()
        ArchUtils.walletsPerTokenAccountThreshold = 100_000
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
            assertMyLimitOrdersCreatedMessageReceived(limitSellOrderApiResponse)
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
            assertMyMarketOrdersCreatedMessageReceived(marketBuyOrderApiResponse)
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
            assertMyOrdersUpdatedMessageReceived { msg ->
                Assertions.assertEquals(1, msg.orders.size)
                msg.orders.first().let { order ->
                    Assertions.assertEquals(OrderStatus.Filled, order.status)
                    Assertions.assertEquals(1, order.executions.size)
                    assertAmount(AssetAmount(baseSymbol, "0.00003"), order.executions[0].amount)
                    assertAmount(AssetAmount(quoteSymbol, "0.999"), order.executions[0].price)
                    Assertions.assertEquals(ExecutionRole.Taker, order.executions[0].role)
                }
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
            assertMyOrdersUpdatedMessageReceived { msg ->
                Assertions.assertEquals(1, msg.orders.size)
                msg.orders.first().let { order ->
                    Assertions.assertEquals(OrderStatus.Filled, order.status)
                    Assertions.assertEquals(1, order.executions.size)
                    assertAmount(AssetAmount(baseSymbol, "0.00003"), order.executions[0].amount)
                    assertAmount(AssetAmount(quoteSymbol, "0.999"), order.executions[0].price)
                    Assertions.assertEquals(ExecutionRole.Maker, order.executions[0].role)
                }
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
            assertMyLimitOrdersCreatedMessageReceived(limitBuyOrderApiResponse)
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
            assertMyMarketOrdersCreatedMessageReceived(marketSellOrderApiResponse)
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
            assertMyOrdersUpdatedMessageReceived { msg ->
                Assertions.assertEquals(1, msg.orders.size)
                msg.orders.first().let { order ->
                    Assertions.assertEquals(OrderStatus.Filled, order.status)
                    Assertions.assertEquals(1, order.executions.size)
                    assertAmount(AssetAmount(baseSymbol, "0.00003"), order.executions[0].amount)
                    assertAmount(AssetAmount(quoteSymbol, "0.999"), order.executions[0].price)
                    Assertions.assertEquals(ExecutionRole.Taker, order.executions[0].role)
                }
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
            assertMyOrdersUpdatedMessageReceived { msg ->
                Assertions.assertEquals(1, msg.orders.size)
                msg.orders.first().let { order ->
                    Assertions.assertEquals(OrderStatus.Filled, order.status)
                    Assertions.assertEquals(1, order.executions.size)
                    assertAmount(AssetAmount(baseSymbol, "0.00003"), order.executions[0].amount)
                    assertAmount(AssetAmount(quoteSymbol, "0.999"), order.executions[0].price)
                    Assertions.assertEquals(ExecutionRole.Maker, order.executions[0].role)
                }
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

        ArchUtils.walletsPerTokenAccountThreshold = 1

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

        ArchUtils.walletsPerTokenAccountThreshold = 100_000

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
            assertMyLimitOrdersCreatedMessageReceived(limitSellOrderApiResponse)
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
            assertMyMarketOrdersCreatedMessageReceived(marketBuyOrderApiResponse)
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
            assertMyOrdersUpdatedMessageReceived { msg ->
                Assertions.assertEquals(1, msg.orders.size)
                msg.orders.first().let { order ->
                    Assertions.assertEquals(OrderStatus.Filled, order.status)
                    Assertions.assertEquals(1, order.executions.size)
                    assertAmount(AssetAmount(baseSymbol, "0.00003"), order.executions[0].amount)
                    assertAmount(AssetAmount(quoteSymbol, "0.999"), order.executions[0].price)
                    Assertions.assertEquals(ExecutionRole.Taker, order.executions[0].role)
                }
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
            assertMyOrdersUpdatedMessageReceived { msg ->
                Assertions.assertEquals(1, msg.orders.size)
                msg.orders.first().let { order ->
                    Assertions.assertEquals(OrderStatus.Filled, order.status)
                    Assertions.assertEquals(1, order.executions.size)
                    assertAmount(AssetAmount(baseSymbol, "0.00003"), order.executions[0].amount)
                    assertAmount(AssetAmount(quoteSymbol, "0.999"), order.executions[0].price)
                    Assertions.assertEquals(ExecutionRole.Maker, order.executions[0].role)
                }
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
    fun `settlement failure - some trades in batch fail, some succeed`() {
        Assumptions.assumeFalse(isBitcoinDisabled())
        val market = btcbtcArchMarket!!
        val baseSymbol = btc
        val quoteSymbol = btcArch

        val depositAmount = AssetAmount(quoteSymbol, "0.00016")
        val (taker1ApiClient, taker1EvmWallet, taker1WsClient, taker1BitcoinWallet) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(quoteSymbol, "0.00020"),
            ),
            deposits = listOf(
                depositAmount,
            ),
            subscribeToOrderBook = false,
            subscribeToPrices = false,
        )

        val withdrawAmount = AssetAmount(quoteSymbol, "0.00007")
        val pendingBtcWithdrawal = initiateWithdrawal(taker1ApiClient, taker1BitcoinWallet!!, taker1WsClient, btcArch, depositAmount.inFundamentalUnits, withdrawAmount.inFundamentalUnits)
        taker1WsClient.apply {
            assertLimitsMessageReceived(market, base = BigDecimal.ZERO, quote = BigDecimal("0.00009"))
        }
        waitForWithdrawal(taker1ApiClient, taker1BitcoinWallet, taker1WsClient, btcArch, pendingBtcWithdrawal, depositAmount.inFundamentalUnits, withdrawAmount.inFundamentalUnits)

        // hack to resubmit the same transaction again bypassing the sequencer. This way taker will not have a
        // sufficient on chain balance for the order so settlement will fail.
        transaction {
            WithdrawalEntity[pendingBtcWithdrawal.id].status = WithdrawalStatus.Sequenced
        }

        waitForFinalizedWithdrawal(pendingBtcWithdrawal.id, WithdrawalStatus.Complete)

        taker1WsClient.apply {
            assertBalancesMessageReceived()
        }

        val (taker2ApiClient, taker2EvmWallet, taker2WsClient, taker2BitcoinWallet) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(quoteSymbol, "0.00020"),
            ),
            deposits = listOf(
                depositAmount,
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
        val taker1StartingBaseBalance = taker1EvmWallet.getExchangeBalance(baseSymbol)
        val taker1StartingQuoteBalance = taker1BitcoinWallet.getExchangeBalance(quoteSymbol)
        val taker2StartingBaseBalance = taker2EvmWallet.getExchangeBalance(baseSymbol)
        val taker2StartingQuoteBalance = taker2BitcoinWallet!!.getExchangeBalance(quoteSymbol)

        val startingFeeAccountBalance = getFeeAccountBalanceOnArch(btcArch)

        // place an order and see that it gets accepted
        val limitSellOrderApiResponse = makerApiClient.createLimitOrder(
            market,
            OrderSide.Sell,
            amount = BigDecimal("0.004"),
            price = BigDecimal("0.999"),
            makerBitcoinWallet,
        )
        makerWsClient.apply {
            assertMyLimitOrdersCreatedMessageReceived(limitSellOrderApiResponse)
            assertLimitsMessageReceived(market, base = BigDecimal("0.596"), quote = BigDecimal("0"))
        }

        // place a sell order
        val marketBuyOrderApiResponse = taker1ApiClient.createMarketOrder(
            market,
            OrderSide.Buy,
            BigDecimal("0.00003"),
            taker1EvmWallet,
        )

        taker1WsClient.apply {
            assertMyMarketOrdersCreatedMessageReceived(marketBuyOrderApiResponse)
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
            assertMyOrdersUpdatedMessageReceived { msg ->
                Assertions.assertEquals(OrderStatus.Filled, msg.orders[0].status)
                Assertions.assertEquals(1, msg.orders[0].executions.size)
                assertAmount(AssetAmount(baseSymbol, "0.00003"), msg.orders[0].executions[0].amount)
                assertAmount(AssetAmount(quoteSymbol, "0.999"), msg.orders[0].executions[0].price)
                Assertions.assertEquals(ExecutionRole.Taker, msg.orders[0].executions[0].role)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = BigDecimal("0"), available = BigDecimal("0.00003")),
                    ExpectedBalance(quoteSymbol, total = BigDecimal("0.00002000"), available = BigDecimal("0.00005944")),
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
            assertMyOrdersUpdatedMessageReceived { msg ->
                Assertions.assertEquals(OrderStatus.Partial, msg.orders[0].status)
                Assertions.assertEquals(1, msg.orders[0].executions.size)
                assertAmount(AssetAmount(baseSymbol, "0.00003"), msg.orders[0].executions[0].amount)
                assertAmount(AssetAmount(quoteSymbol, "0.999"), msg.orders[0].executions[0].price)
                Assertions.assertEquals(ExecutionRole.Maker, msg.orders[0].executions[0].role)
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

        taker1WsClient.apply {
            assertLimitsMessageReceived(market, base = BigDecimal("0.00003"), quote = BigDecimal("0.00005944"))
        }

        makerWsClient.apply {
            assertLimitsMessageReceived(market, base = BigDecimal("0.596"), quote = BigDecimal("0.00002968"))
        }

        // taker 2
        // place a sell order
        val marketBuyOrder2ApiResponse = taker2ApiClient.createMarketOrder(
            market,
            OrderSide.Buy,
            BigDecimal("0.00003"),
            taker2EvmWallet,
        )

        taker2WsClient.apply {
            assertMyMarketOrdersCreatedMessageReceived(marketBuyOrder2ApiResponse)
            assertMyTradesCreatedMessageReceived(
                listOf(
                    MyExpectedTrade(
                        order = marketBuyOrder2ApiResponse,
                        price = BigDecimal("0.999"),
                        amount = AssetAmount(baseSymbol, marketBuyOrder2ApiResponse.order.amount.fixedAmount()),
                        fee = AssetAmount(quoteSymbol, "0.00000059"),
                        settlementStatus = SettlementStatus.Pending,
                    ),
                ),
            )
            assertMyOrdersUpdatedMessageReceived { msg ->
                Assertions.assertEquals(OrderStatus.Filled, msg.orders[0].status)
                Assertions.assertEquals(1, msg.orders[0].executions.size)
                assertAmount(AssetAmount(baseSymbol, "0.00003"), msg.orders[0].executions[0].amount)
                assertAmount(AssetAmount(quoteSymbol, "0.999"), msg.orders[0].executions[0].price)
                Assertions.assertEquals(ExecutionRole.Taker, msg.orders[0].executions[0].role)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = BigDecimal("0"), available = BigDecimal("0.00003")),
                    ExpectedBalance(quoteSymbol, total = BigDecimal("0.00016000"), available = BigDecimal("0.00012944")),
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
            assertMyOrdersUpdatedMessageReceived { msg ->
                Assertions.assertEquals(OrderStatus.Partial, msg.orders[0].status)
                Assertions.assertEquals(2, msg.orders[0].executions.size)
                assertAmount(AssetAmount(baseSymbol, "0.00003"), msg.orders[0].executions[1].amount)
                assertAmount(AssetAmount(quoteSymbol, "0.999"), msg.orders[0].executions[1].price)
                Assertions.assertEquals(ExecutionRole.Maker, msg.orders[0].executions[1].role)
            }
            assertBalancesMessageReceived(
                listOf(
                    ExpectedBalance(baseSymbol, total = BigDecimal("0.6"), available = BigDecimal("0.59994")),
                    ExpectedBalance(quoteSymbol, total = BigDecimal("0"), available = BigDecimal("0.00005936")),
                ),
            )
        }
        val trade2 = getTradesForOrders(listOf(marketBuyOrder2ApiResponse.orderId)).first().also {
            assertAmount(AssetAmount(baseSymbol, "0.00003"), it.amount)
            assertAmount(AssetAmount(quoteSymbol, "0.999"), it.price)
        }

        taker2WsClient.apply {
            assertLimitsMessageReceived(market, base = BigDecimal("0.00003"), quote = BigDecimal("0.00012944"))
        }

        makerWsClient.apply {
            assertLimitsMessageReceived(market, base = BigDecimal("0.596"), quote = BigDecimal("0.00005936"))
        }

        waitForSettlementToFinish(listOf(trade.id.value), SettlementStatus.Failed)
        waitForSettlementToFinish(listOf(trade2.id.value))

        val baseQuantity = AssetAmount(baseSymbol, trade.amount)
        val notional2 = trade2.price.ofAsset(quoteSymbol) * baseQuantity.amount
        val makerFee = notional2 * BigDecimal("0.01")
        val taker2Fee = notional2 * BigDecimal("0.02")

        assertBalances(
            listOf(
                ExpectedBalance(makerStartingBaseBalance - baseQuantity),
                ExpectedBalance(makerStartingQuoteBalance + notional2 - makerFee),
            ),
            makerApiClient.getBalances().balances,
        )

        assertBalances(
            listOf(
                ExpectedBalance(taker1StartingBaseBalance),
                ExpectedBalance(taker1StartingQuoteBalance.symbol, taker1StartingQuoteBalance.amount, (taker1StartingQuoteBalance + withdrawAmount).amount),
            ),
            taker1ApiClient.getBalances().balances,
        )
        assertBalances(
            listOf(
                ExpectedBalance(taker2StartingBaseBalance + baseQuantity),
                ExpectedBalance(taker2StartingQuoteBalance - notional2 - taker2Fee),
            ),
            taker2ApiClient.getBalances().balances,
        )

        taker2WsClient.assertBalancesMessageReceived(
            listOf(
                ExpectedBalance(taker2StartingBaseBalance + baseQuantity),
                ExpectedBalance(taker2StartingQuoteBalance - notional2 - taker2Fee),
            ),
        )
        taker2WsClient.assertMyTradesUpdatedMessageReceived { msg ->
            Assertions.assertEquals(1, msg.trades.size)
            Assertions.assertEquals(marketBuyOrder2ApiResponse.orderId, msg.trades[0].orderId)
            Assertions.assertEquals(SettlementStatus.Completed, msg.trades[0].settlementStatus)
        }

        makerWsClient.apply {
            assertMessagesReceived(3) { messages ->
                assertContainsMyTradesUpdatedMessage(messages) { msg ->
                    Assertions.assertEquals(1, msg.trades.size)
                    Assertions.assertEquals(limitSellOrderApiResponse.orderId, msg.trades[0].orderId)
                    Assertions.assertEquals(SettlementStatus.Failed, msg.trades[0].settlementStatus)
                    Assertions.assertEquals("Insufficient Balance", msg.trades[0].error)
                }
            }
        }

        assertEquals(
            startingFeeAccountBalance + makerFee + taker2Fee,
            getFeeAccountBalanceOnArch(btcArch),
        )
    }
}
