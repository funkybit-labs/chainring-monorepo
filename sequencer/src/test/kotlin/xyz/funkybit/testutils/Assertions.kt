package xyz.funkybit.testutils

import org.junit.jupiter.api.Assertions.assertEquals
import xyz.funkybit.core.model.SequencerAccountId
import xyz.funkybit.core.sequencer.toSequencerAccountId
import xyz.funkybit.sequencer.core.MarketId
import xyz.funkybit.sequencer.core.toBigInteger
import xyz.funkybit.sequencer.proto.SequencerResponse
import java.math.BigDecimal
import java.math.BigInteger

data class ExpectedTrade(
    val buyOrderGuid: Long,
    val sellOrderGuid: Long,
    val price: BigDecimal,
    val amount: BigDecimal,
    val buyerFee: BigDecimal,
    val sellerFee: BigDecimal,
    val marketId: String? = null,
)

fun SequencerResponse.assertTrades(
    market: SequencerClient.Market,
    expectedTrades: List<ExpectedTrade>,
) {
    assertEquals(
        expectedTrades.map {
            it.copy(
                amount = it.amount.setScale(market.baseDecimals),
                buyerFee = it.buyerFee.setScale(market.quoteDecimals),
                sellerFee = it.sellerFee.setScale(market.quoteDecimals),
                marketId = market.id.value,
            )
        },
        tradesCreatedList.map {
            ExpectedTrade(
                buyOrderGuid = it.buyOrderGuid,
                sellOrderGuid = it.sellOrderGuid,
                price = market.tickSize.multiply(it.levelIx.toBigDecimal()),
                amount = it.amount.fromFundamentalUnits(market.baseDecimals),
                buyerFee = it.buyerFee.fromFundamentalUnits(market.quoteDecimals),
                sellerFee = it.sellerFee.fromFundamentalUnits(market.quoteDecimals),
                marketId = it.marketId,
            )
        },
    )
}

fun SequencerResponse.assertTrade(
    market: SequencerClient.Market,
    expectedTrade: ExpectedTrade,
    index: Int,
) {
    assertEquals(
        expectedTrade.copy(
            amount = expectedTrade.amount.setScale(market.baseDecimals),
            buyerFee = expectedTrade.buyerFee.setScale(market.quoteDecimals),
            sellerFee = expectedTrade.sellerFee.setScale(market.quoteDecimals),
            marketId = market.id.value,
        ),
        ExpectedTrade(
            buyOrderGuid = tradesCreatedList[index].buyOrderGuid,
            sellOrderGuid = tradesCreatedList[index].sellOrderGuid,
            price = market.tickSize.multiply(tradesCreatedList[index].levelIx.toBigDecimal()),
            amount = tradesCreatedList[index].amount.fromFundamentalUnits(market.baseDecimals),
            buyerFee = tradesCreatedList[index].buyerFee.fromFundamentalUnits(market.quoteDecimals),
            sellerFee = tradesCreatedList[index].sellerFee.fromFundamentalUnits(market.quoteDecimals),
            marketId = tradesCreatedList[index].marketId,
        ),
    )
}

fun SequencerResponse.assertBalanceChanges(
    market: SequencerClient.Market,
    expectedChanges: List<Triple<SequencerAccountId, SequencerClient.Asset, BigDecimal>>,
) {
    val changes = balancesChangedList.map {
        val asset = market.getAsset(it.asset)
        Triple(
            it.account.toSequencerAccountId(),
            asset,
            it.delta.fromFundamentalUnits(asset.decimals),
        )
    }

    assertEquals(
        expectedChanges.map { it.copy(third = it.third.setScale(it.second.decimals)) },
        changes,
    )
}

data class ExpectedLimitsUpdate(
    val userId: SequencerAccountId,
    val marketId: MarketId,
    val base: BigInteger,
    val quote: BigInteger,
)

fun SequencerResponse.assertLimits(
    expected: List<ExpectedLimitsUpdate>,
) {
    val actual = limitsUpdatedList.map {
        ExpectedLimitsUpdate(
            it.account.toSequencerAccountId(),
            MarketId(it.marketId),
            it.base.toBigInteger(),
            it.quote.toBigInteger(),
        )
    }

    assertEquals(expected.sortedWith(compareBy({ it.userId.value }, { it.marketId.value })), actual)
}
