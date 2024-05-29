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
import co.chainring.apps.api.model.websocket.Prices
import co.chainring.apps.api.model.websocket.Publishable
import co.chainring.apps.api.model.websocket.SubscriptionTopic
import co.chainring.apps.api.model.websocket.TradeCreated
import co.chainring.apps.api.model.websocket.TradeUpdated
import co.chainring.apps.api.model.websocket.Trades
import co.chainring.core.client.rest.ApiCallFailure
import co.chainring.core.model.Address
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OHLCDuration
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.OrderStatus
import co.chainring.core.model.db.SettlementStatus
import co.chainring.core.utils.generateOrderNonce
import co.chainring.core.model.db.ChainId
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.math.max
import kotlin.math.pow
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys

class Maker(
    marketIds: List<MarketId>,
    private val tightness: Int,
    private val skew: Int,
    private val levels: Int,
    nativeAssets: Map<String, BigInteger>,
    assets: Map<String, BigInteger>,
    keyPair: ECKeyPair = Keys.createEcKeyPair()
) : Actor(marketIds, nativeAssets, assets, keyPair) {
    override val id: String = "mm_${Address(Keys.toChecksumAddress("0x" + Keys.getAddress(keyPair))).value}"
    override val logger: KLogger = KotlinLogging.logger {}
    private var currentOrders = mutableMapOf<MarketId,MutableSet<Order.Limit>>()
    private var quotesCreated = false

    override val websocketSubscriptionTopics: List<SubscriptionTopic> =
        marketIds
            .map { SubscriptionTopic.Prices(it, OHLCDuration.P5M) } +
            listOf(
                SubscriptionTopic.Trades,
                SubscriptionTopic.Orders,
                SubscriptionTopic.Balances
            )

    override fun onStopping() {
        quotesCreated = false
        apiClient.cancelOpenOrders()
    }

    override fun handleWebsocketMessage(message: Publishable) {
        when (message) {
            is TradeCreated -> {
                logger.info { "$id: received trade created" }
                pendingTrades.add(message.trade)
            }
            is TradeUpdated -> {
                logger.info { "$id: received trade update" }
                val trade = message.trade
                if (trade.settlementStatus == SettlementStatus.Pending) {
                    pendingTrades.add(trade)
                } else if (trade.settlementStatus == SettlementStatus.Completed) {
                    pendingTrades.removeIf { it.id == trade.id }
                    settledTrades.add(trade)
                }
            }
            is Trades -> {
                logger.info { "$id: received ${message.trades.size} trade updates" }
                message.trades.forEach { trade ->
                    if (trade.settlementStatus == SettlementStatus.Pending) {
                        pendingTrades.add(trade)
                    } else if (trade.settlementStatus == SettlementStatus.Completed) {
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
                            currentOrders[order.marketId]!!.add(
                                order as Order.Limit
                            )
                        }

                        OrderStatus.Partial -> {
                            val oldOrder = currentOrders[order.marketId]!!.find { it.id == order.id }
                            if (oldOrder == null) {
                                logger.info { "$id: received partial fill for unknown order ${order.id}" }
                            } else {
                                val filled = oldOrder.amount - order.amount
                                logger.info { "$id: order ${order.id} filled $filled of ${oldOrder.amount} remaining" }
                                currentOrders[order.marketId]!!.remove(oldOrder)
                                currentOrders[order.marketId]!!.add(
                                    oldOrder.copy(
                                        amount = order.amount
                                    )
                                )
                            }
                        }

                        OrderStatus.Filled -> {
                            logger.info { "$id: order ${order.id} fully filled ${order.amount}" }
                            currentOrders[order.marketId]!!.removeIf { it.id == order.id }
                        }

                        OrderStatus.Cancelled -> {
                            logger.info { "$id: order ${order.id} canceled" }
                            currentOrders[order.marketId]!!.removeIf { it.id == order.id }
                        }

                        OrderStatus.Expired -> {
                            logger.info { "$id: order ${order.id} expired" }
                            currentOrders[order.marketId]!!.removeIf { it.id == order.id }
                        }

                        else -> {}
                    }
                }
            }
            is Prices -> {
                if (message.full) {
                    logger.info { "$id: full price update for ${message.market}" }
                    if (!quotesCreated) {
                        markets.find { it.id == message.market }?.let {
                            createQuotes(message.market, levels, message.ohlc.lastOrNull()?.close?.toBigDecimal() ?: BigDecimal.ONE)
                            quotesCreated = true
                        }
                    }
                } else {
                    if (message.ohlc.isNotEmpty()) {
                        logger.info { "$id: incremental price update $message" }
                        markets.find { it.id == message.market }?.let {
                            adjustQuotes(it, message.ohlc.last().close.toBigDecimal())
                        }
                    }
                }
            }
            else -> {}
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
        val useFraction = 0.5.toBigDecimal()
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
        logger.debug { "$id: adjusting quotes in market ${market.id}, current price: $curPrice" }
        val marketId = market.id
        val currentOffers = currentOrders[marketId]?.filter { it.side == OrderSide.Sell } ?: emptyList()
        val currentBids = currentOrders[marketId]?.filter { it.side == OrderSide.Buy } ?: emptyList()
        val ordersToCancel = currentOffers + currentBids
        val (offerPrices, bidPrices) = offerAndBidPrices(market, levels, curPrice)
        val (offerAmounts, bidAmounts) = offerAndBidAmounts(market, offerPrices, bidPrices)

        val createOrders = offerPrices.mapIndexed { ix, price ->
            wallet.signOrder(
                CreateOrderApiRequest.Limit(
                    nonce = generateOrderNonce(),
                    marketId = marketId,
                    side = OrderSide.Sell,
                    amount = offerAmounts[ix],
                    price = price,
                    signature = EvmSignature.emptySignature(),
                    verifyingChainId = ChainId.empty,
                )
            )
        } + bidPrices.mapIndexed { ix, price ->
            wallet.signOrder(
                CreateOrderApiRequest.Limit(
                    nonce = generateOrderNonce(),
                    marketId = marketId,
                    side = OrderSide.Buy,
                    amount = bidAmounts[ix],
                    price = price,
                    signature = EvmSignature.emptySignature(),
                    verifyingChainId = ChainId.empty,
                )
            )
        }

        val cancelOrders = ordersToCancel.map {
            wallet.signCancelOrder(
                CancelOrderApiRequest(
                    orderId = it.id,
                    marketId = it.marketId,
                    amount = it.amount,
                    side = it.side,
                    nonce = generateOrderNonce(),
                    signature = EvmSignature.emptySignature(),
                    verifyingChainId = ChainId.empty,
                )
            )
        }

        apiClient.tryBatchOrders(
            BatchOrdersApiRequest(
                marketId = marketId,
                createOrders = createOrders,
                updateOrders = emptyList(),
                cancelOrders = cancelOrders
            )
        ).onLeft {
            logger.warn { "$id could not apply batch: ${it.error?.message}" }
        }.onRight {
            logger.info { "$id: applied update batch: created ${createOrders.size}, cancelled ${cancelOrders.size}" }
        }
    }

    private fun createQuotes(marketId: MarketId, levels: Int, curPrice: BigDecimal) {
        logger.debug { "$id: creating quotes in market $marketId, current price: $curPrice" }
        markets.find { it.id == marketId }?.let { market ->
            apiClient.cancelOpenOrders()

            val (offerPrices, bidPrices) = offerAndBidPrices(market, levels, curPrice)
            val (offerAmounts, bidAmounts) = offerAndBidAmounts(market, offerPrices, bidPrices)

            offerPrices.forEachIndexed { ix, price ->
                val amount = offerAmounts[ix]
                apiClient.tryCreateOrder(
                    wallet.signOrder(CreateOrderApiRequest.Limit(
                        nonce = generateOrderNonce(),
                        marketId = marketId,
                        side = OrderSide.Sell,
                        amount = amount,
                        price = price,
                        signature = EvmSignature.emptySignature(),
                        verifyingChainId = ChainId.empty,
                    ))
                ).onLeft { e: ApiCallFailure ->
                    logger.warn { "$id failed to create Sell order in $marketId market with: $e" }
                }
            }

            bidPrices.forEachIndexed { ix, price ->
                val amount = bidAmounts[ix]
                apiClient.tryCreateOrder(
                    wallet.signOrder(CreateOrderApiRequest.Limit(
                        nonce = generateOrderNonce(),
                        marketId = marketId,
                        side = OrderSide.Buy,
                        amount = amount,
                        price = price,
                        signature = EvmSignature.emptySignature(),
                        verifyingChainId = ChainId.empty,
                    ))
                ).onLeft { e: ApiCallFailure ->
                    logger.warn { "$id failed to create Buy order in $marketId market with: $e" }
                }
            }

            currentOrders[marketId] = mutableSetOf()

        } ?: throw RuntimeException("Market $marketId not found")
    }
}
