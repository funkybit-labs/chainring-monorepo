package co.chainring.mocker.core

import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

class BrownianMotionWithReversionToMean(
    private val initialValue: Double,
    private val boundary: Double,
    private var lastValue: Double = initialValue,
    private val meanReversionSpeed: Double = 0.15,  // Speed at which the process reverts to the mean
    private val volatility: Double = 0.5            // Volatility of the process
) {
    private val random = Random()
    private var lastUpdateTime: Instant = Clock.System.now()

    fun nextValue(instant: Instant): Double {
        val deltaTime = (instant - lastUpdateTime).inWholeSeconds

        if (deltaTime > 0) {
            val meanReversionTerm = meanReversionSpeed * (initialValue - lastValue) * deltaTime
            val diffusionTerm = volatility * random.nextGaussian()
            lastValue += meanReversionTerm + diffusionTerm
            lastValue = clamp(lastValue, initialValue - boundary, initialValue + boundary)
            lastUpdateTime = instant
        }

        return lastValue
    }

    private fun clamp(value: Double, minValue: Double, maxValue: Double): Double {
        return max(minValue, min(value, maxValue))
    }

    companion object {
        fun generateRandom(initialValue: Double, maxSpread: Double): BrownianMotionWithReversionToMean {
            return BrownianMotionWithReversionToMean(initialValue = initialValue, boundary = maxSpread)
        }
    }
}

fun main() {
    val initialValue = 17.4
    val boundary = initialValue * 0.1 // The maximum deviation from the initial value
    val ouProcess = BrownianMotionWithReversionToMean(initialValue, boundary)


    val startTime = Clock.System.now()
    for (i in 0..1000) {
        val currentTime = startTime + i.seconds
        val price = ouProcess.nextValue(currentTime)
        println("Time: $currentTime, Price: $price")
    }
}
