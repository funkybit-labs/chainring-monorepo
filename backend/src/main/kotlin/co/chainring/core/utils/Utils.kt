package co.chainring.core.utils

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import org.bouncycastle.util.encoders.Hex
import java.math.BigInteger

fun generateHexString(length: Int = 64): String {
    val alphaChars = ('0'..'9').toList().toTypedArray() + ('a'..'f').toList().toTypedArray()
    return (1..length).map { alphaChars.random().toChar() }.toMutableList().joinToString("")
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
