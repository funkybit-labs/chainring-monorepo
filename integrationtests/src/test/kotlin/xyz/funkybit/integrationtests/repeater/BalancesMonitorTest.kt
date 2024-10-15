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
class BalancesMonitorTest {

    @Test
    fun `test evm submitter gas monitor`() {
        Assumptions.assumeFalse(isTestEnvRun())
        clearLogMessages()
        triggerRepeaterTaskAndWaitForCompletion("balances_monitor", taskArgs = listOf("evm_submitter", "10000000000000"))
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
        triggerRepeaterTaskAndWaitForCompletion("balances_monitor", taskArgs = listOf("evm_submitter", "10000000000000"))
        assertEquals(true, logMessages.isEmpty(), "expected no logs but got $logMessages")

        // but if the gas recovers
        triggerRepeaterTaskAndWaitForCompletion("balances_monitor")

        // then it should warn again
        triggerRepeaterTaskAndWaitForCompletion("balances_monitor", taskArgs = listOf("evm_submitter", "10000000000000"))
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

    @Test
    fun `test evm testnet challenge airdropper gas monitor`() {
        Assumptions.assumeFalse(isTestEnvRun())
        clearLogMessages()
        triggerRepeaterTaskAndWaitForCompletion("balances_monitor", taskArgs = listOf("testnet_challenge", "500000"))
        assert(
            logMessages.any {
                it.message.contains(Regex("Low gas alert for airdropper testnet challenge .* on 1337: only .* available, need a minimum of 5000000000000000000000"))
            },
        )
        assert(
            logMessages.any {
                it.message.contains(Regex("Low USDC:1337 alert for airdropper testnet challenge .* on 1337: only .* available, need a minimum of 5000000000000000"))
            },
        )

        // warning should not reoccur
        clearLogMessages()
        triggerRepeaterTaskAndWaitForCompletion("balances_monitor", taskArgs = listOf("testnet_challenge", "500000"))
        assertEquals(true, logMessages.isEmpty(), "expected no logs but got $logMessages")

        // but if the gas recovers
        triggerRepeaterTaskAndWaitForCompletion("balances_monitor", taskArgs = listOf("testnet_challenge", "100"))

        // then it should warn again
        triggerRepeaterTaskAndWaitForCompletion("balances_monitor", taskArgs = listOf("testnet_challenge", "200000"))
        assert(
            logMessages.any {
                it.message.contains(Regex("Low gas alert for airdropper testnet challenge .* on 1337: only .* available, need a minimum of 2000000000000000000000"))
            },
        )
        assert(
            logMessages.any {
                it.message.contains(Regex("Low USDC:1337 alert for airdropper testnet challenge .* on 1337: only .* available, need a minimum of 2000000000000000"))
            },
        )
    }

    @Test
    fun `test bitcoin fee payer gas monitor`() {
        Assumptions.assumeFalse(isTestEnvRun())
        clearLogMessages()
        triggerRepeaterTaskAndWaitForCompletion("balances_monitor", taskArgs = listOf("bitcoin_fee_payer", "10000000"))
        assert(
            logMessages.any {
                it.message.contains(Regex("Low gas alert for bitcoin fee payer .*: only .* available, need a minimum of 10000000"))
            },
        )

        // warning should not reoccur
        clearLogMessages()
        triggerRepeaterTaskAndWaitForCompletion("balances_monitor", taskArgs = listOf("bitcoin_fee_payer", "10000000"))
        assertEquals(true, logMessages.isEmpty(), "expected no logs but got $logMessages")

        // but if the gas recovers
        triggerRepeaterTaskAndWaitForCompletion("balances_monitor", taskArgs = listOf("bitcoin_fee_payer", "100"))

        // then it should warn again
        triggerRepeaterTaskAndWaitForCompletion("balances_monitor", taskArgs = listOf("bitcoin_fee_payer", "20000000"))
        assert(
            logMessages.any {
                it.message.contains(Regex("Low gas alert for bitcoin fee payer .*: only .* available, need a minimum of 20000000"))
            },
        )
    }
}
