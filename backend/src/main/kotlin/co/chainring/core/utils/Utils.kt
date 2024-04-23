package co.chainring.core.utils

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.bouncycastle.util.encoders.Hex

fun generateHexString(length: Int = 64): String {
    val alphaChars = ('0'..'9').toList().toTypedArray() + ('a'..'f').toList().toTypedArray()
    return (1..length).map { alphaChars.random().toChar() }.toMutableList().joinToString("")
}

@OptIn(ExperimentalStdlibApi::class)
fun ByteArray.toHex() = "0x" + this.toHexString(HexFormat.Default)

fun String.toHexBytes() = Hex.decode(this.replace("0x", ""))

fun LocalTime.truncateTo(unit: DateTimeUnit.TimeBased): LocalTime =
    LocalTime.fromNanosecondOfDay(toNanosecondOfDay().let { it - it % unit.nanoseconds })

fun LocalDateTime.truncateTo(unit: DateTimeUnit.TimeBased): LocalDateTime =
    LocalDateTime(date, time.truncateTo(unit))

fun Instant.truncateTo(unit: DateTimeUnit.TimeBased): Instant =
    toLocalDateTime(TimeZone.UTC).truncateTo(unit).toInstant(TimeZone.UTC)
