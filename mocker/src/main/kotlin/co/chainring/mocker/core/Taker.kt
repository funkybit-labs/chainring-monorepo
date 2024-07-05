package co.chainring.mocker.core

import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.Market
import co.chainring.apps.api.model.Order
import co.chainring.apps.api.model.OrderAmount
import co.chainring.apps.api.model.websocket.Balances
import co.chainring.apps.api.model.websocket.OrderCreated
import co.chainring.apps.api.model.websocket.OrderUpdated
import co.chainring.apps.api.model.websocket.Orders
import co.chainring.apps.api.model.websocket.Prices
import co.chainring.apps.api.model.websocket.Publishable
import co.chainring.apps.api.model.websocket.SubscriptionTopic
import co.chainring.apps.api.model.websocket.TradeCreated
import co.chainring.apps.api.model.websocket.TradeUpdated
import co.chainring.apps.api.model.websocket.Trades
import co.chainring.core.model.Address
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OHLCDuration
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.OrderStatus
import co.chainring.core.model.db.SettlementStatus
import co.chainring.core.utils.generateHexString
import co.chainring.core.utils.TraceRecorder
import co.chainring.core.utils.WSSpans
import co.chainring.core.model.db.ChainId
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

class Taker(
    marketIds: List<MarketId>,
    private val rate: Long,
    nativeAssets: Map<String, BigInteger>,
    assets: Map<String, BigInteger>,
    private val priceCorrectionFunction: PriceFunction,
    keyPair: ECKeyPair = Keys.createEcKeyPair()
) : Actor(marketIds, nativeAssets, assets, keyPair) {
    override val id: String = "tkr_${Address(Keys.toChecksumAddress("0x" + Keys.getAddress(keyPair))).value}"
    override val logger: KLogger = KotlinLogging.logger {}
    private var currentOrder: Order.Market? = null
    private var marketPrices = mutableMapOf<MarketId, BigDecimal>()
    private var listenerInitialized: Boolean = false
    private var actorThread: Thread? = null
    private var stopping = false

    override val websocketSubscriptionTopics: List<SubscriptionTopic> =
        marketIds
            .map { SubscriptionTopic.Prices(it, OHLCDuration.P5M) } +
            listOf(
                SubscriptionTopic.Trades,
                SubscriptionTopic.Orders,
                SubscriptionTopic.Balances
            )

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
        listenerInitialized = true
    }

    override fun handleWebsocketMessage(message: Publishable) {
        when (message) {
            is TradeCreated -> {
                TraceRecorder.full.finishWSRecording(message.trade.orderId.value, WSSpans.tradeCreated)
                logger.info { "$id: received trade created" }
                pendingTrades.add(message.trade)
            }

            is TradeUpdated -> {
                logger.info { "$id: received trade update" }
                val trade = message.trade
                if (trade.settlementStatus == SettlementStatus.Pending) {
                    TraceRecorder.full.finishWSRecording(trade.id.value, WSSpans.tradeCreated)
                    pendingTrades.add(trade)
                } else if (trade.settlementStatus == SettlementStatus.Completed) {
                    TraceRecorder.full.finishWSRecording(message.trade.orderId.value, WSSpans.tradeSettled)
                    pendingTrades.removeIf { it.id == trade.id }
                    settledTrades.add(trade)
                }
            }

            is Trades -> {
                logger.info { "$id: received ${message.trades.size} trade updates" }
                message.trades.forEach { trade ->
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

            is Balances -> {
                message.balances.forEach {
                    balances[it.symbol.value] = it.available
                }
                logger.info { "$id: received balance update ${message.balances}" }
            }

            is Orders, is OrderCreated, is OrderUpdated -> {
                val orders = when (message) {
                    is Orders -> message.orders
                    is OrderCreated -> listOf(message.order)
                    is OrderUpdated -> listOf(message.order)
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
                    marketPrices[message.market] = it.close.toBigDecimal()
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
