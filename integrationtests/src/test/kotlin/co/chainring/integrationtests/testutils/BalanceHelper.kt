package co.chainring.integrationtests.testutils

import co.chainring.integrationtests.utils.ApiClient
import org.awaitility.kotlin.await
import java.math.BigInteger
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

data class ExpectedBalance(
    val symbol: String,
    val total: BigInteger,
    val available: BigInteger,
)

object BalanceHelper {

    fun <T> waitForAndVerifyBalanceChange(apiClient: ApiClient, expectedBalances: List<ExpectedBalance>, logic: () -> T): T {
        return logic().also {
            await
                .pollInSameThread()
                .pollDelay(Duration.ofMillis(100))
                .pollInterval(Duration.ofMillis(100))
                .atMost(Duration.ofMillis(20000L))
                .untilAsserted {
                    verifyBalances(apiClient, expectedBalances)
                }
        }
    }

    fun verifyBalances(apiClient: ApiClient, expectedBalances: List<ExpectedBalance>) {
        val actualBalances = apiClient.getBalances().balances
        expectedBalances.forEach { expected ->
            val actual = actualBalances.firstOrNull { it.symbol.value == expected.symbol }
            assertNotNull(actual)
            assertEquals(expected.available, actual.available, "${expected.symbol} available balance does not match")
            assertEquals(expected.total, actual.total, "${expected.symbol} total balance does not match")
        }
    }
}
