package co.chainring.integrationtests.testutils

import co.chainring.core.model.db.BalanceTable
import org.awaitility.kotlin.await
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigInteger
import java.time.Duration
import kotlin.test.assertEquals

data class ExpectedBalance(
    val symbol: String,
    val total: BigInteger,
    val available: BigInteger,
)

object BalanceHelper {

    fun <T> waitForAndVerifyBalanceChange(apiClient: ApiClient, expectedBalances: List<ExpectedBalance>, logic: () -> T): T {
        val lastBalanceChange = getLastBalanceChange()

        return logic().also {
            await
                .pollInSameThread()
                .pollDelay(Duration.ofMillis(100))
                .pollInterval(Duration.ofMillis(100))
                .atMost(Duration.ofMillis(20000L))
                .until {
                    lastBalanceChange != getLastBalanceChange()
                }
            verifyBalances(apiClient, expectedBalances)
        }
    }

    fun verifyBalances(apiClient: ApiClient, expectedBalances: List<ExpectedBalance>) {
        val actualBalances = apiClient.getBalances().balances
        expectedBalances.forEach { expected ->
            val actual = actualBalances.first { it.symbol.value == expected.symbol }
            assertEquals(expected.available, actual.available, "${expected.symbol} available balance does not match")
            assertEquals(expected.total, actual.total, "${expected.symbol} total balance does not match")
        }
    }

    private fun getLastBalanceChange() =
        transaction {
            BalanceTable
                .select(BalanceTable.updatedAt.max())
                .maxByOrNull { BalanceTable.updatedAt }?.let { it[BalanceTable.updatedAt.max()] }
        }
}
