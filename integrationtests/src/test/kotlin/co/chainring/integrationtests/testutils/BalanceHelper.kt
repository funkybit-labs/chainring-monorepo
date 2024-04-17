package co.chainring.integrationtests.testutils

import co.chainring.core.model.db.BalanceTable
import co.chainring.core.model.db.SymbolTable
import kotlinx.datetime.Instant
import org.awaitility.kotlin.await
import org.jetbrains.exposed.sql.JoinType
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
        val lastBalanceChanges: List<Pair<String, Instant?>> = expectedBalances.map { it.symbol to getLastBalanceChange(it.symbol) }

        return logic().also {
            await
                .pollInSameThread()
                .pollDelay(Duration.ofMillis(100))
                .pollInterval(Duration.ofMillis(100))
                .atMost(Duration.ofMillis(10000L))
                .until {
                    lastBalanceChanges.all { (symbol, lastBalanceChange) ->
                        lastBalanceChange != getLastBalanceChange(symbol)
                    }
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

    private fun getLastBalanceChange(symbol: String) =
        transaction {
            BalanceTable
                .join(SymbolTable, JoinType.INNER, SymbolTable.guid, BalanceTable.symbolGuid)
                .select(BalanceTable.updatedAt.max())
                .where { SymbolTable.name.eq(symbol) }
                .maxByOrNull { BalanceTable.updatedAt }?.let { it[BalanceTable.updatedAt.max()] }
        }
}
