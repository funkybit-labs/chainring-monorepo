package xyz.funkybit.core.model

import kotlinx.serialization.Serializable
import xyz.funkybit.core.utils.toHex
import xyz.funkybit.core.utils.toHexBytes

@Serializable
@JvmInline
value class BitcoinSignature(val value: String) {
    init {
        require(
            // TODO check length and format
            true,
        ) {
            "Invalid bitcoin signature format"
        }
    }

    fun toByteArray() = value.toHexBytes()

    companion object {
        fun emptySignature(): BitcoinSignature {
            return ByteArray(65).toHex().toBitcoinSignature()
        }
    }
}

fun String.toBitcoinSignature(): BitcoinSignature {
    return BitcoinSignature(this)
}
