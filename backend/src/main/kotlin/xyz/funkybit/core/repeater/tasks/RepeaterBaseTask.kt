package xyz.funkybit.core.repeater.tasks

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.notifyDbListener
import java.util.TimerTask
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

const val REPEATER_APP_TASK_DONE_CHANNEL = "repeater_app_task_done"

abstract class RepeaterBaseTask(
    val invokePeriod: Duration,
    val initialDelay: Duration = Duration.ZERO,
    val maxPlannedExecutionTime: Duration? = null,
) : TimerTask() {
    abstract val name: String

    private val logger = KotlinLogging.logger {}

    private val lock = Semaphore(1, true)

    // keeping `run` final to make sure all exceptions are logged centrally
    final override fun run() {
        val isLockAcquired = lock.tryAcquire()
        if (isLockAcquired) {
            val startTime = Clock.System.now()
            try {
                runWithLock()
                transaction {
                    notifyDbListener(REPEATER_APP_TASK_DONE_CHANNEL, name)
                }
            } catch (t: Throwable) {
                logger.error(t) { "Error running ${this.javaClass.simpleName}" }
            } finally {
                maxPlannedExecutionTime?.let { maxExecutionTime ->
                    val executionTime = Clock.System.now() - startTime
                    if (executionTime > maxExecutionTime) {
                        logger.error { "The execution time of ${this.javaClass.simpleName} ($executionTime) has exceeded the maximum planned time ($maxExecutionTime). Please consider optimizing it." }
                    }
                }
                try {
                    lock.release()
                } catch (t: Throwable) {
                    logger.warn(t) { "Could not release lock, retrying" }
                    try {
                        lock.release()
                    } catch (t: Throwable) {
                        logger.error(t) { "Still could not release lock, giving up" }
                    }
                }
            }
        } else {
            logger.warn { "Could not acquire lock for ${this.javaClass.simpleName}, skipping" }
        }
    }

    open fun runWithLock() {}

    fun schedule(timer: ScheduledThreadPoolExecutor) {
        if (invokePeriod.inWholeMilliseconds > 0) {
            timer.scheduleAtFixedRate(this, initialDelay.inWholeMilliseconds, invokePeriod.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        }
    }

    open fun setNextInvocationArgs(args: List<String>) {}
}
