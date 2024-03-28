package co.chainring.core.model

import co.chainring.core.utils.toHex
import co.chainring.core.utils.toHexBytes
import kotlinx.serialization.Serializable

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
            return EvmSignature(ByteArray(65).toHex())
        }
    }
}
