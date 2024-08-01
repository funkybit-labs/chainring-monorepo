package xyz.funkybit.integrationtests.repeater

import org.awaitility.Awaitility.await
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import xyz.funkybit.core.db.notifyDbListener
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.utils.clearLogMessages
import xyz.funkybit.integrationtests.utils.logMessages
import java.lang.System.getenv
import java.util.concurrent.TimeUnit

@ExtendWith(AppUnderTestRunner::class)
class GasMonitorTest {
    @Test
    fun `test gas monitor`() {
        Assumptions.assumeTrue((getenv("INTEGRATION_RUN") ?: "0") != "1")
        clearLogMessages()
        transaction {
            notifyDbListener("repeater_app_task_ctl", "gas_monitor:10000000000000")
        }
        await().atMost(1, TimeUnit.SECONDS).until {
            logMessages.size == 2
        }
        assert(
            logMessages.any {
                it.message.contains(Regex("Low gas alert for submitter .* on 1337: only .* available, need a minimum of 50000000000000000000000"))
            },
        )
        assert(
            logMessages.any {
                it.message.contains(Regex("Low gas alert for submitter .* on 1338: only .* available, need a minimum of 50000000000000000000000"))
            },
        )

        // warning should not reoccur
        clearLogMessages()
        transaction {
            notifyDbListener("repeater_app_task_ctl", "gas_monitor:10000000000000")
        }
        Thread.sleep(100L)
        assertEquals(true, logMessages.isEmpty(), "expected no logs but got $logMessages")

        // but if the gas recovers
        transaction {
            notifyDbListener("repeater_app_task_ctl", "gas_monitor")
        }
        // give a little time for the monitor task to run
        Thread.sleep(20L)

        // then it should warn again
        transaction {
            notifyDbListener("repeater_app_task_ctl", "gas_monitor:10000000000000")
        }
        await().atMost(1, TimeUnit.SECONDS).until {
            logMessages.size == 2
        }
        assert(
            logMessages.any {
                it.message.contains(Regex("Low gas alert for submitter .* on 1337: only .* available, need a minimum of 50000000000000000000000"))
            },
        )
        assert(
            logMessages.any {
                it.message.contains(Regex("Low gas alert for submitter .* on 1338: only .* available, need a minimum of 50000000000000000000000"))
            },
        )
    }
}
