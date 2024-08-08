package xyz.funkybit.integrationtests.api

import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.extension.ExtendWith
import xyz.funkybit.apps.api.model.ApiError
import xyz.funkybit.apps.api.model.GetOrderBookApiResponse
import xyz.funkybit.apps.api.model.ReasonCode
import xyz.funkybit.apps.api.model.websocket.OrderBook
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.OrderId
import xyz.funkybit.core.model.db.OrderSide
import xyz.funkybit.core.model.db.OrderStatus
import xyz.funkybit.core.model.db.OrderType
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.testutils.OrderBaseTest
import xyz.funkybit.integrationtests.utils.TestApiClient
import xyz.funkybit.integrationtests.utils.assertError
import xyz.funkybit.testfixtures.DbTestHelpers.createWallet
import xyz.funkybit.testfixtures.OrderBookTestHelper.Order
import xyz.funkybit.testfixtures.OrderBookTestHelper.verifyOrderBook
import kotlin.test.Test

@ExtendWith(AppUnderTestRunner::class)
class OrderBookTest : OrderBaseTest() {
    @Test
    fun `get order book snapshot`() {
        val wallets = transaction { (1..5).map { createWallet() } }

        val apiClient = TestApiClient()

        // unknown market
        apiClient
            .tryGetOrderBook(MarketId("FOO/BAR"))
            .assertError(ApiError(ReasonCode.MarketNotFound, "Unknown market"))

        // empty order book
        verifyOrderBook(
            btcEthMarket.id,
            ordersInDb = emptyList(),
            tradesInDb = emptyList(),
            expected = GetOrderBookApiResponse(
                marketId = btcEthMarket.id,
                bids = emptyList(),
                asks = emptyList(),
                last = OrderBook.LastTrade(
                    price = "0.000",
                    direction = OrderBook.LastTradeDirection.Unchanged,
                ),
            ),
            getOrderBook = { marketId ->
                apiClient.getOrderBook(marketId)
            },
        )

        // non-empty order book
        verifyOrderBook(
            btcEthMarket.id,
            ordersInDb = listOf(
                Order(
                    OrderId("order_1"),
                    wallets.random(),
                    btcEthMarket.id,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.55".toBigDecimal(),
                    amount = "1.2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_2"),
                    wallets.random(),
                    btcEthMarket.id,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.55".toBigDecimal(),
                    amount = "1.3".toBigDecimal(),
                ),
                Order(
                    OrderId("order_3"),
                    wallets.random(),
                    btcEthMarket.id,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.50".toBigDecimal(),
                    amount = "1.5".toBigDecimal(),
                ),
                Order(
                    OrderId("order_4"),
                    wallets.random(),
                    btcEthMarket.id,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.40".toBigDecimal(),
                    amount = "5".toBigDecimal(),
                ),
                Order(
                    OrderId("order_5"),
                    wallets.random(),
                    btcEthMarket.id,
                    OrderSide.Buy,
                    OrderType.Limit,
                    OrderStatus.Partial,
                    price = "17.40".toBigDecimal(),
                    amount = "2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_6"),
                    wallets.random(),
                    btcEthMarket.id,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.35".toBigDecimal(),
                    amount = "1.2".toBigDecimal(),
                ),
                Order(
                    OrderId("order_7"),
                    wallets.random(),
                    btcEthMarket.id,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Partial,
                    price = "17.35".toBigDecimal(),
                    amount = "1.3".toBigDecimal(),
                ),
                Order(
                    OrderId("order_8"),
                    wallets.random(),
                    btcEthMarket.id,
                    OrderSide.Sell,
                    OrderType.Limit,
                    OrderStatus.Open,
                    price = "17.30".toBigDecimal(),
                    amount = "5".toBigDecimal(),
                ),
            ),
            tradesInDb = emptyList(),
            expected = GetOrderBookApiResponse(
                marketId = btcEthMarket.id,
                bids = listOf(
                    OrderBook.Entry(price = "17.550", size = "2.5".toBigDecimal()),
                    OrderBook.Entry(price = "17.500", size = "1.5".toBigDecimal()),
                    OrderBook.Entry(price = "17.400", size = "7".toBigDecimal()),
                ),
                asks = listOf(
                    OrderBook.Entry(price = "17.350", size = "2.5".toBigDecimal()),
                    OrderBook.Entry(price = "17.300", size = "5".toBigDecimal()),
                ),
                last = OrderBook.LastTrade(
                    price = "0.000",
                    direction = OrderBook.LastTradeDirection.Unchanged,
                ),
            ),
            getOrderBook = { marketId ->
                apiClient.getOrderBook(marketId)
            },
        )
    }
}
