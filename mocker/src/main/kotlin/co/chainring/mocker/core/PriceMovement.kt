package co.chainring.mocker.core

import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

class DeterministicHarmonicPriceMovement(
    private val initialValue: Double,
    private val maxSpread: Double,
    private val numHarmonics: Int = 5,
    private val meanReversionSpeed: Double = 0.01,
    private val seed: Long = 42L
) {
    private val period: Long = 24 * 3600 // Period in seconds (one day)
    private val harmonics: List<Harmonic>
    private val random = Random(seed)

    init {
        harmonics = List(numHarmonics) { index ->
            Harmonic(
                amplitude = 0.5 + random.nextDouble() * 0.5,
                phase = random.nextDouble() * 2 * PI,
                frequency = 1.0 + random.nextDouble()
            )
        }
    }

    fun nextValue(timestamp: Long): Double {
        val t = timestamp % period
        var price = initialValue

        for (harmonic in harmonics) {
            price += maxSpread * harmonic.amplitude * sin(2 * PI * harmonic.frequency * t / period + harmonic.phase)
        }

        // Add deterministic noise based on timestamp
        val deterministicNoise = maxSpread * 0.05 * sin(2 * PI * t / period + seed)
        price += deterministicNoise

        // Add mean reversion to the initial price
        val deviationFromMean = price - initialValue
        price -= meanReversionSpeed * deviationFromMean

        return price
    }

    private data class Harmonic(
        val amplitude: Double,
        val phase: Double,
        val frequency: Double
    )

    companion object {
        fun generateRandom(initialValue: Double, maxSpread: Double): DeterministicHarmonicPriceMovement {
            return DeterministicHarmonicPriceMovement(initialValue = initialValue, maxSpread = maxSpread)
        }
    }
}
