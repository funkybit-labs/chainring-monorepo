package co.chainring.core.model.db

import co.chainring.apps.api.model.websocket.LastTrade
import co.chainring.apps.api.model.websocket.LastTradeDirection
import co.chainring.apps.api.model.websocket.OrderBook
import co.chainring.apps.api.model.websocket.OrderBookEntry
import co.chainring.core.model.SequencerOrderId
import co.chainring.core.model.Symbol
import co.chainring.core.utils.generateHexString
import co.chainring.core.utils.toFundamentalUnits
import co.chainring.testutils.TestWithDb
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class OrderBookTest : TestWithDb() {
    private val btcEthMarket = MarketId("BTC/ETH")
    private val ethUsdcMarket = MarketId("ETH/USDC")
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
            expected = OrderBook(
                marketId = btcEthMarket,
                buy = emptyList(),
                sell = emptyList(),
                last = LastTrade(
                    price = "0.000",
                    direction = LastTradeDirection.Unchanged,
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
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.55".toBigDecimal(),
                    amount = "1.2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_2"),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.55".toBigDecimal(),
                    amount = "1.3".toBigDecimal(),
                ),
                Order(
                    OrderId("order_3"),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.50".toBigDecimal(),
                    amount = "1.5".toBigDecimal(),
                ),
                Order(
                    OrderId("order_4"),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.40".toBigDecimal(),
                    amount = "5".toBigDecimal(),
                ),
                Order(
                    OrderId("order_5"),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Partial,
                    price = "17.40".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
            ),
            tradesInDb = emptyList(),
            expected = OrderBook(
                marketId = btcEthMarket,
                buy = listOf(
                    OrderBookEntry(price = "17.550", size = "2.5".toBigDecimal()),
                    OrderBookEntry(price = "17.500", size = "1.5".toBigDecimal()),
                    OrderBookEntry(price = "17.400", size = "7".toBigDecimal()),
                ),
                sell = emptyList(),
                last = LastTrade(
                    price = "0.000",
                    direction = LastTradeDirection.Unchanged,
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
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.35".toBigDecimal(),
                    amount = "1.2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_2"),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Partial,
                    price = "17.35".toBigDecimal(),
                    amount = "1.3".toBigDecimal(),
                ),
                Order(
                    OrderId("order_3"),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.30".toBigDecimal(),
                    amount = "5".toBigDecimal(),
                ),
            ),
            tradesInDb = emptyList(),
            expected = OrderBook(
                marketId = btcEthMarket,
                buy = emptyList(),
                sell = listOf(
                    OrderBookEntry(price = "17.350", size = "2.5".toBigDecimal()),
                    OrderBookEntry(price = "17.300", size = "5".toBigDecimal()),
                ),
                last = LastTrade(
                    price = "0.000",
                    direction = LastTradeDirection.Unchanged,
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
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.55".toBigDecimal(),
                    amount = "1.2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_2"),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.55".toBigDecimal(),
                    amount = "1.3".toBigDecimal(),
                ),
                Order(
                    OrderId("order_3"),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.50".toBigDecimal(),
                    amount = "1.5".toBigDecimal(),
                ),
                Order(
                    OrderId("order_4"),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.40".toBigDecimal(),
                    amount = "5".toBigDecimal(),
                ),
                Order(
                    OrderId("order_5"),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Partial,
                    price = "17.40".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_6"),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.35".toBigDecimal(),
                    amount = "1.2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_7"),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Partial,
                    price = "17.35".toBigDecimal(),
                    amount = "1.3".toBigDecimal(),
                ),
                Order(
                    OrderId("order_8"),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.30".toBigDecimal(),
                    amount = "5".toBigDecimal(),
                ),
            ),
            tradesInDb = emptyList(),
            expected = OrderBook(
                marketId = btcEthMarket,
                buy = listOf(
                    OrderBookEntry(price = "17.550", size = "2.5".toBigDecimal()),
                    OrderBookEntry(price = "17.500", size = "1.5".toBigDecimal()),
                    OrderBookEntry(price = "17.400", size = "7".toBigDecimal()),
                ),
                sell = listOf(
                    OrderBookEntry(price = "17.350", size = "2.5".toBigDecimal()),
                    OrderBookEntry(price = "17.300", size = "5".toBigDecimal()),
                ),
                last = LastTrade(
                    price = "0.000",
                    direction = LastTradeDirection.Unchanged,
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
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.55".toBigDecimal(),
                    amount = "1.2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_2"),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.55".toBigDecimal(),
                    amount = "1.3".toBigDecimal(),
                ),
                Order(
                    OrderId("order_3"),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.50".toBigDecimal(),
                    amount = "1.5".toBigDecimal(),
                ),
                Order(
                    OrderId("order_4"),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.40".toBigDecimal(),
                    amount = "5".toBigDecimal(),
                ),
                Order(
                    OrderId("order_5"),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Partial,
                    price = "17.40".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_6"),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Market,
                    OrderStatus.Open,
                    price = "17.35".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_7"),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Filled,
                    price = "17.35".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_8"),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Cancelled,
                    price = "17.35".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_9"),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Expired,
                    price = "17.35".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_11"),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Rejected,
                    price = "17.35".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_12"),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.35".toBigDecimal(),
                    amount = "1.2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_13"),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Partial,
                    price = "17.35".toBigDecimal(),
                    amount = "1.3".toBigDecimal(),
                ),
                Order(
                    OrderId("order_14"),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.30".toBigDecimal(),
                    amount = "5".toBigDecimal(),
                ),
                Order(
                    OrderId("order_15"),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Market,
                    OrderStatus.Open,
                    price = "17.40".toBigDecimal(),
                    amount = "5".toBigDecimal(),
                ),
                Order(
                    OrderId("order_16"),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Market,
                    OrderStatus.Filled,
                    price = "17.35".toBigDecimal(),
                    amount = "5".toBigDecimal(),
                ),
                Order(
                    OrderId("order_17"),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Market,
                    OrderStatus.Cancelled,
                    price = "17.35".toBigDecimal(),
                    amount = "5".toBigDecimal(),
                ),
                Order(
                    OrderId("order_18"),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Market,
                    OrderStatus.Expired,
                    price = "17.35".toBigDecimal(),
                    amount = "5".toBigDecimal(),
                ),
                Order(
                    OrderId("order_19"),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Market,
                    OrderStatus.Rejected,
                    price = "17.35".toBigDecimal(),
                    amount = "5".toBigDecimal(),
                ),
            ),
            tradesInDb = emptyList(),
            expected = OrderBook(
                marketId = btcEthMarket,
                buy = listOf(
                    OrderBookEntry(price = "17.550", size = "2.5".toBigDecimal()),
                    OrderBookEntry(price = "17.500", size = "1.5".toBigDecimal()),
                    OrderBookEntry(price = "17.400", size = "7".toBigDecimal()),
                ),
                sell = listOf(
                    OrderBookEntry(price = "17.350", size = "2.5".toBigDecimal()),
                    OrderBookEntry(price = "17.300", size = "5".toBigDecimal()),
                ),
                last = LastTrade(
                    price = "0.000",
                    direction = LastTradeDirection.Unchanged,
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
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.50".toBigDecimal(),
                    amount = "1".toBigDecimal(),
                ),
                Order(
                    OrderId("order_2"),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Partial,
                    price = "17.55".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_3"),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Filled,
                    price = "17.40".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_4"),
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
                    timeSinceHappened = 1.seconds,
                    buyOrder = OrderId("order_3"),
                    sellOrder = OrderId("order_4"),
                    amount = "2".toBigDecimal(),
                    price = "17.40".toBigDecimal(),
                ),
            ),
            expected = OrderBook(
                marketId = btcEthMarket,
                buy = listOf(
                    OrderBookEntry(price = "17.500", size = "1".toBigDecimal()),
                ),
                sell = listOf(
                    OrderBookEntry(price = "17.550", size = "2".toBigDecimal()),
                ),
                last = LastTrade(
                    price = "17.400",
                    direction = LastTradeDirection.Up,
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
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.50".toBigDecimal(),
                    amount = "1".toBigDecimal(),
                ),
                Order(
                    OrderId("order_2"),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Partial,
                    price = "17.55".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_3"),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Filled,
                    price = "17.45".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_4"),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Market,
                    OrderStatus.Filled,
                    price = null,
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_5"),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Filled,
                    price = "17.40".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_6"),
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
                    timeSinceHappened = 2.seconds,
                    buyOrder = OrderId("order_3"),
                    sellOrder = OrderId("order_4"),
                    amount = "2".toBigDecimal(),
                    price = "17.45".toBigDecimal(),
                ),
                Trade(
                    btcEthMarket,
                    timeSinceHappened = 1.seconds,
                    buyOrder = OrderId("order_5"),
                    sellOrder = OrderId("order_6"),
                    amount = "2".toBigDecimal(),
                    price = "17.40".toBigDecimal(),
                ),
            ),
            expected = OrderBook(
                marketId = btcEthMarket,
                buy = listOf(
                    OrderBookEntry(price = "17.500", size = "1".toBigDecimal()),
                ),
                sell = listOf(
                    OrderBookEntry(price = "17.550", size = "2".toBigDecimal()),
                ),
                last = LastTrade(
                    price = "17.400",
                    direction = LastTradeDirection.Down,
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
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.50".toBigDecimal(),
                    amount = "1".toBigDecimal(),
                ),
                Order(
                    OrderId("order_2"),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Partial,
                    price = "17.55".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_3"),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Filled,
                    price = "17.45".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_4"),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Market,
                    OrderStatus.Filled,
                    price = null,
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_5"),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Filled,
                    price = "17.40".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_6"),
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
                    timeSinceHappened = 2.seconds,
                    buyOrder = OrderId("order_5"),
                    sellOrder = OrderId("order_6"),
                    amount = "2".toBigDecimal(),
                    price = "17.40".toBigDecimal(),
                ),
                Trade(
                    btcEthMarket,
                    timeSinceHappened = 1.seconds,
                    buyOrder = OrderId("order_3"),
                    sellOrder = OrderId("order_4"),
                    amount = "2".toBigDecimal(),
                    price = "17.45".toBigDecimal(),
                ),
            ),
            expected = OrderBook(
                marketId = btcEthMarket,
                buy = listOf(
                    OrderBookEntry(price = "17.500", size = "1".toBigDecimal()),
                ),
                sell = listOf(
                    OrderBookEntry(price = "17.550", size = "2".toBigDecimal()),
                ),
                last = LastTrade(
                    price = "17.450",
                    direction = LastTradeDirection.Up,
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
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.50".toBigDecimal(),
                    amount = "1".toBigDecimal(),
                ),
                Order(
                    OrderId("order_2"),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Partial,
                    price = "17.55".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_3"),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Filled,
                    price = "17.45".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_4"),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Market,
                    OrderStatus.Filled,
                    price = null,
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_5"),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Filled,
                    price = "17.40".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_6"),
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
                    timeSinceHappened = 2.seconds,
                    buyOrder = OrderId("order_5"),
                    sellOrder = OrderId("order_6"),
                    amount = "2".toBigDecimal(),
                    price = "17.40".toBigDecimal(),
                ),
                Trade(
                    btcEthMarket,
                    timeSinceHappened = 1.seconds,
                    buyOrder = OrderId("order_3"),
                    sellOrder = OrderId("order_4"),
                    amount = "2".toBigDecimal(),
                    price = "17.40".toBigDecimal(),
                ),
            ),
            expected = OrderBook(
                marketId = btcEthMarket,
                buy = listOf(
                    OrderBookEntry(price = "17.500", size = "1".toBigDecimal()),
                ),
                sell = listOf(
                    OrderBookEntry(price = "17.550", size = "2".toBigDecimal()),
                ),
                last = LastTrade(
                    price = "17.400",
                    direction = LastTradeDirection.Unchanged,
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
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.50".toBigDecimal(),
                    amount = "1".toBigDecimal(),
                ),
                Order(
                    OrderId("order_2"),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Partial,
                    price = "17.55".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_3"),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Filled,
                    price = "17.45".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_4"),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Filled,
                    price = "17.40".toBigDecimal(),
                    amount = "3".toBigDecimal(),
                ),
                Order(
                    OrderId("order_5"),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Market,
                    OrderStatus.Filled,
                    price = null,
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_6"),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Filled,
                    price = "17.35".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_7"),
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
                    timeSinceHappened = 1.seconds,
                    buyOrder = OrderId("order_3"),
                    sellOrder = OrderId("order_5"),
                    amount = "2".toBigDecimal(),
                    price = "17.45".toBigDecimal(),
                ),
                Trade(
                    btcEthMarket,
                    timeSinceHappened = 1.seconds,
                    buyOrder = OrderId("order_4"),
                    sellOrder = OrderId("order_5"),
                    amount = "3".toBigDecimal(),
                    price = "17.40".toBigDecimal(),
                ),
                Trade(
                    btcEthMarket,
                    timeSinceHappened = 2.seconds,
                    buyOrder = OrderId("order_6"),
                    sellOrder = OrderId("order_7"),
                    amount = "2".toBigDecimal(),
                    price = "17.35".toBigDecimal(),
                ),
            ),
            expected = OrderBook(
                marketId = btcEthMarket,
                buy = listOf(
                    OrderBookEntry(price = "17.500", size = "1".toBigDecimal()),
                ),
                sell = listOf(
                    OrderBookEntry(price = "17.550", size = "2".toBigDecimal()),
                ),
                last = LastTrade(
                    price = "17.420",
                    direction = LastTradeDirection.Up,
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
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.50".toBigDecimal(),
                    amount = "1".toBigDecimal(),
                ),
                Order(
                    OrderId("order_2"),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Partial,
                    price = "17.55".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_3"),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Filled,
                    price = "17.45".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_4"),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Market,
                    OrderStatus.Filled,
                    price = null,
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_5"),
                    btcEthMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Filled,
                    price = "17.40".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_6"),
                    btcEthMarket,
                    OrderSide.Sell,
                    OrderType.Market,
                    OrderStatus.Filled,
                    price = null,
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_7"),
                    ethUsdcMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "3000".toBigDecimal(),
                    amount = "1".toBigDecimal(),
                ),
                Order(
                    OrderId("order_8"),
                    ethUsdcMarket,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "3001".toBigDecimal(),
                    amount = "1".toBigDecimal(),
                ),
                Order(
                    OrderId("order_9"),
                    ethUsdcMarket,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Filled,
                    price = "3000".toBigDecimal(),
                    amount = "1".toBigDecimal(),
                ),
                Order(
                    OrderId("order_10"),
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
                    timeSinceHappened = 3.seconds,
                    buyOrder = OrderId("order_5"),
                    sellOrder = OrderId("order_6"),
                    amount = "2".toBigDecimal(),
                    price = "17.40".toBigDecimal(),
                ),
                Trade(
                    btcEthMarket,
                    timeSinceHappened = 2.seconds,
                    buyOrder = OrderId("order_3"),
                    sellOrder = OrderId("order_4"),
                    amount = "2".toBigDecimal(),
                    price = "17.45".toBigDecimal(),
                ),
                Trade(
                    ethUsdcMarket,
                    timeSinceHappened = 1.seconds,
                    buyOrder = OrderId("order_9"),
                    sellOrder = OrderId("order_10"),
                    amount = "1".toBigDecimal(),
                    price = "3000".toBigDecimal(),
                ),
            ),
            expected = OrderBook(
                marketId = btcEthMarket,
                buy = listOf(
                    OrderBookEntry(price = "17.500", size = "1".toBigDecimal()),
                ),
                sell = listOf(
                    OrderBookEntry(price = "17.550", size = "2".toBigDecimal()),
                ),
                last = LastTrade(
                    price = "17.450",
                    direction = LastTradeDirection.Up,
                ),
            ),
        )
    }

    data class Order(
        val id: OrderId,
        val market: MarketId,
        val side: OrderSide,
        val type: OrderType,
        val status: OrderStatus,
        val price: BigDecimal?,
        val amount: BigDecimal,
    )

    data class Trade(
        val market: MarketId,
        val timeSinceHappened: Duration,
        val buyOrder: OrderId,
        val sellOrder: OrderId,
        val amount: BigDecimal,
        val price: BigDecimal,
    )

    private fun verifyOrderBook(marketId: MarketId, ordersInDb: List<Order>, tradesInDb: List<Trade>, expected: OrderBook) {
        transaction {
            val market = MarketEntity[marketId]

            ordersInDb.forEachIndexed { i, order ->
                createOrder(
                    MarketEntity[order.market], wallets.random(),
                    order.side, order.type, order.amount, order.price, order.status,
                    SequencerOrderId(i.toLong()),
                    order.id,
                )
            }

            TransactionManager.current().commit()

            val now = Clock.System.now()
            tradesInDb.forEach { trade ->
                val tradeMarket = MarketEntity[trade.market]

                val tradeEntity = TradeEntity.create(
                    now.minus(trade.timeSinceHappened),
                    tradeMarket,
                    amount = trade.amount.toFundamentalUnits(tradeMarket.baseSymbol.decimals),
                    price = trade.price,
                    tradeHash = generateHexString(32),
                )

                listOf(trade.buyOrder, trade.sellOrder).forEach { orderId ->
                    val order = OrderEntity[orderId]
                    assertTrue(
                        order.status == OrderStatus.Filled || order.status == OrderStatus.Partial,
                        "Order must be filled or partially filled",
                    )
                    OrderExecutionEntity.create(
                        timestamp = tradeEntity.timestamp,
                        orderEntity = order,
                        tradeEntity = tradeEntity,
                        role = if (order.type == OrderType.Market) ExecutionRole.Taker else ExecutionRole.Maker,
                        feeAmount = BigInteger.ZERO,
                        feeSymbol = Symbol(order.market.quoteSymbol.name),
                    )
                }
            }

            TransactionManager.current().commit()

            assertEquals(expected, OrderEntity.getOrderBook(market))
        }
    }
}
