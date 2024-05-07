package co.chainring.integrationtests.utils

import co.chainring.apps.api.model.Balance
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration

fun humanReadable(duration: Duration): String {
    return humanReadableNanoseconds((duration.inWholeNanoseconds))
}

fun humanReadableNanoseconds(ns: Long): String {
    val minutes = ns / 60_000_000_000.0
    if (minutes >= 1) return "%.1fm".format(minutes)

    val seconds = ns / 1_000_000_000.0
    if (seconds >= 1) return "%.1fs".format(seconds)

    val milliseconds = ns / 1_000_000.0
    if (milliseconds >= 1) return "%.1fms".format(milliseconds)

    val microseconds = ns / 1_000.0
    if (microseconds >= 1) return "%.1fµs".format(microseconds)

    return "${ns}ns"
}

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
