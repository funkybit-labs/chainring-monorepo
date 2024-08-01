package xyz.funkybit.core.model

import kotlinx.serialization.Serializable
import xyz.funkybit.core.utils.toHex
import xyz.funkybit.core.utils.toHexBytes

@Serializable
@JvmInline
value class EvmSignature(val value: String) {
    init {
        require(
            value.startsWith("0x") && value.length == 132 &&
                value.drop(2).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' },
        ) {
            "Invalid evm signature or not a hex string"
        }
    }

    fun toByteArray() = value.toHexBytes()

    companion object {
        fun emptySignature(): EvmSignature {
            return ByteArray(65).toHex().toEvmSignature()
        }
    }
}

fun String.toEvmSignature(): EvmSignature {
    return EvmSignature(this)
}
