package co.chainring.integrationtests.exchange

import co.chainring.apps.api.model.BatchOrdersApiRequest
import co.chainring.apps.api.model.CancelOrderApiRequest
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.OrderAmount
import co.chainring.apps.api.model.RequestStatus
import co.chainring.apps.api.model.websocket.MarketTradesCreated
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.OrderStatus
import co.chainring.core.utils.generateOrderNonce
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.testutils.OrderBaseTest
import co.chainring.integrationtests.utils.AssetAmount
import co.chainring.integrationtests.utils.TestApiClient
import co.chainring.integrationtests.utils.assertBalancesMessageReceived
import co.chainring.integrationtests.utils.assertMarketTradesCreatedMessageReceived
import co.chainring.integrationtests.utils.assertMyOrderCreatedMessageReceived
import co.chainring.integrationtests.utils.assertMyOrderUpdatedMessageReceived
import co.chainring.integrationtests.utils.assertMyTradesCreatedMessageReceived
import co.chainring.integrationtests.utils.blocking
import co.chainring.integrationtests.utils.subscribeToMarketTrades
import co.chainring.integrationtests.utils.toCancelOrderRequest
import org.http4k.client.WebsocketClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.math.BigInteger

@ExtendWith(AppUnderTestRunner::class)
class MarketTradesBroadcastingTest : OrderBaseTest() {
    @Test
    fun `market trades are broadcasted to subscribers`() {
        val market = btcUsdcMarket
        val baseSymbol = btc
        val quoteSymbol = usdc

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

        val marketTradesObserver = WebsocketClient.blocking(TestApiClient().authToken).apply {
            subscribeToMarketTrades(market.id)
        }

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
        }

        makerWsClient.apply {
            assertMyTradesCreatedMessageReceived { msg ->
                assertEquals(5, msg.trades.size)
            }
            repeat(5) { assertMyOrderUpdatedMessageReceived() }
            assertBalancesMessageReceived()
        }

        val takerBuyOrders = takerApiClient.listOrders(emptyList(), market.id).orders
        assertEquals(1, takerBuyOrders.count { it.status == OrderStatus.Filled })
        assertEquals(5, makerApiClient.listOrders(listOf(OrderStatus.Filled), market.id).orders.size)

        val buyTrades = getTradesForOrders(takerBuyOrders.map { it.id })
        assertEquals(5, buyTrades.size)

        marketTradesObserver.assertMarketTradesCreatedMessageReceived(market.id) { msg ->
            assertEquals(5, msg.trades.size)
            assertEquals(1, msg.sequenceNumber)
            assertEquals(
                buyTrades.map {
                    MarketTradesCreated.Trade(
                        it.id.value,
                        OrderSide.Buy,
                        price = it.price,
                        amount = it.amount,
                        timestamp = it.timestamp,
                    )
                },
                msg.trades,
            )
        }

        makerApiClient.batchOrders(
            BatchOrdersApiRequest(
                marketId = market.id,
                createOrders = listOf("0.001", "0.002").map {
                    makerWallet.signOrder(
                        CreateOrderApiRequest.Limit(
                            nonce = generateOrderNonce(),
                            marketId = market.id,
                            side = OrderSide.Buy,
                            amount = OrderAmount.Fixed(AssetAmount(baseSymbol, it).inFundamentalUnits),
                            price = BigDecimal("68500.000"),
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ),
                    )
                },
                cancelOrders = listOf(),
            ),
        )

        repeat(2) { makerWsClient.assertMyOrderCreatedMessageReceived() }

        takerApiClient.createMarketOrder(
            market,
            OrderSide.Sell,
            amount = BigDecimal("0.003"),
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

        val takerSellOrders = takerApiClient.listOrders(emptyList(), market.id).orders
        val sellTrades = getTradesForOrders(takerSellOrders.map { it.id }).takeLast(2)

        marketTradesObserver.assertMarketTradesCreatedMessageReceived(market.id) { msg ->
            assertEquals(2, msg.trades.size)
            assertEquals(2, msg.sequenceNumber)
            assertEquals(
                sellTrades.map {
                    MarketTradesCreated.Trade(
                        it.id.value,
                        OrderSide.Sell,
                        price = it.price,
                        amount = it.amount,
                        timestamp = it.timestamp,
                    )
                },
                msg.trades,
            )
        }

        makerWsClient.close()
        takerWsClient.close()
        marketTradesObserver.close()
    }
}
