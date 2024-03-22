package co.chainring.core.utils

import io.github.oshai.kotlinlogging.KLogger
import java.time.Duration
import java.util.TimerTask

open class Timer(val logger: KLogger) {
    private val timer = java.util.Timer()

    fun scheduleOnce(delay: Duration, f: () -> Unit) {
        timer.schedule(
            timerTask(rethrowError = false, f),
            delay.toMillis(),
        )
    }

    fun scheduleAtFixedRate(period: Duration, stopOnError: Boolean, f: () -> Unit) {
        scheduleAtFixedRate(Duration.ZERO, period, stopOnError, f)
    }

    fun scheduleAtFixedRate(initialDelay: Duration, period: Duration, stopOnError: Boolean, f: () -> Unit) {
        timer.scheduleAtFixedRate(
            timerTask(rethrowError = !stopOnError, f),
            initialDelay.toMillis(),
            period.toMillis(),
        )
    }

    fun cancel() {
        timer.cancel()
    }

    private fun timerTask(rethrowError: Boolean = false, f: () -> Unit): TimerTask =
        object : TimerTask() {
            override fun run() {
                try {
                    f()
                } catch (e: Exception) {
                    logger.error(e) { "Error while executing timer task" }
                    if (rethrowError) throw e
                }
            }
        }
}
