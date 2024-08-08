package xyz.funkybit.apps.telegrambot.model

import org.apache.commons.lang3.ObjectUtils.min
import xyz.funkybit.apps.api.model.CreateOrderApiRequest
import xyz.funkybit.apps.api.model.OrderAmount
import xyz.funkybit.core.model.EvmSignature
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.MarketEntity
import xyz.funkybit.core.model.db.OrderBookSnapshot
import xyz.funkybit.core.model.db.OrderSide
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.utils.generateOrderNonce
import xyz.funkybit.core.utils.setScale
import xyz.funkybit.core.utils.toFundamentalUnits
import java.math.BigDecimal
import java.math.RoundingMode

data class SwapEstimation(
    val from: SymbolEntity,
    val fromAmount: BigDecimal,
    val to: SymbolEntity,
    val toAmount: BigDecimal,
    val price: BigDecimal,
    val market: MarketEntity,
) {
    fun toOrderRequest(): CreateOrderApiRequest.Market {
        val (amount, orderSide) = if (from == market.baseSymbol) {
            Pair(fromAmount, OrderSide.Sell)
        } else {
            Pair(toAmount, OrderSide.Buy)
        }

        return CreateOrderApiRequest.Market(
            nonce = generateOrderNonce(),
            marketId = market.id.value,
            side = orderSide,
            amount = OrderAmount.Fixed(amount.toFundamentalUnits(market.baseSymbol.decimals)),
            signature = EvmSignature.emptySignature(),
            verifyingChainId = ChainId.empty,
        )
    }
}

class SwapEstimateError(message: String) : RuntimeException(message)

fun estimateSwap(fromSymbol: SymbolEntity, toSymbol: SymbolEntity, amount: BigDecimal): SwapEstimation {
    val market = MarketEntity.findBySymbols(fromSymbol, toSymbol)
        ?: throw RuntimeException("There is no market for ${fromSymbol.name}/${toSymbol.name} swaps")

    return if (market.baseSymbol == fromSymbol) {
        val price = getBaseToQuoteSwapPrice(market, amount)

        SwapEstimation(
            from = fromSymbol,
            fromAmount = amount,
            to = toSymbol,
            toAmount = price * amount.setScale(toSymbol.decimals),
            price = price,
            market = market,
        )
    } else {
        val price = getQuoteToBaseSwapPrice(market, amount)

        SwapEstimation(
            from = fromSymbol,
            fromAmount = amount,
            to = toSymbol,
            toAmount = price * amount.setScale(toSymbol.decimals),
            price = price,
            market = market,
        )
    }
}

private data class Level(
    val size: BigDecimal,
    val price: BigDecimal,
)

private data class Trade(
    val amount: BigDecimal,
    val price: BigDecimal,
)

private fun getBaseToQuoteSwapPrice(market: MarketEntity, baseAmount: BigDecimal): BigDecimal {
    val baseScale = market.baseSymbol.decimals
    val zeroBase = BigDecimal.ZERO.setScale(baseScale)

    val quoteScale = market.quoteSymbol.decimals
    val zeroQuote = BigDecimal.ZERO.setScale(quoteScale)

    val orderBook = OrderBookSnapshot.get(market)

    val levels = orderBook.bids.reversed().map {
        Level(size = it.size.setScale(baseScale), price = it.price.setScale(quoteScale))
    }.toMutableList()

    var amountCovered: BigDecimal = zeroBase
    val trades = mutableListOf<Trade>()

    while (amountCovered < baseAmount && levels.size > 0) {
        val level = levels.removeLast()
        val amountLeftToCover = baseAmount - amountCovered
        val trade = Trade(
            amount = min(amountLeftToCover, level.size),
            price = level.price,
        )
        amountCovered += trade.amount
        trades.add(trade)
    }

    if (amountCovered < baseAmount) {
        throw SwapEstimateError("Not enough liquidity on sell side in ${market.id.value} market")
    }

    // size-weighted average across levels consumed
    return (trades.fold(zeroQuote) { acc, t -> acc + t.price * t.amount } / amountCovered)
        .setScale(quoteScale, RoundingMode.HALF_EVEN)
}

private fun getQuoteToBaseSwapPrice(market: MarketEntity, quoteAmount: BigDecimal): BigDecimal {
    val baseScale = market.baseSymbol.decimals
    val zeroBase = BigDecimal.ZERO.setScale(baseScale)

    val quoteScale = market.quoteSymbol.decimals
    val zeroQuote = BigDecimal.ZERO.setScale(quoteScale)

    val orderBook = OrderBookSnapshot.get(market)

    val levels = orderBook.asks.map {
        Level(size = it.size.setScale(baseScale), price = it.price.setScale(quoteScale))
    }.toMutableList()

    var amountCovered: BigDecimal = zeroQuote
    val trades = mutableListOf<Trade>()

    while (amountCovered < quoteAmount && levels.size > 0) {
        val level = levels.removeLast()
        val amountLeftToCover = quoteAmount - amountCovered
        val trade = Trade(
            amount = min(amountLeftToCover, (level.size * level.price).setScale(quoteScale, RoundingMode.HALF_EVEN)),
            price = (BigDecimal(1).setScale(quoteScale) / level.price).setScale(baseScale, RoundingMode.HALF_EVEN),
        )
        amountCovered += trade.amount
        trades.add(trade)
    }

    if (amountCovered < quoteAmount) {
        throw SwapEstimateError("Not enough liquidity on buy side in ${market.id.value} market")
    }

    // size-weighted average across levels consumed
    return (trades.fold(zeroBase) { acc, t -> acc + t.price * t.amount } / amountCovered)
        .setScale(baseScale, RoundingMode.HALF_EVEN)
}
