package co.chainring.mocker.core

import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.Market
import co.chainring.apps.api.model.Order
import co.chainring.apps.api.model.websocket.Balances
import co.chainring.apps.api.model.websocket.OrderCreated
import co.chainring.apps.api.model.websocket.OrderUpdated
import co.chainring.apps.api.model.websocket.Orders
import co.chainring.apps.api.model.websocket.OutgoingWSMessage
import co.chainring.apps.api.model.websocket.Prices
import co.chainring.apps.api.model.websocket.SubscriptionTopic
import co.chainring.apps.api.model.websocket.TradeCreated
import co.chainring.apps.api.model.websocket.TradeUpdated
import co.chainring.apps.api.model.websocket.Trades
import co.chainring.core.client.rest.ApiCallFailure
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OHLCDuration
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.OrderStatus
import co.chainring.core.model.db.SettlementStatus
import co.chainring.core.utils.generateHexString
import co.chainring.core.utils.TraceRecorder
import co.chainring.core.utils.WSSpans
import co.chainring.core.client.ws.blocking
import co.chainring.core.client.ws.receivedDecoded
import co.chainring.core.client.ws.subscribeToBalances
import co.chainring.core.client.ws.subscribeToOrders
import co.chainring.core.client.ws.subscribeToPrices
import co.chainring.core.client.ws.subscribeToTrades
import co.chainring.core.client.ws.unsubscribe
import co.chainring.core.model.db.ChainId
import io.github.oshai.kotlinlogging.KotlinLogging
import org.http4k.client.WebsocketClient
import org.http4k.websocket.WsStatus
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlinx.datetime.Clock

class Taker(
    private val rate: Long,
    private val sizeFactor: Double,
    native: BigInteger?,
    assets: Map<String, BigInteger>,
    private val priceCorrectionFunction: DeterministicHarmonicPriceMovement
) : Actor(native, assets) {
    private var currentOrder: Order.Market? = null
    private var markets = setOf<Market>()
    private var marketPrices = mutableMapOf<MarketId, BigDecimal>()
    private val logger = KotlinLogging.logger {}
    private var listenerThread: Thread? = null
    private var listenerInitialized: Boolean = false
    private var actorThread: Thread? = null
    private var stopping = false

    fun start(marketIds: List<MarketId>) {
        val config = apiClient.getConfiguration()
        markets = config.markets.toSet()
        listenerThread = thread(start = true, name = "tkr-listen-$id", isDaemon = false) {
            logger.info { "Taker $id starting" }
            this.depositAssets()
            val wsClient = WebsocketClient.blocking(apiClient.authToken)
            marketIds.forEach {
                wsClient.subscribeToPrices(it)
            }
            wsClient.subscribeToOrders()
            wsClient.subscribeToTrades()
            wsClient.subscribeToBalances()
            listenerInitialized = true
            logger.info { "Taker $id initialized, entering run-loop" }
            while (!stopping) {
                try {
                    val message = (wsClient.receivedDecoded().firstOrNull() as OutgoingWSMessage.Publish?)
                    when (message?.topic) {
                        SubscriptionTopic.Trades -> {
                            when (val data = message.data) {
                                is TradeCreated -> {
                                    TraceRecorder.full.finishWSRecording(data.trade.orderId.value, WSSpans.tradeCreated)
                                    logger.info { "$id Received trade created" }
                                    pendingTrades.add(data.trade)
                                }

                                is TradeUpdated -> {
                                    logger.info { "$id Received trade update" }
                                    val trade = data.trade
                                    if (trade.settlementStatus == SettlementStatus.Pending) {
                                        TraceRecorder.full.finishWSRecording(trade.id.value, WSSpans.tradeCreated)
                                        pendingTrades.add(trade)
                                    } else if (trade.settlementStatus == SettlementStatus.Completed) {
                                        TraceRecorder.full.finishWSRecording(data.trade.orderId.value, WSSpans.tradeSettled)
                                        pendingTrades.removeIf { it.id == trade.id }
                                        settledTrades.add(trade)
                                    }
                                    data.trade
                                }

                                is Trades -> {
                                    logger.info { "$id Received ${data.trades.size} trade updates" }
                                    data.trades.forEach { trade ->
                                        if (trade.settlementStatus == SettlementStatus.Pending) {
                                            TraceRecorder.full.finishWSRecording(trade.orderId.value, WSSpans.tradeCreated)
                                            pendingTrades.add(trade)
                                        } else if (trade.settlementStatus == SettlementStatus.Completed) {
                                            TraceRecorder.full.finishWSRecording(trade.orderId.value, WSSpans.tradeSettled)
                                            pendingTrades.removeIf { it.id == trade.id }
                                            settledTrades.add(trade)
                                        }
                                    }
                                }
                                else -> {}
                            }
                        }

                        SubscriptionTopic.Balances -> {
                            val balanceData = message.data as Balances
                            balanceData.balances.forEach {
                                balances[it.symbol.value] = it.available
                            }
                            logger.info { "$id Balance update ${balanceData.balances}" }
                        }

                        SubscriptionTopic.Orders -> {
                            val orders = when (val data = message.data) {
                                is Orders -> data.orders
                                is OrderCreated -> listOf(data.order)
                                is OrderUpdated -> listOf(data.order)
                                else -> emptyList()
                            }
                            orders.forEach { order ->
                                logger.info { "$id Order update $order" }
                                when (order.status) {
                                    OrderStatus.Open -> {
                                        TraceRecorder.full.finishWSRecording(order.id.value, WSSpans.orderCreated)
                                    }

                                    OrderStatus.Partial -> {
                                        TraceRecorder.full.finishWSRecording(order.id.value, WSSpans.orderFilled)
                                        currentOrder?.let { cur ->
                                            val filled = cur.amount - order.amount
                                            logger.info { "$id Order ${order.id} filled $filled of ${cur.amount} remaining" }
                                            currentOrder = cur.copy(
                                                amount = order.amount
                                            )
                                        }
                                    }

                                    OrderStatus.Filled -> {
                                        TraceRecorder.full.finishWSRecording(order.id.value, WSSpans.orderFilled)
                                        logger.info { "$id Order ${order.id} fully filled ${order.amount}" }
                                        currentOrder = null
                                    }

                                    OrderStatus.Cancelled -> {
                                        logger.info { "$id Order ${order.id} canceled" }
                                        currentOrder = null
                                    }

                                    OrderStatus.Expired -> {
                                        logger.info { "$id Order ${order.id} expired" }
                                        currentOrder = null
                                    }

                                    else -> {}
                                }
                            }
                        }

                        is SubscriptionTopic.Prices -> {
                            val prices = message.data as Prices
                            if (prices.full) {
                                logger.info { "$id Full price update for ${prices.market}" }
                            } else {
                                logger.info { "$id Incremental price update: $prices" }
                            }
                            prices.ohlc.lastOrNull()?.let {
                                marketPrices[prices.market] = it.close.toBigDecimal()
                            }
                        }

                        else -> {}
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "Error occurred while running taker in market $markets" }
                }
            }
            marketIds.forEach {
                wsClient.unsubscribe(SubscriptionTopic.Prices(it, OHLCDuration.P5M))
            }
            wsClient.unsubscribe(SubscriptionTopic.Trades)
            wsClient.unsubscribe(SubscriptionTopic.Orders)
            wsClient.unsubscribe(SubscriptionTopic.Balances)
            wsClient.close(WsStatus.GOING_AWAY)
        }
        actorThread = thread(start = true, name = "tkr-actor-$id", isDaemon = false) {
            while (!listenerInitialized && !stopping) {
                Thread.sleep(50)
            }
            while (!stopping) {
                Thread.sleep(Random.nextLong(rate / 2, rate * 2))
                marketIds.random().let { marketId ->
                    val baseBalance = balances[marketId.baseSymbol()] ?: BigInteger.ZERO
                    val quoteBalance = balances[marketId.quoteSymbol()] ?: BigInteger.ZERO
                    val market = markets.find { it.id == marketId }!!
                    logger.debug { "$id: baseBalance $baseBalance, quoteBalance: $quoteBalance" }
                    marketPrices[marketId]?.let { price ->
                        val expectedMarketPrice = priceCorrectionFunction.nextValue(Clock.System.now().toEpochMilliseconds())
                        val side = if (expectedMarketPrice > price.toDouble()) {
                            OrderSide.Buy
                        } else {
                            OrderSide.Sell
                        }
                        val amount = when (side) {
                            OrderSide.Buy -> {
                                quoteBalance.let { notional ->
                                    ((notional.toBigDecimal() / Random.nextDouble(0.0, sizeFactor).toBigDecimal()) / price).movePointLeft(
                                        market.quoteDecimals - market.baseDecimals
                                    ).toBigInteger()
                                } ?: BigInteger.ZERO
                            }

                            OrderSide.Sell -> {
                                (baseBalance.toBigDecimal() / Random.nextDouble(0.0, sizeFactor).toBigDecimal()).toBigInteger()
                            }
                        }
                        logger.debug { "$id going to create a market $side order (amount: $amount, market price: $price" }
                        apiClient.tryCreateOrder(
                            CreateOrderApiRequest.Market(
                                nonce = generateHexString(32),
                                marketId = marketId,
                                side = side,
                                amount = amount,
                                signature = EvmSignature.emptySignature(),
                                verifyingChainId = ChainId.empty,
                            ).let {
                                wallet.signOrder(it)
                            },
                        ).fold(
                            { e: ApiCallFailure -> logger.error { "$id failed to create order with: $e" } },
                            { response ->
                                logger.debug { "$id back from creating an order" }

                                TraceRecorder.full.startWSRecording(response.orderId.value, WSSpans.orderCreated)
                                TraceRecorder.full.startWSRecording(response.orderId.value, WSSpans.orderFilled)
                                TraceRecorder.full.startWSRecording(response.orderId.value, WSSpans.tradeCreated)
                                TraceRecorder.full.startWSRecording(response.orderId.value, WSSpans.tradeSettled)
                            }
                        )
                    }
                }
            }
        }
    }

    fun stop() {
        logger.info { "Taker $id stopping" }
        actorThread?.let {
            stopping = true
            it.interrupt()
            it.join(1000)
        }
        listenerThread?.let {
            stopping = true
            it.interrupt()
            it.join(1000)
        }
    }
}
