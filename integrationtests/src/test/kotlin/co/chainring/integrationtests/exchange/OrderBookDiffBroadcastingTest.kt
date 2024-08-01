package co.chainring.integrationtests.exchange

import co.chainring.apps.api.model.BatchOrdersApiRequest
import co.chainring.apps.api.model.CancelOrderApiRequest
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.OrderAmount
import co.chainring.apps.api.model.RequestStatus
import co.chainring.apps.api.model.websocket.OrderBook
import co.chainring.apps.api.model.websocket.OrderBookDiff
import co.chainring.apps.api.model.websocket.SubscriptionTopic
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.utils.generateOrderNonce
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.testutils.OrderBaseTest
import co.chainring.integrationtests.utils.AssetAmount
import co.chainring.integrationtests.utils.TestApiClient
import co.chainring.integrationtests.utils.assertBalancesMessageReceived
import co.chainring.integrationtests.utils.assertMessageReceived
import co.chainring.integrationtests.utils.assertMyOrderCreatedMessageReceived
import co.chainring.integrationtests.utils.assertMyOrderUpdatedMessageReceived
import co.chainring.integrationtests.utils.assertMyTradesCreatedMessageReceived
import co.chainring.integrationtests.utils.assertOrderBookDiffMessageReceived
import co.chainring.integrationtests.utils.assertOrderBookMessageReceived
import co.chainring.integrationtests.utils.blocking
import co.chainring.integrationtests.utils.subscribeToIncrementalOrderBook
import co.chainring.integrationtests.utils.subscribeToOrderBook
import co.chainring.integrationtests.utils.toCancelOrderRequest
import org.http4k.client.WebsocketClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.math.BigInteger

@ExtendWith(AppUnderTestRunner::class)
class OrderBookDiffBroadcastingTest : OrderBaseTest() {
    @Test
    fun `order book diffs are broadcasted to subscribers`() {
        val market = btcUsdcMarket
        val baseSymbol = btc
        val quoteSymbol = usdc

        val (makerApiClient, makerWallet, makerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(baseSymbol, "0.5"),
                AssetAmount(quoteSymbol, "10000"),
            ),
            deposits = listOf(
                AssetAmount(baseSymbol, "0.4"),
                AssetAmount(quoteSymbol, "10000"),
            ),
            subscribeToOrderBook = false,
            subscribeToPrices = false,
            subscribeToLimits = false,
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
            subscribeToPrices = false,
            subscribeToLimits = false,
        )

        val orderBookObserver = WebsocketClient.blocking(TestApiClient().authToken).apply {
            subscribeToOrderBook(market.id)
        }
        val incrementalOrderBookObserver = WebsocketClient.blocking(TestApiClient().authToken).apply {
            subscribeToIncrementalOrderBook(market.id)
        }

        val createBatchLimitOrders = makerApiClient.batchOrders(
            BatchOrdersApiRequest(
                marketId = market.id,
                createOrders = listOf("0.001", "0.002").map {
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
                } + listOf("0.002").map {
                    makerWallet.signOrder(
                        CreateOrderApiRequest.Limit(
                            nonce = generateOrderNonce(),
                            marketId = market.id,
                            side = OrderSide.Sell,
                            amount = OrderAmount.Fixed(AssetAmount(baseSymbol, it).inFundamentalUnits),
                            price = BigDecimal("68300.000"),
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

        val batchOrderResponse = makerApiClient.batchOrders(
            BatchOrdersApiRequest(
                marketId = market.id,
                createOrders = listOf("0.004", "0.005").map {
                    makerWallet.signOrder(
                        CreateOrderApiRequest.Limit(
                            nonce = generateOrderNonce(),
                            marketId = market.id,
                            side = OrderSide.Buy,
                            amount = OrderAmount.Fixed(AssetAmount(baseSymbol, it).inFundamentalUnits),
                            price = BigDecimal("68200.000"),
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    )
                } + listOf("0.006").map {
                    makerWallet.signOrder(
                        CreateOrderApiRequest.Limit(
                            nonce = generateOrderNonce(),
                            marketId = market.id,
                            side = OrderSide.Buy,
                            amount = OrderAmount.Fixed(AssetAmount(baseSymbol, it).inFundamentalUnits),
                            price = BigDecimal("68100.000"),
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

        takerApiClient.createMarketOrder(
            market,
            OrderSide.Buy,
            amount = AssetAmount(baseSymbol, "0.004").amount,
            takerWallet,
        )

        takerWsClient.apply {
            assertMyOrderCreatedMessageReceived()
            assertMyTradesCreatedMessageReceived { msg ->
                assertEquals(2, msg.trades.size)
            }
            assertMyOrderUpdatedMessageReceived()
            assertBalancesMessageReceived()
        }

        makerWsClient.apply {
            assertMyTradesCreatedMessageReceived { msg ->
                assertEquals(2, msg.trades.size)
            }
            repeat(2) { assertMyOrderUpdatedMessageReceived() }
            assertBalancesMessageReceived()
        }

        orderBookObserver.apply {
            assertOrderBookMessageReceived(
                market.id,
                OrderBook(
                    marketId = market.id,
                    buy = emptyList(),
                    sell = emptyList(),
                    last = OrderBook.LastTrade("0.0", OrderBook.LastTradeDirection.Unchanged),
                ),
            )

            assertOrderBookMessageReceived(
                market.id,
                OrderBook(
                    marketId = market.id,
                    buy = emptyList(),
                    sell = listOf(
                        OrderBook.Entry(price = "68400.0", size = "0.003".toBigDecimal()),
                        OrderBook.Entry(price = "68300.0", size = "0.002".toBigDecimal()),
                    ),
                    last = OrderBook.LastTrade("0.0", OrderBook.LastTradeDirection.Unchanged),
                ),
            )

            assertOrderBookMessageReceived(
                market.id,
                OrderBook(
                    marketId = market.id,
                    buy = listOf(
                        OrderBook.Entry(price = "68200.0", size = "0.009".toBigDecimal()),
                        OrderBook.Entry(price = "68100.0", size = "0.006".toBigDecimal()),
                    ),
                    sell = listOf(
                        OrderBook.Entry(price = "68400.0", size = "0.003".toBigDecimal()),
                    ),
                    last = OrderBook.LastTrade("0.0", OrderBook.LastTradeDirection.Unchanged),
                ),
            )

            assertOrderBookMessageReceived(
                market.id,
                OrderBook(
                    marketId = market.id,
                    buy = listOf(
                        OrderBook.Entry(price = "68200.0", size = "0.009".toBigDecimal()),
                        OrderBook.Entry(price = "68100.0", size = "0.006".toBigDecimal()),
                    ),
                    sell = emptyList(),
                    last = OrderBook.LastTrade("68400.0", OrderBook.LastTradeDirection.Up),
                ),
            )
        }

        incrementalOrderBookObserver.apply {
            assertMessageReceived<OrderBook>(SubscriptionTopic.IncrementalOrderBook(market.id)) { msg ->
                assertEquals(
                    OrderBook(
                        marketId = market.id,
                        buy = emptyList(),
                        sell = emptyList(),
                        last = OrderBook.LastTrade("0.0", OrderBook.LastTradeDirection.Unchanged),
                    ),
                    msg,
                )
            }

            assertOrderBookDiffMessageReceived(
                market.id,
                OrderBookDiff(
                    sequenceNumber = 1,
                    marketId = market.id,
                    buy = emptyList(),
                    sell = listOf(
                        OrderBook.Entry(price = "68400.0", size = "0.003".toBigDecimal()),
                        OrderBook.Entry(price = "68300.0", size = "0.002".toBigDecimal()),
                    ),
                    last = null,
                ),
            )

            assertOrderBookDiffMessageReceived(
                market.id,
                OrderBookDiff(
                    sequenceNumber = 2,
                    marketId = market.id,
                    buy = listOf(
                        OrderBook.Entry(price = "68200.0", size = "0.009".toBigDecimal()),
                        OrderBook.Entry(price = "68100.0", size = "0.006".toBigDecimal()),
                    ),
                    sell = listOf(
                        OrderBook.Entry(price = "68300.0", size = "0.0".toBigDecimal()),
                    ),
                    last = null,
                ),
            )

            assertOrderBookDiffMessageReceived(
                market.id,
                OrderBookDiff(
                    sequenceNumber = 3,
                    marketId = market.id,
                    buy = emptyList(),
                    sell = listOf(
                        OrderBook.Entry(price = "68400.0", size = "0.0".toBigDecimal()),
                    ),
                    last = OrderBook.LastTrade("68400.0", OrderBook.LastTradeDirection.Up),
                ),
            )
        }

        makerWsClient.close()
        orderBookObserver.close()
        incrementalOrderBookObserver.close()
    }
}
