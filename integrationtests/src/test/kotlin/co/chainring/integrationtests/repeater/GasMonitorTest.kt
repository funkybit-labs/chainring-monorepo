package co.chainring.integrationtests.repeater

import co.chainring.core.db.notifyDbListener
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.utils.clearLogMessages
import co.chainring.integrationtests.utils.logMessages
import org.awaitility.Awaitility.await
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
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
        assert(logMessages.isEmpty())

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
