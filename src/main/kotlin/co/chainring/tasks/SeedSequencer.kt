package co.chainring.tasks

import co.chainring.core.model.Symbol
import co.chainring.core.model.WithdrawalFee
import co.chainring.core.model.db.MarketId
import co.chainring.core.sequencer.SequencerClient
import co.chainring.core.utils.toFundamentalUnits
import co.chainring.tasks.fixtures.Fixtures
import kotlinx.coroutines.runBlocking

val sequencerClient = SequencerClient()

fun seedSequencer(fixtures: Fixtures) {
    runBlocking {
        sequencerClient.setFeeRates(fixtures.feeRates).also { response ->
            if (response.hasError()) {
                throw RuntimeException("Failed to set fee rates in sequencer: ${response.error}")
            }
        }

        sequencerClient.setWithdrawalFees(
            fixtures.symbols.map { WithdrawalFee(Symbol(it.name), it.withdrawalFee.toFundamentalUnits(it.decimals)) }
        )

        fixtures.markets.forEach { market ->
            val baseSymbol = fixtures.symbols.first { it.id == market.baseSymbol }
            val quoteSymbol = fixtures.symbols.first { it.id == market.quoteSymbol }

            sequencerClient.createMarket(
                marketId = "${baseSymbol.name}/${quoteSymbol.name}",
                tickSize = market.tickSize,
                marketPrice = market.marketPrice,
                quoteDecimals = quoteSymbol.decimals,
                baseDecimals = baseSymbol.decimals,
                minFee = market.minFee
            ).also { response ->
                if (response.hasError()) {
                    throw RuntimeException("Failed to create market in sequencer: ${response.error}")
                }
            }
        }

        sequencerClient.setMarketMinFees(
            fixtures.markets.associate { market ->
                val baseSymbol = fixtures.symbols.first { it.id == market.baseSymbol }
                val quoteSymbol = fixtures.symbols.first { it.id == market.quoteSymbol }
                MarketId("${baseSymbol.name}/${quoteSymbol.name}") to market.minFee.toFundamentalUnits(quoteSymbol.decimals)
            }
        )
    }
}

