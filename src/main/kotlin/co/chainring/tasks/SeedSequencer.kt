package co.chainring.tasks

import co.chainring.core.sequencer.SequencerClient
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

        fixtures.markets.forEach { market ->
            val baseSymbol = fixtures.symbols.first { it.id == market.baseSymbol }
            val quoteSymbol = fixtures.symbols.first { it.id == market.quoteSymbol }

            sequencerClient.createMarket(
                marketId = "${baseSymbol.name}/${quoteSymbol.name}",
                tickSize = market.tickSize,
                marketPrice = market.marketPrice,
                quoteDecimals = quoteSymbol.decimals,
                baseDecimals = baseSymbol.decimals,
            ).also { response ->
                if (response.hasError()) {
                    throw RuntimeException("Failed to create market in sequencer: ${response.error}")
                }
            }
        }
    }
}

