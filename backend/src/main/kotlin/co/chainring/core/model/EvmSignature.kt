package co.chainring.core.model

import co.chainring.core.utils.toHex
import kotlinx.serialization.Serializable
import org.bouncycastle.util.encoders.Hex

@Serializable
@JvmInline
value class EvmSignature(val value: String) {
    init {
        require(
            value.length == 130 &&
                value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' },
        ) {
            "Invalid evm signature or not a hex string"
        }
    }

    fun toByteArray() = Hex.decode(value)

    companion object {
        fun emptySignature(): EvmSignature {
            return EvmSignature(ByteArray(65).toHex())
        }
    }
}
