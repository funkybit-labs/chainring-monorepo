package xyz.funkybit.core.utils

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import org.bouncycastle.util.encoders.Hex
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.notifyDbListener
import xyz.funkybit.core.repeater.REPEATER_APP_TASK_CTL_CHANNEL
import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
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

fun generateHexString(length: Int = 64): String {
    val alphaChars = ('0'..'9').toList().toTypedArray() + ('a'..'f').toList().toTypedArray()
    return (1..length).map { alphaChars.random().toChar() }.toMutableList().joinToString("")
}

fun generateRandomBytes(length: Int): ByteArray {
    val bytes = ByteArray(length).also {
        SecureRandom().nextBytes(it)
    }
    return bytes
}

fun generateOrderNonce() = generateHexString(32)

@OptIn(ExperimentalStdlibApi::class)
fun ByteArray.toHex(add0x: Boolean = true) = (if (add0x) "0x" else "") + this.toHexString(HexFormat.Default)

@OptIn(ExperimentalStdlibApi::class, ExperimentalUnsignedTypes::class)
fun UByteArray.toHex(add0x: Boolean = true) = (if (add0x) "0x" else "") + this.toHexString(HexFormat.Default)

fun String.toHexBytes() = Hex.decode(this.replace("0x", ""))

fun Instant.truncateTo(unit: DateTimeUnit.TimeBased): Instant {
    return when (unit) {
        DateTimeUnit.NANOSECOND -> throw IllegalArgumentException("Truncation of nanoseconds is not supported")
        else -> Instant.fromEpochMilliseconds(toEpochMilliseconds().let { it - it % unit.duration.inWholeMilliseconds })
    }
}

fun ByteArray.toPaddedHexString(length: Int) = joinToString("") { "%02X".format(it) }.padStart(length, '0')

fun ByteArray.pad(length: Int) = Hex.decode(this.toPaddedHexString(length * 2))

fun BigInteger.toByteArrayNoSign(len: Int = 32): ByteArray {
    val byteArray = this.toByteArray()
    return when {
        byteArray.size == len + 1 && byteArray[0].compareTo(0) == 0 -> byteArray.slice(IntRange(1, byteArray.size - 1)).toByteArray()
        byteArray.size < len -> byteArray.pad(len)
        else -> byteArray
    }
}

fun tryAcquireAdvisoryLock(keyId: Long): Boolean {
    var ret = false
    TransactionManager.current().exec(
        "SELECT pg_try_advisory_xact_lock($keyId::bigint)",
    ) { rs ->
        if (rs.next()) {
            ret = rs.getBoolean(1)
        }
    }
    return ret
}

operator fun BigInteger.rangeTo(other: BigInteger) =
    BigIntegerRange(this, other)

class BigIntegerRange(
    override val start: BigInteger,
    override val endInclusive: BigInteger,
) : ClosedRange<BigInteger>, Iterable<BigInteger> {
    override operator fun iterator(): Iterator<BigInteger> =
        BigIntegerRangeIterator(this)
}

class BigIntegerRangeIterator(
    private val range: ClosedRange<BigInteger>,
) : Iterator<BigInteger> {
    private var current = range.start

    override fun hasNext(): Boolean =
        current <= range.endInclusive

    override fun next(): BigInteger {
        if (!hasNext()) {
            throw NoSuchElementException()
        }
        return current++
    }
}

fun BigInteger.safeToInt(): Int? =
    runCatching {
        this.intValueExact()
    }.recover { exception ->
        when (exception) {
            is ArithmeticException -> null
            else -> throw exception
        }
    }.getOrNull()

fun sha256(b: ByteArray?): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(b)
}

fun doubleSha256(b: ByteArray?) = sha256(sha256(b))

// this is to match arch - they sha256 but then second sha256 is of the hex string of the first sha256
fun doubleSha256FromHex(b: ByteArray?) = sha256(sha256(b).toHex(false).toByteArray())

fun List<BigInteger>.sum() = this.reduce { a, b -> a + b }
fun List<BigDecimal>.sum() = this.reduce { a, b -> a + b }

fun triggerRepeaterTask(taskName: String, taskArgs: List<String> = emptyList()) {
    transaction {
        notifyDbListener(REPEATER_APP_TASK_CTL_CHANNEL, (listOf(taskName) + taskArgs).joinToString(":"))
    }
}
