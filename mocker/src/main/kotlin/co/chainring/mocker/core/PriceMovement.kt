package co.chainring.mocker.core

import java.util.Date
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import org.knowm.xchart.SwingWrapper
import org.knowm.xchart.XYChartBuilder

class DeterministicHarmonicPriceMovement(
    private val initialValue: Double,
    private val maxFluctuation: Double,
) {
    private val startTime: Instant = Clock.System.now()
    private val random = Random()
    private val harmonics: List<Harmonic> = listOf(
        Harmonic(frequency = 1.0 / 14400, amplitude = 0.03 * initialValue, phase = random.nextDouble(0.0, 2 * PI)),  // 4 hours
        Harmonic(frequency = 1.0 / 7200, amplitude = 0.02 * initialValue, phase = random.nextDouble(0.0, 2 * PI)),   // 2 hours
        Harmonic(frequency = 1.0 / 3600, amplitude = 0.015 * initialValue, phase = random.nextDouble(0.0, 2 * PI)),  // 1 hour
        Harmonic(frequency = 1.0 / 1800, amplitude = 0.01 * initialValue, phase = random.nextDouble(0.0, 2 * PI)),   // 30 minutes
        Harmonic(frequency = 1.0 / 900, amplitude = 0.005 * initialValue, phase = random.nextDouble(0.0, 2 * PI))    // 15 minutes
    )
    private var nextPhaseChangeTime: Instant = startTime + random.nextLong(600, 1200).seconds

    fun nextValue(timestamp: Instant): Double {
        val duration = (timestamp - startTime).inWholeSeconds.toDouble()
        var fluctuation = 0.0
        harmonics.forEach { harmonic ->
            if (timestamp > nextPhaseChangeTime) {
                // random shock by updating phase of some harmonics
                if (random.nextBoolean()) {
                    harmonic.phase = random.nextDouble(0.0, 2 * PI)
                }
                nextPhaseChangeTime += random.nextLong(600, 1200).seconds
            }
            fluctuation += harmonic.amplitude * sin(2 * PI * harmonic.frequency * duration + harmonic.phase)
        }
        val normalizedFluctuation = maxFluctuation * (fluctuation / harmonics.sumOf { it.amplitude })
        return initialValue + normalizedFluctuation
    }

    private data class Harmonic(
        val frequency: Double,
        val amplitude: Double,
        var phase: Double,
    )

    companion object {
        fun generateRandom(initialValue: Double, maxFluctuation: Double): DeterministicHarmonicPriceMovement {
            return DeterministicHarmonicPriceMovement(initialValue = initialValue, maxFluctuation = maxFluctuation)
        }
    }
}


fun main() {
    val initialPrice = 17.0
    val maxDeviation = initialPrice * 0.01
    val priceFunction = DeterministicHarmonicPriceMovement(initialPrice, maxDeviation)

    val now = Clock.System.now()
    val timestamps = (1..20 * 60).map { (now + it.seconds * 30) }
    val prices = timestamps.map { priceFunction.nextValue(it) }

    val chart = XYChartBuilder().width(1200).height(600).title("Price Movement with Multiple Harmonics").xAxisTitle("Timestamp (seconds)")
        .yAxisTitle("Price").build()

    chart.addSeries("Price", timestamps.toList().map { Date.from(it.toJavaInstant()) }, prices)

    SwingWrapper(chart).displayChart()

}
