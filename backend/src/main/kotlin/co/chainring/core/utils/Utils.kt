package co.chainring.core.utils

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import org.bouncycastle.util.encoders.Hex
import java.math.BigInteger
import java.security.SecureRandom
import kotlin.text.HexFormat
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
