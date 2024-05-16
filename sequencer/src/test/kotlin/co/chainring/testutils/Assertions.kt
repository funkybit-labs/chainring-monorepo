package co.chainring.testutils

import co.chainring.sequencer.core.WalletAddress
import co.chainring.sequencer.core.toBigDecimal
import co.chainring.sequencer.core.toWalletAddress
import co.chainring.sequencer.proto.SequencerResponse
import org.junit.jupiter.api.Assertions.assertEquals
import java.math.BigDecimal

data class ExpectedTrade(
    val buyOrderGuid: Long,
    val sellOrderGuid: Long,
    val price: BigDecimal,
    val amount: BigDecimal,
    val buyerFee: BigDecimal,
    val sellerFee: BigDecimal,
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
            )
        },
        tradesCreatedList.map {
            ExpectedTrade(
                buyOrderGuid = it.buyOrderGuid,
                sellOrderGuid = it.sellOrderGuid,
                price = it.price.toBigDecimal(),
                amount = it.amount.fromFundamentalUnits(market.baseDecimals),
                buyerFee = it.buyerFee.fromFundamentalUnits(market.quoteDecimals),
                sellerFee = it.sellerFee.fromFundamentalUnits(market.quoteDecimals),
            )
        },
    )
}

fun SequencerResponse.assertBalanceChanges(
    market: SequencerClient.Market,
    expectedChanges: List<Triple<WalletAddress, SequencerClient.Asset, BigDecimal>>,
) {
    val changes = balancesChangedList.map {
        val asset = market.getAsset(it.asset)
        Triple(
            it.wallet.toWalletAddress(),
            asset,
            it.delta.fromFundamentalUnits(asset.decimals),
        )
    }

    assertEquals(
        expectedChanges.map { it.copy(third = it.third.setScale(it.second.decimals)) },
        changes,
    )
}
