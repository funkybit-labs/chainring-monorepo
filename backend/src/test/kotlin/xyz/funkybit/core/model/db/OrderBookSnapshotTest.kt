package xyz.funkybit.core.model.db

import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.funkybit.testfixtures.DbTestHelpers.createChain
import xyz.funkybit.testfixtures.DbTestHelpers.createMarket
import xyz.funkybit.testfixtures.DbTestHelpers.createNativeSymbol
import xyz.funkybit.testfixtures.DbTestHelpers.createSymbol
import xyz.funkybit.testfixtures.DbTestHelpers.createWallet
import xyz.funkybit.testfixtures.OrderBookTestHelper.Order
import xyz.funkybit.testfixtures.OrderBookTestHelper.Trade
import xyz.funkybit.testfixtures.OrderBookTestHelper.verifyOrderBook
import xyz.funkybit.testutils.TestWithDb
import java.math.BigDecimal

class OrderBookSnapshotTest : TestWithDb() {
    private val btcEthMarket = MarketId("BTC:123/ETH:123")
    private val ethUsdcMarket = MarketId("ETH:123/USDC:123")
    private lateinit var wallets: List<WalletEntity>

    @BeforeEach
    fun setup() {
        transaction {
            val chain = createChain(ChainId(123UL), "test-chain")
            val btc = createNativeSymbol("BTC", chain.id.value, decimals = 18U)
            val eth = createSymbol("ETH", chain.id.value, decimals = 18U)
            val usdc = createSymbol("USDC", chain.id.value, decimals = 18U)
            createMarket(btc, eth, tickSize = "0.05".toBigDecimal(), lastPrice = "17.525".toBigDecimal()).id.value
            createMarket(eth, usdc, tickSize = "0.01".toBigDecimal(), lastPrice = "2999.995".toBigDecimal()).id.value
            wallets = (1..5).map { createWallet() }
        }
    }

    @Test
    fun empty() {
        verifyOrderBook(
            btcEthMarket,
            ordersInDb = emptyList(),
            tradesInDb = emptyList(),
            expected = OrderBookSnapshot(
                bids = emptyList(),
                asks = emptyList(),
                last = OrderBookSnapshot.LastTrade(
                    price = BigDecimal("0.000"),
                    direction = OrderBookSnapshot.LastTradeDirection.Unchanged,
                ),
            ),
        )
    }

    @Test
    fun `buy orders only`() {
        verifyOrderBook(
            btcEthMarket,
            ordersInDb = listOf(
                Order(
                    OrderId("order_1"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.55".toBigDecimal(),
                    amount = "1.2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_2"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.55".toBigDecimal(),
                    amount = "1.3".toBigDecimal(),
                ),
                Order(
                    OrderId("order_3"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.50".toBigDecimal(),
                    amount = "1.5".toBigDecimal(),
                ),
                Order(
                    OrderId("order_4"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.40".toBigDecimal(),
                    amount = "5".toBigDecimal(),
                ),
                Order(
                    OrderId("order_5"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Partial,
                    price = "17.40".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
            ),
            tradesInDb = emptyList(),
            expected = OrderBookSnapshot(
                bids = listOf(
                    OrderBookSnapshot.Entry(price = BigDecimal("17.550"), size = "2.5".toBigDecimal()),
                    OrderBookSnapshot.Entry(price = BigDecimal("17.500"), size = "1.5".toBigDecimal()),
                    OrderBookSnapshot.Entry(price = BigDecimal("17.400"), size = "7".toBigDecimal()),
                ),
                asks = emptyList(),
                last = OrderBookSnapshot.LastTrade(
                    price = BigDecimal("0.000"),
                    direction = OrderBookSnapshot.LastTradeDirection.Unchanged,
                ),
            ),
        )
    }

    @Test
    fun `sell orders only`() {
        verifyOrderBook(
            btcEthMarket,
            ordersInDb = listOf(
                Order(
                    OrderId("order_1"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.35".toBigDecimal(),
                    amount = "1.2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_2"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Partial,
                    price = "17.35".toBigDecimal(),
                    amount = "1.3".toBigDecimal(),
                ),
                Order(
                    OrderId("order_3"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.30".toBigDecimal(),
                    amount = "5".toBigDecimal(),
                ),
            ),
            tradesInDb = emptyList(),
            expected = OrderBookSnapshot(
                bids = emptyList(),
                asks = listOf(
                    OrderBookSnapshot.Entry(price = BigDecimal("17.350"), size = "2.5".toBigDecimal()),
                    OrderBookSnapshot.Entry(price = BigDecimal("17.300"), size = "5".toBigDecimal()),
                ),
                last = OrderBookSnapshot.LastTrade(
                    price = BigDecimal("0.000"),
                    direction = OrderBookSnapshot.LastTradeDirection.Unchanged,
                ),
            ),
        )
    }

    @Test
    fun `buy and sell orders`() {
        verifyOrderBook(
            btcEthMarket,
            ordersInDb = listOf(
                Order(
                    OrderId("order_1"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.55".toBigDecimal(),
                    amount = "1.2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_2"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.55".toBigDecimal(),
                    amount = "1.3".toBigDecimal(),
                ),
                Order(
                    OrderId("order_3"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.50".toBigDecimal(),
                    amount = "1.5".toBigDecimal(),
                ),
                Order(
                    OrderId("order_4"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.40".toBigDecimal(),
                    amount = "5".toBigDecimal(),
                ),
                Order(
                    OrderId("order_5"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Partial,
                    price = "17.40".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_6"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.35".toBigDecimal(),
                    amount = "1.2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_7"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Partial,
                    price = "17.35".toBigDecimal(),
                    amount = "1.3".toBigDecimal(),
                ),
                Order(
                    OrderId("order_8"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.30".toBigDecimal(),
                    amount = "5".toBigDecimal(),
                ),
            ),
            tradesInDb = emptyList(),
            expected = OrderBookSnapshot(
                bids = listOf(
                    OrderBookSnapshot.Entry(price = BigDecimal("17.550"), size = "2.5".toBigDecimal()),
                    OrderBookSnapshot.Entry(price = BigDecimal("17.500"), size = "1.5".toBigDecimal()),
                    OrderBookSnapshot.Entry(price = BigDecimal("17.400"), size = "7".toBigDecimal()),
                ),
                asks = listOf(
                    OrderBookSnapshot.Entry(price = BigDecimal("17.350"), size = "2.5".toBigDecimal()),
                    OrderBookSnapshot.Entry(price = BigDecimal("17.300"), size = "5".toBigDecimal()),
                ),
                last = OrderBookSnapshot.LastTrade(
                    price = BigDecimal("0.000"),
                    direction = OrderBookSnapshot.LastTradeDirection.Unchanged,
                ),
            ),
        )
    }

    @Test
    fun `only open and partially filled limit orders are considered`() {
        verifyOrderBook(
            btcEthMarket,
            ordersInDb = listOf(
                Order(
                    OrderId("order_1"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.55".toBigDecimal(),
                    amount = "1.2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_2"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.55".toBigDecimal(),
                    amount = "1.3".toBigDecimal(),
                ),
                Order(
                    OrderId("order_3"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.50".toBigDecimal(),
                    amount = "1.5".toBigDecimal(),
                ),
                Order(
                    OrderId("order_4"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.40".toBigDecimal(),
                    amount = "5".toBigDecimal(),
                ),
                Order(
                    OrderId("order_5"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Partial,
                    price = "17.40".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_6"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Market,
                    OrderStatus.Open,
                    price = "17.35".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_7"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Filled,
                    price = "17.35".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_8"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Cancelled,
                    price = "17.35".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_9"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Expired,
                    price = "17.35".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_11"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Rejected,
                    price = "17.35".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_12"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.35".toBigDecimal(),
                    amount = "1.2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_13"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Partial,
                    price = "17.35".toBigDecimal(),
                    amount = "1.3".toBigDecimal(),
                ),
                Order(
                    OrderId("order_14"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.30".toBigDecimal(),
                    amount = "5".toBigDecimal(),
                ),
                Order(
                    OrderId("order_15"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Market,
                    OrderStatus.Open,
                    price = "17.40".toBigDecimal(),
                    amount = "5".toBigDecimal(),
                ),
                Order(
                    OrderId("order_16"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Market,
                    OrderStatus.Filled,
                    price = "17.35".toBigDecimal(),
                    amount = "5".toBigDecimal(),
                ),
                Order(
                    OrderId("order_17"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Market,
                    OrderStatus.Cancelled,
                    price = "17.35".toBigDecimal(),
                    amount = "5".toBigDecimal(),
                ),
                Order(
                    OrderId("order_18"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Market,
                    OrderStatus.Expired,
                    price = "17.35".toBigDecimal(),
                    amount = "5".toBigDecimal(),
                ),
                Order(
                    OrderId("order_19"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Market,
                    OrderStatus.Rejected,
                    price = "17.35".toBigDecimal(),
                    amount = "5".toBigDecimal(),
                ),
            ),
            tradesInDb = emptyList(),
            expected = OrderBookSnapshot(
                bids = listOf(
                    OrderBookSnapshot.Entry(price = BigDecimal("17.550"), size = "2.5".toBigDecimal()),
                    OrderBookSnapshot.Entry(price = BigDecimal("17.500"), size = "1.5".toBigDecimal()),
                    OrderBookSnapshot.Entry(price = BigDecimal("17.400"), size = "7".toBigDecimal()),
                ),
                asks = listOf(
                    OrderBookSnapshot.Entry(price = BigDecimal("17.350"), size = "2.5".toBigDecimal()),
                    OrderBookSnapshot.Entry(price = BigDecimal("17.300"), size = "5".toBigDecimal()),
                ),
                last = OrderBookSnapshot.LastTrade(
                    price = BigDecimal("0.000"),
                    direction = OrderBookSnapshot.LastTradeDirection.Unchanged,
                ),
            ),
        )
    }

    @Test
    fun `last trade when only one trade present`() {
        verifyOrderBook(
            btcEthMarket,
            ordersInDb = listOf(
                Order(
                    OrderId("order_1"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.50".toBigDecimal(),
                    amount = "1".toBigDecimal(),
                ),
                Order(
                    OrderId("order_2"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Partial,
                    price = "17.55".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_3"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Filled,
                    price = "17.40".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_4"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Market,
                    OrderStatus.Filled,
                    price = null,
                    amount = "2".toBigDecimal(),
                ),
            ),
            tradesInDb = listOf(
                Trade(
                    btcEthMarket,
                    responseSequence = 1,
                    buyOrder = OrderId("order_3"),
                    sellOrder = OrderId("order_4"),
                    amount = "2".toBigDecimal(),
                    price = "17.40".toBigDecimal(),
                ),
            ),
            expected = OrderBookSnapshot(
                bids = listOf(
                    OrderBookSnapshot.Entry(price = BigDecimal("17.500"), size = "1".toBigDecimal()),
                ),
                asks = listOf(
                    OrderBookSnapshot.Entry(price = BigDecimal("17.550"), size = "2".toBigDecimal()),
                ),
                last = OrderBookSnapshot.LastTrade(
                    price = BigDecimal("17.400"),
                    direction = OrderBookSnapshot.LastTradeDirection.Up,
                ),
            ),
        )
    }

    @Test
    fun `last trade down`() {
        verifyOrderBook(
            btcEthMarket,
            ordersInDb = listOf(
                Order(
                    OrderId("order_1"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.50".toBigDecimal(),
                    amount = "1".toBigDecimal(),
                ),
                Order(
                    OrderId("order_2"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Partial,
                    price = "17.55".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_3"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Filled,
                    price = "17.45".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_4"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Market,
                    OrderStatus.Filled,
                    price = null,
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_5"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Filled,
                    price = "17.40".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_6"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Market,
                    OrderStatus.Filled,
                    price = null,
                    amount = "2".toBigDecimal(),
                ),
            ),
            tradesInDb = listOf(
                Trade(
                    btcEthMarket,
                    responseSequence = 1,
                    buyOrder = OrderId("order_3"),
                    sellOrder = OrderId("order_4"),
                    amount = "2".toBigDecimal(),
                    price = "17.45".toBigDecimal(),
                ),
                Trade(
                    btcEthMarket,
                    responseSequence = 1,
                    buyOrder = OrderId("order_5"),
                    sellOrder = OrderId("order_6"),
                    amount = "2".toBigDecimal(),
                    price = "17.40".toBigDecimal(),
                ),
            ),
            expected = OrderBookSnapshot(
                bids = listOf(
                    OrderBookSnapshot.Entry(price = BigDecimal("17.500"), size = "1".toBigDecimal()),
                ),
                asks = listOf(
                    OrderBookSnapshot.Entry(price = BigDecimal("17.550"), size = "2".toBigDecimal()),
                ),
                last = OrderBookSnapshot.LastTrade(
                    price = BigDecimal("17.400"),
                    direction = OrderBookSnapshot.LastTradeDirection.Down,
                ),
            ),
        )
    }

    @Test
    fun `last trade up`() {
        verifyOrderBook(
            btcEthMarket,
            ordersInDb = listOf(
                Order(
                    OrderId("order_1"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.50".toBigDecimal(),
                    amount = "1".toBigDecimal(),
                ),
                Order(
                    OrderId("order_2"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Partial,
                    price = "17.55".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_3"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Filled,
                    price = "17.45".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_4"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Market,
                    OrderStatus.Filled,
                    price = null,
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_5"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Filled,
                    price = "17.40".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_6"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Market,
                    OrderStatus.Filled,
                    price = null,
                    amount = "2".toBigDecimal(),
                ),
            ),
            tradesInDb = listOf(
                Trade(
                    btcEthMarket,
                    responseSequence = 1,
                    buyOrder = OrderId("order_5"),
                    sellOrder = OrderId("order_6"),
                    amount = "2".toBigDecimal(),
                    price = "17.40".toBigDecimal(),
                ),
                Trade(
                    btcEthMarket,
                    responseSequence = 2,
                    buyOrder = OrderId("order_3"),
                    sellOrder = OrderId("order_4"),
                    amount = "2".toBigDecimal(),
                    price = "17.45".toBigDecimal(),
                ),
            ),
            expected = OrderBookSnapshot(
                bids = listOf(
                    OrderBookSnapshot.Entry(price = BigDecimal("17.500"), size = "1".toBigDecimal()),
                ),
                asks = listOf(
                    OrderBookSnapshot.Entry(price = BigDecimal("17.550"), size = "2".toBigDecimal()),
                ),
                last = OrderBookSnapshot.LastTrade(
                    price = BigDecimal("17.450"),
                    direction = OrderBookSnapshot.LastTradeDirection.Up,
                ),
            ),
        )
    }

    @Test
    fun `last trade unchanged`() {
        verifyOrderBook(
            btcEthMarket,
            ordersInDb = listOf(
                Order(
                    OrderId("order_1"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.50".toBigDecimal(),
                    amount = "1".toBigDecimal(),
                ),
                Order(
                    OrderId("order_2"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Partial,
                    price = "17.55".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_3"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Filled,
                    price = "17.45".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_4"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Market,
                    OrderStatus.Filled,
                    price = null,
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_5"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Filled,
                    price = "17.40".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_6"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Market,
                    OrderStatus.Filled,
                    price = null,
                    amount = "2".toBigDecimal(),
                ),
            ),
            tradesInDb = listOf(
                Trade(
                    btcEthMarket,
                    responseSequence = 1,
                    buyOrder = OrderId("order_5"),
                    sellOrder = OrderId("order_6"),
                    amount = "2".toBigDecimal(),
                    price = "17.40".toBigDecimal(),
                ),
                Trade(
                    btcEthMarket,
                    responseSequence = 1,
                    buyOrder = OrderId("order_3"),
                    sellOrder = OrderId("order_4"),
                    amount = "2".toBigDecimal(),
                    price = "17.40".toBigDecimal(),
                ),
            ),
            expected = OrderBookSnapshot(
                bids = listOf(
                    OrderBookSnapshot.Entry(price = BigDecimal("17.500"), size = "1".toBigDecimal()),
                ),
                asks = listOf(
                    OrderBookSnapshot.Entry(price = BigDecimal("17.550"), size = "2".toBigDecimal()),
                ),
                last = OrderBookSnapshot.LastTrade(
                    price = BigDecimal("17.400"),
                    direction = OrderBookSnapshot.LastTradeDirection.Unchanged,
                ),
            ),
        )
    }

    @Test
    fun `last trade's price is a weighted average for all executions in latest match`() {
        verifyOrderBook(
            btcEthMarket,
            ordersInDb = listOf(
                Order(
                    OrderId("order_1"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.50".toBigDecimal(),
                    amount = "1".toBigDecimal(),
                ),
                Order(
                    OrderId("order_2"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Partial,
                    price = "17.55".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_3"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Filled,
                    price = "17.45".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_4"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Filled,
                    price = "17.40".toBigDecimal(),
                    amount = "3".toBigDecimal(),
                ),
                Order(
                    OrderId("order_5"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Market,
                    OrderStatus.Filled,
                    price = null,
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_6"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Filled,
                    price = "17.35".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_7"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Market,
                    OrderStatus.Filled,
                    price = null,
                    amount = "2".toBigDecimal(),
                ),
            ),
            tradesInDb = listOf(
                Trade(
                    btcEthMarket,
                    responseSequence = 1,
                    buyOrder = OrderId("order_6"),
                    sellOrder = OrderId("order_7"),
                    amount = "2".toBigDecimal(),
                    price = "17.35".toBigDecimal(),
                ),
                Trade(
                    btcEthMarket,
                    responseSequence = 2,
                    buyOrder = OrderId("order_3"),
                    sellOrder = OrderId("order_5"),
                    amount = "2".toBigDecimal(),
                    price = "17.45".toBigDecimal(),
                ),
                Trade(
                    btcEthMarket,
                    responseSequence = 2,
                    buyOrder = OrderId("order_4"),
                    sellOrder = OrderId("order_5"),
                    amount = "3".toBigDecimal(),
                    price = "17.40".toBigDecimal(),
                ),
            ),
            expected = OrderBookSnapshot(
                bids = listOf(
                    OrderBookSnapshot.Entry(price = BigDecimal("17.500"), size = "1".toBigDecimal()),
                ),
                asks = listOf(
                    OrderBookSnapshot.Entry(price = BigDecimal("17.550"), size = "2".toBigDecimal()),
                ),
                last = OrderBookSnapshot.LastTrade(
                    price = BigDecimal("17.420"),
                    direction = OrderBookSnapshot.LastTradeDirection.Up,
                ),
            ),
        )
    }

    @Test
    fun `orders and trades from other markets are not considered`() {
        verifyOrderBook(
            btcEthMarket,
            ordersInDb = listOf(
                Order(
                    OrderId("order_1"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.50".toBigDecimal(),
                    amount = "1".toBigDecimal(),
                ),
                Order(
                    OrderId("order_2"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Partial,
                    price = "17.55".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_3"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Filled,
                    price = "17.45".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_4"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Market,
                    OrderStatus.Filled,
                    price = null,
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_5"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Filled,
                    price = "17.40".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_6"),
                    wallets.random(),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Market,
                    OrderStatus.Filled,
                    price = null,
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_7"),
                    wallets.random(),
                    ethUsdcMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "3000".toBigDecimal(),
                    amount = "1".toBigDecimal(),
                ),
                Order(
                    OrderId("order_8"),
                    wallets.random(),
                    ethUsdcMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "3001".toBigDecimal(),
                    amount = "1".toBigDecimal(),
                ),
                Order(
                    OrderId("order_9"),
                    wallets.random(),
                    ethUsdcMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Filled,
                    price = "3000".toBigDecimal(),
                    amount = "1".toBigDecimal(),
                ),
                Order(
                    OrderId("order_10"),
                    wallets.random(),
                    ethUsdcMarket,
                    OrderSide.Sell,
                    OrderType.Market,
                    OrderStatus.Filled,
                    price = null,
                    amount = "1".toBigDecimal(),
                ),
            ),
            tradesInDb = listOf(
                Trade(
                    btcEthMarket,
                    responseSequence = 1,
                    buyOrder = OrderId("order_5"),
                    sellOrder = OrderId("order_6"),
                    amount = "2".toBigDecimal(),
                    price = "17.40".toBigDecimal(),
                ),
                Trade(
                    btcEthMarket,
                    responseSequence = 1,
                    buyOrder = OrderId("order_3"),
                    sellOrder = OrderId("order_4"),
                    amount = "2".toBigDecimal(),
                    price = "17.45".toBigDecimal(),
                ),
                Trade(
                    ethUsdcMarket,
                    responseSequence = 1,
                    buyOrder = OrderId("order_9"),
                    sellOrder = OrderId("order_10"),
                    amount = "1".toBigDecimal(),
                    price = "3000".toBigDecimal(),
                ),
            ),
            expected = OrderBookSnapshot(
                bids = listOf(
                    OrderBookSnapshot.Entry(price = BigDecimal("17.500"), size = "1".toBigDecimal()),
                ),
                asks = listOf(
                    OrderBookSnapshot.Entry(price = BigDecimal("17.550"), size = "2".toBigDecimal()),
                ),
                last = OrderBookSnapshot.LastTrade(
                    price = BigDecimal("17.450"),
                    direction = OrderBookSnapshot.LastTradeDirection.Up,
                ),
            ),
        )
    }

    private fun verifyOrderBook(
        marketId: MarketId,
        ordersInDb: List<Order>,
        tradesInDb: List<Trade>,
        expected: OrderBookSnapshot,
    ) {
        verifyOrderBook(marketId, ordersInDb, tradesInDb, expected, OrderBookSnapshot::get)
    }
}
