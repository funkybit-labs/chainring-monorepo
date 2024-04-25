package co.chainring.core

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
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OHLCDuration
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.OrderStatus
import co.chainring.core.model.db.SettlementStatus
import co.chainring.core.utils.generateHexString
import co.chainring.integrationtests.utils.blocking
import co.chainring.integrationtests.utils.receivedDecoded
import co.chainring.integrationtests.utils.subscribeToBalances
import co.chainring.integrationtests.utils.subscribeToOrders
import co.chainring.integrationtests.utils.subscribeToPrices
import co.chainring.integrationtests.utils.subscribeToTrades
import co.chainring.integrationtests.utils.unsubscribe
import io.github.oshai.kotlinlogging.KotlinLogging
import org.http4k.client.WebsocketClient
import org.http4k.websocket.WsStatus
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.concurrent.thread
import kotlin.random.Random

class Taker(
    private val rate: Long,
    private val sizeFactor: Double,
    native: BigInteger?,
    assets: Map<String, BigInteger>
): Actor(native, assets) {
    private var currentOrder: Order.Market? = null
    private var markets = setOf<Market>()
    private var marketPrices = mutableMapOf<MarketId,BigDecimal>()
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
                    val message = (wsClient.receivedDecoded().first() as OutgoingWSMessage.Publish)
                    when (message.topic) {
                        SubscriptionTopic.Trades -> {
                            when (val data = message.data) {
                                is TradeCreated -> {
                                    logger.info { "Received trade created" }
                                    pendingTrades.add(data.trade)

                                }
                                is TradeUpdated -> {
                                    logger.info { "Received trade update" }
                                    val trade = data.trade
                                    if (trade.settlementStatus == SettlementStatus.Pending) {
                                        pendingTrades.add(trade)
                                    } else if (trade.settlementStatus == SettlementStatus.Completed) {
                                        pendingTrades.removeIf { it.id == trade.id }
                                        settledTrades.add(trade)
                                    }
                                    data.trade
                                }
                                is Trades -> {
                                    logger.info { "Received ${data.trades.size} trade updates" }
                                    data.trades.forEach { trade ->
                                        if (trade.settlementStatus == SettlementStatus.Pending) {
                                            pendingTrades.add(trade)
                                        } else if (trade.settlementStatus == SettlementStatus.Completed) {
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
                            logger.info { "Balance update ${balanceData.balances}" }
                        }
                        SubscriptionTopic.Orders -> {
                            val orders = when (val data = message.data) {
                                is Orders -> data.orders
                                is OrderCreated -> listOf(data.order)
                                is OrderUpdated -> listOf(data.order)
                                else -> emptyList()
                            }
                            orders.forEach { order ->
                                logger.info { "Order update $order" }
                                when (order.status) {
                                    OrderStatus.Partial -> {
                                        currentOrder?.let { cur ->
                                            val filled = cur.amount - order.amount
                                            logger.info { "Order ${order.id} filled $filled of ${cur.amount} remaining" }
                                            currentOrder = cur.copy(
                                                amount = order.amount
                                            )
                                        }
                                    }

                                    OrderStatus.Filled -> {
                                        logger.info { "Order ${order.id} fully filled ${order.amount}" }
                                        currentOrder = null
                                    }

                                    OrderStatus.Cancelled -> {
                                        logger.info { "Order ${order.id} canceled" }
                                        currentOrder = null
                                    }

                                    OrderStatus.Expired -> {
                                        logger.info { "Order ${order.id} expired" }
                                        currentOrder = null
                                    }

                                    else -> {}
                                }
                            }
                        }
                        is SubscriptionTopic.Prices -> {
                            val prices = message.data as Prices
                            if (prices.full) {
                                logger.info { "Full price update for ${prices.market}" }
                            } else {
                                logger.info { "Incremental price update: $prices" }
                            }
                            prices.ohlc.lastOrNull()?.let {
                                marketPrices[prices.market] = it.close.toBigDecimal()
                            }
                        }
                        else -> {}
                    }
                } catch (_: InterruptedException) {}
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
                    val market = markets.find { it.id == marketId.value }!!
                    logger.debug { "$id: baseBalance $baseBalance, quoteBalance: $quoteBalance" }
                    marketPrices[marketId]?.let { price ->
                        val side = if (baseBalance.toBigDecimal() < (quoteBalance.toBigDecimal()/price).movePointLeft(market.quoteDecimals - market.baseDecimals)) {
                            OrderSide.Buy
                        } else {
                            OrderSide.Sell
                        }
                        val amount = when (side) {
                            OrderSide.Buy -> {
                                quoteBalance.let { notional ->
                                    ((notional.toBigDecimal() / sizeFactor.toBigDecimal()) / price).movePointLeft(
                                        market.quoteDecimals - market.baseDecimals
                                    ).toBigInteger()
                                } ?: BigInteger.ZERO
                            }
                            OrderSide.Sell -> {
                                (baseBalance.toBigDecimal() / sizeFactor.toBigDecimal()).toBigInteger()
                            }
                        }
                        logger.debug { "$id going to create a $side order" }
                        apiClient.createOrder(
                            CreateOrderApiRequest.Market(
                                nonce = generateHexString(32),
                                marketId = marketId,
                                side = side,
                                amount = amount,
                                signature = EvmSignature.emptySignature(),
                            ).let {
                                wallet.signOrder(it)
                            },
                        )
                        logger.debug { "$id back from creating an order" }
                    }
                }
            }
        }
    }

    fun stop() {
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

    fun join() {
        actorThread?.join()
        listenerThread?.join()
    }
}
