package xyz.funkybit.integrationtests.exchange

import org.http4k.client.WebsocketClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import xyz.funkybit.apps.api.model.BatchOrdersApiRequest
import xyz.funkybit.apps.api.model.CancelOrderApiRequest
import xyz.funkybit.apps.api.model.CreateOrderApiRequest
import xyz.funkybit.apps.api.model.OrderAmount
import xyz.funkybit.apps.api.model.RequestStatus
import xyz.funkybit.apps.api.model.websocket.OrderBook
import xyz.funkybit.apps.api.model.websocket.OrderBookDiff
import xyz.funkybit.apps.api.model.websocket.SubscriptionTopic
import xyz.funkybit.core.model.EvmSignature
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.OrderId
import xyz.funkybit.core.model.db.OrderSide
import xyz.funkybit.core.utils.generateOrderNonce
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.testutils.OrderBaseTest
import xyz.funkybit.integrationtests.utils.AssetAmount
import xyz.funkybit.integrationtests.utils.TestApiClient
import xyz.funkybit.integrationtests.utils.assertBalancesMessageReceived
import xyz.funkybit.integrationtests.utils.assertMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyOrdersCreatedMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyOrdersUpdatedMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyTradesCreatedMessageReceived
import xyz.funkybit.integrationtests.utils.assertOrderBookDiffMessageReceived
import xyz.funkybit.integrationtests.utils.assertOrderBookMessageReceived
import xyz.funkybit.integrationtests.utils.blocking
import xyz.funkybit.integrationtests.utils.subscribeToIncrementalOrderBook
import xyz.funkybit.integrationtests.utils.subscribeToOrderBook
import xyz.funkybit.integrationtests.utils.toCancelOrderRequest
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
        makerWsClient.assertMyOrdersCreatedMessageReceived {
            assertEquals(3, it.orders.size)
        }

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

        makerWsClient.assertMyOrdersCreatedMessageReceived {
            assertEquals(3, it.orders.size)
        }
        makerWsClient.assertMyOrdersUpdatedMessageReceived()

        val marketOrderResponse = takerApiClient.createMarketOrder(
            market,
            OrderSide.Buy,
            amount = AssetAmount(baseSymbol, "0.004").amount,
            takerWallet,
        )

        takerWsClient.apply {
            assertMyOrdersCreatedMessageReceived()
            assertMyTradesCreatedMessageReceived { msg ->
                assertEquals(2, msg.trades.size)
            }
            assertMyOrdersUpdatedMessageReceived()
            assertBalancesMessageReceived()
        }

        makerWsClient.apply {
            assertMyTradesCreatedMessageReceived { msg ->
                assertEquals(2, msg.trades.size)
            }
            assertMyOrdersUpdatedMessageReceived { msg ->
                assertEquals(2, msg.orders.size)
            }
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

        waitForSettlementToFinish(getTradesForOrders(listOf(marketOrderResponse.orderId)).map { it.id.value })

        makerWsClient.close()
        orderBookObserver.close()
        incrementalOrderBookObserver.close()
    }
}
