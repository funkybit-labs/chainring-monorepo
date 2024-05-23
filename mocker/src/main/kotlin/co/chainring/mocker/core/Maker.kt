package co.chainring.mocker.core

import co.chainring.apps.api.model.BatchOrdersApiRequest
import co.chainring.apps.api.model.CancelOrderApiRequest
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
import co.chainring.core.utils.generateOrderNonce
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
import kotlin.math.max
import kotlin.math.pow
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys

class Maker(
    private val tightness: Int,
    private val skew: Int,
    private val levels: Int,
    native: BigInteger,
    assets: Map<String, BigInteger>,
    keyPair: ECKeyPair = Keys.createEcKeyPair()
) : Actor(native, assets, keyPair) {
    private var currentOrders = mutableMapOf<MarketId,MutableList<Order.Limit>>()
    private var markets = setOf<Market>()
    private val logger = KotlinLogging.logger {}
    private var listenerThread: Thread? = null
    private var stopping = false

    fun start(marketIds: List<MarketId>) {
        val config = apiClient.getConfiguration()
        markets = config.markets.toSet()
        listenerThread = thread(start = true, name = "mm-$id", isDaemon = false) {
            logger.info { "Market maker $id starting" }
            depositAssets()
            val wsClient = WebsocketClient.blocking(apiClient.authToken)
            marketIds.forEach {
                wsClient.subscribeToPrices(it, OHLCDuration.P1M)
                val prices = (wsClient.receivedDecoded().first() as OutgoingWSMessage.Publish).data as Prices
                wsClient.unsubscribe(SubscriptionTopic.Prices(it, OHLCDuration.P1M))
                createQuotes(it, levels, prices.ohlc.last().close.toBigDecimal())
            }
            marketIds.forEach {
                wsClient.subscribeToPrices(it)
            }
            wsClient.subscribeToOrders()
            wsClient.subscribeToTrades()
            wsClient.subscribeToBalances()
            logger.info { "Market maker $id initialized, entering run-loop" }
            while (!stopping) {
                try {
                    val message = (wsClient.receivedDecoded().firstOrNull() as OutgoingWSMessage.Publish?)
                    when (message?.topic) {
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
                                        val oldOrder = currentOrders[order.marketId]!!.find { it.id == order.id }
                                        if (oldOrder == null) {
                                            logger.info { "Received partial fill for unknown order ${order.id}" }
                                        } else {
                                            val filled = oldOrder.amount - order.amount
                                            logger.info { "Order ${order.id} filled $filled of ${oldOrder.amount} remaining" }
                                            currentOrders[order.marketId]!!.remove(oldOrder)
                                            currentOrders[order.marketId]!!.add(
                                                oldOrder.copy(
                                                    amount = order.amount
                                                )
                                            )
                                        }
                                    }

                                    OrderStatus.Filled -> {
                                        logger.info { "Order ${order.id} fully filled ${order.amount}" }
                                        currentOrders[order.marketId]!!.removeIf { it.id == order.id }
                                    }

                                    OrderStatus.Cancelled -> {
                                        logger.info { "Order ${order.id} canceled" }
                                        currentOrders[order.marketId]!!.removeIf { it.id == order.id }
                                    }

                                    OrderStatus.Expired -> {
                                        logger.info { "Order ${order.id} expired" }
                                        currentOrders[order.marketId]!!.removeIf { it.id == order.id }
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
                                if (prices.ohlc.isNotEmpty()) {
                                    logger.info { "Incremental price update: $prices" }
                                    markets.find { it.id == prices.market }?.let {
                                        adjustQuotes(it, prices.ohlc.last().close.toBigDecimal())
                                    }
                                }
                            }
                        }
                        else -> {}
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "Error occurred while running maker in market $markets" }
                }
            }
            marketIds.forEach {
                wsClient.unsubscribe(SubscriptionTopic.Prices(it, OHLCDuration.P5M))
            }
            wsClient.unsubscribe(SubscriptionTopic.Trades)
            wsClient.unsubscribe(SubscriptionTopic.Orders)
            wsClient.close(WsStatus.GOING_AWAY)
            apiClient.cancelOpenOrders()
        }
    }

    fun stop() {
        logger.info { "Market maker $id stopping" }
        listenerThread?.let {
            stopping = true
            it.interrupt()
            it.join(1000)
        }
    }

    private fun offerAndBidPrices(market: Market, levels: Int, curPrice: BigDecimal): Pair<List<BigDecimal>, List<BigDecimal>> {
        val curPriceRounded = curPrice.divide(market.tickSize).toBigInteger().toBigDecimal().times(market.tickSize)
        val offerPrices = (1..levels).map { n ->
            curPriceRounded + market.tickSize * (n * tightness + skew * n).toBigDecimal()
        }
        val bidPrices = (1..levels).mapNotNull { n ->
            val p = curPriceRounded - market.tickSize * (n * tightness + skew * n).toBigDecimal()
            if (p >= market.tickSize) p else null
        }
        return Pair(offerPrices, bidPrices)
    }

    private fun offerAndBidAmounts(market: Market, offerPrices: List<BigDecimal>, bidPrices: List<BigDecimal>): Pair<List<BigInteger>, List<BigInteger>> {
        val marketId = market.id
        // don't try to use all of available inventory
        val useFraction = 0.40.toBigDecimal()
        val baseInventory = (balances.getOrDefault(marketId.baseSymbol(), BigInteger.ZERO).toBigDecimal() * useFraction).toBigInteger()
        val quoteInventory = (balances.getOrDefault(marketId.quoteSymbol(), BigInteger.ZERO).toBigDecimal() * useFraction).toBigInteger()
        val levels = max(offerPrices.size, bidPrices.size)
        val unscaledAmounts = (1..levels).map { it.toDouble().pow(2.0) }
        val totalAmount = unscaledAmounts.sum()
        return Pair(
            List(offerPrices.size) { ix -> (baseInventory.toBigDecimal() * (unscaledAmounts[ix] / totalAmount).toBigDecimal()).toBigInteger() },
            bidPrices.mapIndexed { ix, price ->
                val notional =
                    (quoteInventory.toBigDecimal() * (unscaledAmounts[ix] / totalAmount).toBigDecimal()).toBigInteger()
                (notional.toBigDecimal() / price).movePointLeft(market.quoteDecimals).movePointRight(market.baseDecimals)
                    .toBigInteger()
            }.toList()
        )
    }

    private fun adjustQuotes(market: Market, curPrice: BigDecimal) {
        val marketId = market.id
        val currentOffers = currentOrders[marketId]?.filter { it.side == OrderSide.Sell } ?: emptyList()
        val currentBids = currentOrders[marketId]?.filter { it.side == OrderSide.Buy } ?: emptyList()
        val(offerPrices, bidPrices) = offerAndBidPrices(market, levels, curPrice)
        val(offerAmounts, bidAmounts) = offerAndBidAmounts(market, offerPrices, bidPrices)

        val result = apiClient.tryBatchOrders(
            BatchOrdersApiRequest(
                marketId = marketId,
                createOrders = offerPrices.mapIndexed { ix, price ->
                        CreateOrderApiRequest.Limit(
                            nonce = generateOrderNonce(),
                            marketId = marketId,
                            side = OrderSide.Sell,
                            amount = offerAmounts[ix],
                            price = price,
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ).let {
                            wallet.signOrder(it)
                        }
                } + bidPrices.mapIndexed { ix, price ->
                        CreateOrderApiRequest.Limit(
                            nonce = generateOrderNonce(),
                            marketId = marketId,
                            side = OrderSide.Buy,
                            amount = bidAmounts[ix],
                            price = price,
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        ).let {
                            wallet.signOrder(it)
                        }
                },
                updateOrders = emptyList(),
                cancelOrders = currentOffers.map {
                    CancelOrderApiRequest(
                        orderId = it.id,
                        marketId = it.marketId,
                        amount = it.amount,
                        side = it.side,
                        nonce = generateOrderNonce(),
                        signature = EvmSignature.emptySignature(),
                        verifyingChainId = ChainId.empty,
                    ).let { request ->
                        wallet.signCancelOrder(request)
                    }
                } + currentBids.map {
                    CancelOrderApiRequest(
                        orderId = it.id,
                        marketId = it.marketId,
                        amount = it.amount,
                        side = it.side,
                        nonce = generateOrderNonce(),
                        signature = EvmSignature.emptySignature(),
                        verifyingChainId = ChainId.empty,
                    ).let { request ->
                        wallet.signCancelOrder(request)
                    }
                }
            )
        )
        result.mapLeft {
            logger.warn { "Could not apply batch: ${it.error?.message}" }
        }
        .map {
            logger.info { "Applied update batch" }
        }
        currentOrders[marketId] = apiClient.listOrders().orders.filter {
            (it.status == OrderStatus.Open || it.status == OrderStatus.Partial) && it.marketId == marketId
        }.map { it as Order.Limit }.toMutableList()
    }

    private fun createQuotes(marketId: MarketId, levels: Int, curPrice: BigDecimal) {
        markets.find { it.id == marketId }?.let { market ->
            apiClient.cancelOpenOrders()
            val(offerPrices, bidPrices) = offerAndBidPrices(market, levels, curPrice)
            val(offerAmounts, bidAmounts) = offerAndBidAmounts(market, offerPrices, bidPrices)
            offerPrices.forEachIndexed { ix, price ->
                val amount = offerAmounts[ix]
                apiClient.tryCreateOrder(
                    CreateOrderApiRequest.Limit(
                        nonce = generateOrderNonce(),
                        marketId = marketId,
                        side = OrderSide.Sell,
                        amount = amount,
                        price = price,
                        signature = EvmSignature.emptySignature(),
                        verifyingChainId = ChainId.empty,
                    ).let {
                        wallet.signOrder(it)
                    },
                ).onLeft { e: ApiCallFailure -> logger.warn { "$id failed to create Sell order in $marketId market with: $e" } }
            }
            bidPrices.forEachIndexed { ix, price ->
                val amount = bidAmounts[ix]
                apiClient.tryCreateOrder(
                    CreateOrderApiRequest.Limit(
                        nonce = generateOrderNonce(),
                        marketId = marketId,
                        side = OrderSide.Buy,
                        amount = amount,
                        price = price,
                        signature = EvmSignature.emptySignature(),
                        verifyingChainId = ChainId.empty,
                    ).let {
                        wallet.signOrder(it)
                    },
                ).onLeft { e: ApiCallFailure -> logger.warn { "$id failed to create Buy order in $marketId market with: $e" } }
            }
            currentOrders[marketId] = apiClient.listOrders().orders.filter {
                it.status == OrderStatus.Open && it.marketId == marketId
            }.map { it as Order.Limit }.toMutableList()
            if (currentOrders[marketId]!!.size != levels * 2) {
                logger.warn { "Not all quotes accepted: ${apiClient.listOrders()}" }
            }
        } ?: throw RuntimeException("Market $marketId not found")
    }
}
