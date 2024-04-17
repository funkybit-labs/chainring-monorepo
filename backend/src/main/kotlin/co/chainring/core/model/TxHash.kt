package co.chainring.core.model

import co.chainring.core.utils.toHex
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class TxHash(val value: String) {
    init {
        require(
            value.startsWith("0x") && value.length == 66 &&
                value.drop(2).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' },
        ) {
            "Invalid transaction hash or not a hex string"
        }
    }

    companion object {
        fun emptyHash(): TxHash {
            return TxHash(ByteArray(65).toHex())
        }
    }
}
