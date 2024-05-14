package co.chainring.integrationtests.utils

import co.chainring.apps.api.model.Balance
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

data class ExpectedBalance(
    val symbol: String,
    val total: BigInteger,
    val available: BigInteger,
)

fun assertBalances(expectedBalances: List<ExpectedBalance>, actualBalances: List<Balance>) {
    expectedBalances.forEach { expected ->
        val actual = actualBalances.firstOrNull { it.symbol.value == expected.symbol }
        assertNotNull(actual)
        assertEquals(expected.available, actual.available, "${expected.symbol} available balance does not match")
        assertEquals(expected.total, actual.total, "${expected.symbol} total balance does not match")
    }
}
