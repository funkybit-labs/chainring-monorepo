package co.chainring.core.utils

import org.bouncycastle.util.encoders.Hex

fun generateHexString(length: Int = 64): String {
    val alphaChars = ('0'..'9').toList().toTypedArray() + ('a'..'f').toList().toTypedArray()
    return (1..length).map { alphaChars.random().toChar() }.toMutableList().joinToString("")
}

@OptIn(ExperimentalStdlibApi::class)
fun ByteArray.toHex() = "0x" + this.toHexString(HexFormat.Default)

fun String.toHexBytes() = Hex.decode(this.replace("0x", ""))
