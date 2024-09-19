package xyz.funkybit.core.model

import kotlinx.serialization.Serializable
import xyz.funkybit.core.utils.generateHexString
import xyz.funkybit.core.utils.toHex

@Serializable
@JvmInline
value class TxHash(val value: String) {
    init {
        require(
            ((value.startsWith("0x") && value.length == 66) || value.length == 64) &&
                value.drop(2).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' },
        ) {
            "Invalid transaction hash or not a hex string"
        }
    }

    companion object {
        fun emptyHash(): TxHash {
            return TxHash(ByteArray(32).toHex())
        }

        fun generate() = TxHash("0x${generateHexString(64)}")

        fun fromDbModel(txHash: xyz.funkybit.core.model.db.TxHash): TxHash = TxHash(txHash.value)
    }
}
