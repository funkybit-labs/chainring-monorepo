package xyz.funkybit.integrationtests.repeater

import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import xyz.funkybit.core.model.db.BroadcasterJobEntity
import xyz.funkybit.core.model.db.BroadcasterJobId
import xyz.funkybit.core.model.db.BroadcasterJobTable
import xyz.funkybit.core.model.db.BroadcasterNotification
import xyz.funkybit.core.model.db.UserId
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.testutils.triggerRepeaterTaskAndWaitForCompletion
import kotlin.test.assertContains
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

@ExtendWith(AppUnderTestRunner::class)
class BroadcasterJobsCleanupTest {
    @Test
    fun `test broadcaster jobs cleanup`() {
        transaction {
            val now = Clock.System.now()
            val notification = BroadcasterNotification.walletBalances(UserId.generate())

            BroadcasterJobEntity.create(BroadcasterJobId("bcjob_1"), listOf(notification), now - 8.days)
            BroadcasterJobEntity.create(BroadcasterJobId("bcjob_2"), listOf(notification), now - 7.days - 2.minutes)
            BroadcasterJobEntity.create(BroadcasterJobId("bcjob_3"), listOf(notification), now - 7.days + 2.minutes)
            BroadcasterJobEntity.create(BroadcasterJobId("bcjob_4"), listOf(notification), now - 6.days)
        }

        triggerRepeaterTaskAndWaitForCompletion("broadcaster_jobs_cleanup")

        transaction {
            val retainedJobIds = BroadcasterJobTable
                .select(BroadcasterJobTable.guid)
                .map { row -> row[BroadcasterJobTable.guid].value }
                .toSet()

            assertContains(retainedJobIds, BroadcasterJobId("bcjob_3"))
            assertContains(retainedJobIds, BroadcasterJobId("bcjob_4"))

            assertFalse(retainedJobIds.contains(BroadcasterJobId("bcjob_1")))
            assertFalse(retainedJobIds.contains(BroadcasterJobId("bcjob_2")))
        }
    }
}
