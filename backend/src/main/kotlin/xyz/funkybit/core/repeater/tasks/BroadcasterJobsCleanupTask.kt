package xyz.funkybit.core.repeater.tasks

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.model.db.BroadcasterJobEntity
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class BroadcasterJobsCleanupTask(
    private val retentionPeriod: Duration = System.getenv("BROADCASTER_JOB_RETENTION_DAYS")?.toLongOrNull()?.days ?: 7.days,
) : RepeaterBaseTask(
    invokePeriod = 1.hours,
    maxPlannedExecutionTime = 30.seconds,
) {
    override val name: String = "broadcaster_jobs_cleanup"

    private val logger = KotlinLogging.logger {}

    override fun runWithLock() {
        val now = Clock.System.now()
        transaction {
            logger.debug { "Cleaning up broadcaster jobs table" }

            val deletedRecords = BroadcasterJobEntity.deleteOlderThan(now - retentionPeriod)

            if (deletedRecords == 0) {
                logger.debug { "No broadcaster jobs older than ${retentionPeriod.inWholeDays} days found" }
            } else {
                logger.debug { "Deleted $deletedRecords broadcaster jobs order than ${retentionPeriod.inWholeDays} days" }
            }
        }
    }
}
