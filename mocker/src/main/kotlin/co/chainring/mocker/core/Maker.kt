package co.chainring.mocker.core

import co.chainring.apps.api.model.BatchOrdersApiRequest
import co.chainring.apps.api.model.CancelOrderApiRequest
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
import co.chainring.integrationtests.utils.ApiCallFailure
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
import java.math.RoundingMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random
import org.knowm.xchart.SwingWrapper
import org.knowm.xchart.XYChartBuilder
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys

sealed class LiquidityPlacement {
    data class Absolute(val amount: BigInteger) : LiquidityPlacement()
    data class Relative(val fraction: BigDecimal) : LiquidityPlacement()

    companion object {
        val default = Relative("0.5".toBigDecimal())
    }
}

class Maker(
    marketIds: List<MarketId>,
    private val levels: Int,
    private val levelsSpread: Int,
    private val marketPriceOverride: BigDecimal? = null,
    private val liquidityPlacement: LiquidityPlacement,
    nativeAssets: Map<String, BigInteger>,
    assets: Map<String, BigInteger>,
    keyPair: ECKeyPair = Keys.createEcKeyPair(),
) : Actor(marketIds, nativeAssets, assets, keyPair) {
    override val id: String = "mm_${Address(Keys.toChecksumAddress("0x" + Keys.getAddress(keyPair))).value}"
    override val logger: KLogger = KotlinLogging.logger {}
    private var currentOrders = mutableMapOf<MarketId, MutableSet<Order.Limit>>()
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
                            createQuotes(message.market, levels, levelsSpread, message.ohlc.lastOrNull()?.close?.toBigDecimal() ?: BigDecimal.ONE)
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

    private fun offerAndBidAmounts(market: Market, offerPrices: List<BigDecimal>, bidPrices: List<BigDecimal>, curPrice: BigDecimal): Pair<List<BigInteger>, List<BigInteger>> {
        val marketId = market.id

        val (baseInventory, quoteInventory) = when (liquidityPlacement) {
            is LiquidityPlacement.Absolute -> {
                val baseInventory = minOf(balances.getOrDefault(marketId.baseSymbol(), BigInteger.ZERO), liquidityPlacement.amount)
                val quoteInventory = minOf(balances.getOrDefault(marketId.quoteSymbol(), BigInteger.ZERO), (liquidityPlacement.amount.toBigDecimal() * curPrice).toBigInteger())
                baseInventory to quoteInventory
            }
            is LiquidityPlacement.Relative -> {
                val baseInventory = (balances.getOrDefault(marketId.baseSymbol(), BigInteger.ZERO).toBigDecimal() * liquidityPlacement.fraction).toBigInteger()
                val quoteInventory = (balances.getOrDefault(marketId.quoteSymbol(), BigInteger.ZERO).toBigDecimal() * liquidityPlacement.fraction).toBigInteger()
                baseInventory to quoteInventory
            }
        }

        val marketToPeakStdDevFactor = 6.0
        val peakToOuterStdDevFactor = 2.0

        return Pair(
            generateAsymmetricGaussianAmounts(
                offerPrices,
                baseInventory,
                leftStdDevFactor = marketToPeakStdDevFactor,
                rightStdDevFactor = peakToOuterStdDevFactor
            ),
            generateAsymmetricGaussianAmounts(
                bidPrices,
                quoteInventory,
                leftStdDevFactor = peakToOuterStdDevFactor,
                rightStdDevFactor = marketToPeakStdDevFactor
            ).mapIndexed { index, amount ->
                // convert to base
                (amount.toBigDecimal() / bidPrices[index]).movePointLeft(market.quoteDecimals).movePointRight(market.baseDecimals).toBigInteger()
            },
        )
    }

    private fun adjustQuotes(market: Market, curPrice: BigDecimal) {
        logger.debug { "$id: adjusting quotes in market ${market.id}, current price: $curPrice, price override: $marketPriceOverride" }
        val marketId = market.id
        val ordersToCancel = currentOrders[marketId] ?: emptyList()
        val (offerPrices, bidPrices) = offerAndBidPrices(market.tickSize, levels, levelsSpread, curPrice = marketPriceOverride ?: curPrice)
        val (offerAmounts, bidAmounts) = offerAndBidAmounts(market, offerPrices, bidPrices, curPrice = marketPriceOverride ?: curPrice)

        val createOrders = offerPrices.mapIndexed { ix, price ->
            wallet.signOrder(
                CreateOrderApiRequest.Limit(
                    nonce = generateOrderNonce(),
                    marketId = marketId,
                    side = OrderSide.Sell,
                    amount = OrderAmount.Fixed(offerAmounts[ix]),
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
                    amount = OrderAmount.Fixed(bidAmounts[ix]),
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

    private fun createQuotes(marketId: MarketId, levels: Int, levelsSpread: Int, curPrice: BigDecimal) {
        logger.debug { "$id: creating quotes in market $marketId, current price: $curPrice, price override: $marketPriceOverride" }
        markets.find { it.id == marketId }?.let { market ->
            apiClient.cancelOpenOrders()
            currentOrders[marketId] = mutableSetOf()

            val (offerPrices, bidPrices) = offerAndBidPrices(market.tickSize, levels, levelsSpread, curPrice = marketPriceOverride ?: curPrice)
            val (offerAmounts, bidAmounts) = offerAndBidAmounts(market, offerPrices, bidPrices, curPrice = marketPriceOverride ?: curPrice)

            offerPrices.forEachIndexed { ix, price ->
                val amount = offerAmounts[ix]
                apiClient.tryCreateOrder(
                    wallet.signOrder(
                        CreateOrderApiRequest.Limit(
                            nonce = generateOrderNonce(),
                            marketId = marketId,
                            side = OrderSide.Sell,
                            amount = OrderAmount.Fixed(amount),
                            price = price,
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        )
                    )
                ).onLeft { e: ApiCallFailure ->
                    logger.warn { "$id failed to create Sell order in $marketId market with: $e" }
                }
            }

            bidPrices.forEachIndexed { ix, price ->
                val amount = bidAmounts[ix]
                apiClient.tryCreateOrder(
                    wallet.signOrder(
                        CreateOrderApiRequest.Limit(
                            nonce = generateOrderNonce(),
                            marketId = marketId,
                            side = OrderSide.Buy,
                            amount = OrderAmount.Fixed(amount),
                            price = price,
                            signature = EvmSignature.emptySignature(),
                            verifyingChainId = ChainId.empty,
                        )
                    )
                ).onLeft { e: ApiCallFailure ->
                    logger.warn { "$id failed to create Buy order in $marketId market with: $e" }
                }
            }
        } ?: throw RuntimeException("Market $marketId not found")
    }

    companion object {
        fun offerAndBidPrices(tickSize: BigDecimal, levels: Int, levelsSpread: Int, curPrice: BigDecimal): Pair<List<BigDecimal>, List<BigDecimal>> {
            val curPriceRounded = curPrice.roundToTickSize(tickSize)
            val adjustedLevels = min(levelsSpread / 2, levels)
            val halfLevelsSpread = tickSize.multiply((levelsSpread / 2.0).toBigDecimal())

            val offerPrices = generateUniquePrices(adjustedLevels, curPriceRounded + tickSize, halfLevelsSpread, tickSize, true)
            val bidPrices = generateUniquePrices(adjustedLevels, curPriceRounded - tickSize, halfLevelsSpread, tickSize, false)

            return Pair(offerPrices, bidPrices)
        }

        private fun generateUniquePrices(levels: Int, curPriceRounded: BigDecimal, halfSpreadRange: BigDecimal, tickSize: BigDecimal, isOffer: Boolean): List<BigDecimal> {
            val prices = mutableSetOf<BigDecimal>()

            while (prices.size < levels) {
                val newPrice = if (isOffer) {
                    curPriceRounded + (nextHalfGaussianZeroToOne() * halfSpreadRange).roundToTickSize(tickSize)
                } else {
                    curPriceRounded - (nextHalfGaussianZeroToOne() * halfSpreadRange).roundToTickSize(tickSize)
                }
                prices.add(newPrice)
            }

            return prices.sorted()
        }

        fun generateAsymmetricGaussianAmounts(prices: List<BigDecimal>, totalAmount: BigInteger, leftStdDevFactor: Double, rightStdDevFactor: Double): List<BigInteger> {
            val averagePrice = prices.map { it.toDouble() }.average()
            val range = (prices.maxOrNull()!! - prices.minOrNull()!!).toDouble()
            val leftStdDev = range / leftStdDevFactor
            val rightStdDev = range / rightStdDevFactor

            val amounts = prices.map { price ->
                val x = price.toDouble()
                val stdDev = if (x < averagePrice) leftStdDev else rightStdDev
                val exponent = -(x - averagePrice).pow(2) / (2 * stdDev.pow(2))
                exp(exponent)
            }.map {it + Random.nextDouble(-it, it) * 0.2}
            val unscaledSum = amounts.sum()

            return amounts
                .map { it / unscaledSum * totalAmount.toDouble() }
                .map { it.toBigDecimal().toBigInteger() }
        }

        private fun nextHalfGaussianZeroToOne(): BigDecimal {
            val u = Random.nextDouble()
            val v = Random.nextDouble()
            val gaussian = sqrt(-2.0 * ln(u)) * cos(2.0 * PI * v)
            // Transform to range [-1, 1]
            val transformed = gaussian / 6
            // Clamp to [0, 1]
            return transformed.coerceIn(0.0, 1.0).toBigDecimal()
        }

        private fun BigDecimal.roundToTickSize(tickSize: BigDecimal): BigDecimal {
            return this.divide(tickSize, RoundingMode.HALF_UP).toBigInteger().toBigDecimal().times(tickSize)
        }
    }
}

fun main() {
    listOf(
        Triple(BigDecimal("0.001"), BigDecimal(1.0005), "BTC:1337/BTC:1338"),
        Triple(BigDecimal("0.05"), BigDecimal(17.5), "BTC:1337/ETH:1337"),
        Triple(BigDecimal("1"), BigDecimal(68390.500), "BTC:1337/USDC:1337"),
    ).forEach { (tickSize, initialPrice, market) ->
        val peakToOuterStdDevFactor = 2.0
        val marketToPeakStdDevFactor = 7.0

        val (offerPrices, bidPrices) = Maker.offerAndBidPrices(tickSize = tickSize, levels = 20, levelsSpread = 100, curPrice = initialPrice)
        val offerAmounts =
            Maker.generateAsymmetricGaussianAmounts(offerPrices, BigInteger("10000"), marketToPeakStdDevFactor, peakToOuterStdDevFactor)
        val bidAmounts = Maker.generateAsymmetricGaussianAmounts(bidPrices, BigInteger("10000"), peakToOuterStdDevFactor, marketToPeakStdDevFactor)

        val chart = XYChartBuilder().width(600).height(300)
            .title(market)
            .xAxisTitle("Price")
            .yAxisTitle("Amount").build()

        chart.addSeries("offers", offerPrices, offerAmounts)
        chart.addSeries("bids", bidPrices, bidAmounts)

        SwingWrapper(chart).displayChart()
    }
}
