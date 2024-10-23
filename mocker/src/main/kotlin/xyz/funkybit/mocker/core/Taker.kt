package xyz.funkybit.mocker.core

import xyz.funkybit.apps.api.model.CreateOrderApiRequest
import xyz.funkybit.apps.api.model.Market
import xyz.funkybit.apps.api.model.Order
import xyz.funkybit.apps.api.model.OrderAmount
import xyz.funkybit.apps.api.model.websocket.Balances
import xyz.funkybit.apps.api.model.websocket.MyOrders
import xyz.funkybit.apps.api.model.websocket.Prices
import xyz.funkybit.apps.api.model.websocket.Publishable
import xyz.funkybit.apps.api.model.websocket.SubscriptionTopic
import xyz.funkybit.apps.api.model.websocket.MyTradesCreated
import xyz.funkybit.apps.api.model.websocket.MyTradesUpdated
import xyz.funkybit.apps.api.model.websocket.MyTrades
import xyz.funkybit.core.model.EvmSignature
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.OHLCDuration
import xyz.funkybit.core.model.db.OrderSide
import xyz.funkybit.core.model.db.OrderStatus
import xyz.funkybit.core.model.db.SettlementStatus
import xyz.funkybit.core.utils.generateHexString
import xyz.funkybit.core.utils.TraceRecorder
import xyz.funkybit.core.utils.WSSpans
import xyz.funkybit.core.model.db.ChainId
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlinx.datetime.Clock
import okhttp3.WebSocket
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import xyz.funkybit.apps.api.model.websocket.MyOrdersCreated
import xyz.funkybit.apps.api.model.websocket.MyOrdersUpdated
import xyz.funkybit.integrationtests.utils.WalletKeyPair

class Taker(
    marketIds: List<MarketId>,
    private val rate: Long,
    nativeAssets: Map<String, BigInteger>,
    assets: Map<String, BigInteger>,
    private val priceCorrectionFunction: PriceFunction,
    keyPair: WalletKeyPair = WalletKeyPair.EVM.generate(),
) : Actor(marketIds, nativeAssets, assets, keyPair) {
    override val id: String = "tkr_${keyPair.address().canonicalize()}"
    override val logger: KLogger = KotlinLogging.logger {}
    private var currentOrder: Order.Market? = null
    private var marketPrices = mutableMapOf<MarketId, BigDecimal>()
    private var listenerInitialized: Boolean = false
    private var actorThread: Thread? = null
    private var stopping = false

    override val websocketSubscriptionTopics: List<SubscriptionTopic> =
        marketIds.map { SubscriptionTopic.Prices(it, OHLCDuration.P5M) } +
                listOf(
                    SubscriptionTopic.MyTrades,
                    SubscriptionTopic.MyOrders,
                    SubscriptionTopic.Balances
                ) +
                if (subscribeToAllTopics) {
                    marketIds.map { SubscriptionTopic.OrderBook(it) } + listOf(SubscriptionTopic.Limits)
                } else emptyList()


    override fun onStarted() {
        actorThread = thread(start = true, name = "tkr-actor-$id", isDaemon = false) {
            while (!listenerInitialized && !stopping) {
                Thread.sleep(50)
            }
            while (!stopping) {
                Thread.sleep(Random.nextLong(rate / 2, rate * 2))
                try {
                    submitOrder(markets.random())
                } catch (e: Exception) {
                    logger.warn(e) { "$id: error while submitting order" }
                    Thread.sleep(100)
                }
            }
        }
    }

    override fun onStopping() {
        actorThread?.let {
            stopping = true
            it.interrupt()
            it.join(1000)
        }
    }

    override fun onWebsocketConnected(webSocket: WebSocket) {
        super.onWebsocketConnected(webSocket)
        listenerInitialized = true
    }

    override fun handleWebsocketMessage(message: Publishable) {
        when (message) {
            is MyTradesCreated -> {
                logger.info { "$id: received trades created" }
                message.trades.forEach { trade ->
                    TraceRecorder.full.finishWSRecording(trade.orderId.value, WSSpans.tradeCreated)
                }
                pendingTrades.addAll(message.trades)
            }

            is MyTradesUpdated -> {
                logger.info { "$id: received trades update" }
                message.trades.forEach { trade ->
                    if (trade.settlementStatus == SettlementStatus.Pending) {
                        TraceRecorder.full.finishWSRecording(trade.id.value, WSSpans.tradeCreated)
                        pendingTrades.add(trade)
                    } else if (trade.settlementStatus == SettlementStatus.Completed) {
                        TraceRecorder.full.finishWSRecording(trade.orderId.value, WSSpans.tradeSettled)
                        pendingTrades.removeIf { it.id == trade.id }
                    }
                }
            }

            is MyTrades -> {
                logger.info { "$id: received ${message.trades.size} trade updates" }
                message.trades.forEach { trade ->
                    if (trade.settlementStatus == SettlementStatus.Pending) {
                        TraceRecorder.full.finishWSRecording(trade.orderId.value, WSSpans.tradeCreated)
                        pendingTrades.add(trade)
                    } else if (trade.settlementStatus == SettlementStatus.Completed) {
                        TraceRecorder.full.finishWSRecording(trade.orderId.value, WSSpans.tradeSettled)
                        pendingTrades.removeIf { it.id == trade.id }
                    }
                }
            }

            is Balances -> {
                message.balances.forEach {
                    balances[it.symbol.value] = it.available
                }
                logger.info { "$id: received balance update ${message.balances}" }
            }

            is MyOrders, is MyOrdersCreated, is MyOrdersUpdated -> {
                val orders = when (message) {
                    is MyOrders -> message.orders
                    is MyOrdersCreated -> message.orders
                    is MyOrdersUpdated -> message.orders
                    else -> emptyList()
                }
                orders.forEach { order ->
                    logger.info { "$id: received order update $order" }
                    when (order.status) {
                        OrderStatus.Open -> {
                            TraceRecorder.full.finishWSRecording(order.id.value, WSSpans.orderCreated)
                        }

                        OrderStatus.Partial -> {
                            TraceRecorder.full.finishWSRecording(order.id.value, WSSpans.orderFilled)
                            currentOrder?.let { cur ->
                                val filled = cur.amount - order.amount
                                logger.info { "$id: order ${order.id} filled $filled of ${cur.amount} remaining" }
                                currentOrder = cur.copy(
                                    amount = order.amount
                                )
                            }
                        }

                        OrderStatus.Filled -> {
                            TraceRecorder.full.finishWSRecording(order.id.value, WSSpans.orderFilled)
                            logger.info { "$id: order ${order.id} fully filled ${order.amount}" }
                            currentOrder = null
                        }

                        OrderStatus.Cancelled -> {
                            logger.info { "$id: order ${order.id} canceled" }
                            currentOrder = null
                        }

                        OrderStatus.Expired -> {
                            logger.info { "$id: order ${order.id} expired" }
                            currentOrder = null
                        }

                        else -> {}
                    }
                }
            }

            is Prices -> {
                if (message.full) {
                    logger.info { "$id: full price update for ${message.market}" }
                } else {
                    logger.info { "$id: incremental price update: $message" }
                }
                message.ohlc.lastOrNull()?.let {
                    marketPrices[message.market] = it.close
                }
            }

            else -> {}
        }
    }

    private fun submitOrder(market: Market) {
        logger.info { "$id: submitting order" }

        val baseBalance = balances[market.baseSymbol.value] ?: BigInteger.ZERO
        val quoteBalance = balances[market.quoteSymbol.value] ?: BigInteger.ZERO

        logger.debug { "$id: baseBalance $baseBalance, quoteBalance: $quoteBalance" }

        marketPrices[market.id]?.let { price ->
            val desiredMarketPrice = priceCorrectionFunction.nextValue(Clock.System.now())
            val side = if (desiredMarketPrice > price.toDouble()) {
                OrderSide.Buy
            } else {
                OrderSide.Sell
            }
            val amount = when (side) {
                OrderSide.Buy -> {
                    quoteBalance.let { notional ->
                        ((notional.toBigDecimal() * Random.nextDouble(0.01, 0.5).toBigDecimal()) / price).movePointLeft(
                            market.quoteDecimals - market.baseDecimals
                        ).toBigInteger()
                    } ?: BigInteger.ZERO
                }

                OrderSide.Sell -> {
                    (baseBalance.toBigDecimal() * Random.nextDouble(0.01, 0.5).toBigDecimal()).toBigInteger()
                }
            }

            logger.debug { "$id: going to create a market $side order in ${market.id} market (amount: $amount, market price: $price, desired market price: $desiredMarketPrice" }

            apiClient.createOrder(
                wallet.signOrder(CreateOrderApiRequest.Market(
                    nonce = generateHexString(32),
                    marketId = market.id,
                    side = side,
                    amount = OrderAmount.Fixed(amount),
                    signature = EvmSignature.emptySignature(),
                    verifyingChainId = ChainId.empty,
                ))
            ).also { response ->
                logger.debug { "$id: created a $side order in ${market.id} market" }

                TraceRecorder.full.startWSRecording(response.orderId.value, WSSpans.orderCreated)
                TraceRecorder.full.startWSRecording(response.orderId.value, WSSpans.orderFilled)
                TraceRecorder.full.startWSRecording(response.orderId.value, WSSpans.tradeCreated)
                TraceRecorder.full.startWSRecording(response.orderId.value, WSSpans.tradeSettled)
            }
        }
    }
}
