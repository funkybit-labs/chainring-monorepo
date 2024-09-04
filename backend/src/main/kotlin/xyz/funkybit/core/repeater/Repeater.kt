package xyz.funkybit.core.repeater

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.Database
import xyz.funkybit.core.repeater.tasks.ArchTokenStateSetupTask
import xyz.funkybit.core.repeater.tasks.GasMonitorTask
import xyz.funkybit.core.repeater.tasks.ReferralPointsTask
import xyz.funkybit.core.utils.PgListener
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

const val REPEATER_APP_TASK_CTL_CHANNEL = "repeater_app_task_ctl"

class Repeater(db: Database, private val automaticTaskScheduling: Boolean = true) {
    private val logger = KotlinLogging.logger {}

    private val tasks = listOf(
        GasMonitorTask(),
        ReferralPointsTask(),
        ArchTokenStateSetupTask(),
    ).associateBy { it.name }

    private val timer = ScheduledThreadPoolExecutor(8)

    private val pgListener = PgListener(db, "repeater_app_task-listener", REPEATER_APP_TASK_CTL_CHANNEL, {}) { notification ->
        var repeaterTaskName = notification.parameter
        val args = mutableListOf<String>()
        if (repeaterTaskName.contains(':')) {
            val parts = repeaterTaskName.split(':')
            repeaterTaskName = parts[0]
            args.addAll(parts.slice(1..parts.lastIndex))
        }

        tasks[repeaterTaskName]?.let {
            logger.debug { "Scheduling one time $repeaterTaskName repeater task" }
            if (args.isNotEmpty()) {
                it.setNextInvocationArgs(args)
            }
            timer.schedule(it, 0, TimeUnit.NANOSECONDS)
        }
    }

    fun start() {
        logger.info { "Starting" }
        if (automaticTaskScheduling) {
            tasks.values.forEach {
                it.schedule(timer)
            }
        }
        pgListener.start()
        logger.info { "Started" }
    }

    fun stop() {
        logger.info { "Stopping" }
        pgListener.stop()
        timer.shutdown()
        while (!timer.awaitTermination(5, TimeUnit.SECONDS)) {
            logger.info { "Awaiting termination" }
        }
        logger.info { "Stopped" }
    }
}
