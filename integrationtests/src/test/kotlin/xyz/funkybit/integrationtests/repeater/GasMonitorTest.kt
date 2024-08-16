package xyz.funkybit.integrationtests.repeater

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.testutils.isTestEnvRun
import xyz.funkybit.integrationtests.testutils.triggerRepeaterTaskAndWaitForCompletion
import xyz.funkybit.integrationtests.utils.clearLogMessages
import xyz.funkybit.integrationtests.utils.logMessages

@ExtendWith(AppUnderTestRunner::class)
class GasMonitorTest {
    @Test
    fun `test gas monitor`() {
        Assumptions.assumeFalse(isTestEnvRun())
        clearLogMessages()
        triggerRepeaterTaskAndWaitForCompletion("gas_monitor", taskArgs = listOf("10000000000000"))
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
        triggerRepeaterTaskAndWaitForCompletion("gas_monitor", taskArgs = listOf("10000000000000"))
        assertEquals(true, logMessages.isEmpty(), "expected no logs but got $logMessages")

        // but if the gas recovers
        triggerRepeaterTaskAndWaitForCompletion("gas_monitor")

        // then it should warn again
        triggerRepeaterTaskAndWaitForCompletion("gas_monitor", taskArgs = listOf("10000000000000"))
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
