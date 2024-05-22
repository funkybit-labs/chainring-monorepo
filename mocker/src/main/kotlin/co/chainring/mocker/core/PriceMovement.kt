package co.chainring.mocker.core

import java.util.Date
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Clock
import kotlinx.datetime.toJavaInstant
import org.knowm.xchart.SwingWrapper
import org.knowm.xchart.XYChartBuilder

class DeterministicHarmonicPriceMovement(
    private val initialValue: Double,
    private val maxSpread: Double,
    private val numHarmonics: Int = 3,
    private val meanReversionSpeed: Double = 0.5,
) {
    private val period: Long = 4 * 3600 * 1000
    private val harmonics: List<Harmonic>
    private val random = Random()

    init {
        harmonics = List(numHarmonics) { index ->
            Harmonic(
                amplitude = 0.5 + random.nextDouble() * 0.5,
                phase = random.nextDouble() * 2 * PI,
                frequency = 0.5 + random.nextDouble()
            )
        }
    }

    fun nextValue(timestamp: Long): Double {
        val t = timestamp % period
        var price = initialValue

        for (harmonic in harmonics) {
            val d = maxSpread * harmonic.amplitude * sin(2 * PI * harmonic.frequency * t / period + harmonic.phase)
            println(d)
            price += d
        }

        // Add deterministic noise based on timestamp
        val deterministicNoise = maxSpread * 0.05 * sin(2 * PI * t / period)
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


fun main() {
    val initialPrice = 17.0
    val maxSpread = initialPrice * 0.1
    val priceFunction = DeterministicHarmonicPriceMovement(initialPrice, maxSpread)

    val now = Clock.System.now()
    val timestamps = (1..6 * 60 * 60 ).map { (now + it.seconds) }
    val prices = timestamps.map { priceFunction.nextValue(it.toEpochMilliseconds()) }

    val chart = XYChartBuilder().width(800).height(600).title("Price Movement with Multiple Harmonics").xAxisTitle("Timestamp (seconds)")
        .yAxisTitle("Price").build()

    chart.addSeries("Price", timestamps.toList().map { Date.from(it.toJavaInstant()) }, prices)

    SwingWrapper(chart).displayChart()

}
