package xyz.funkybit.core.model

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class UtxoId(val value: String) {
    init {

        require(value.split(":").size == 2) {
            "Invalid utxoId format"
        }
    }
    companion object {

        fun fromTxHashAndVout(txId: xyz.funkybit.core.model.db.TxHash, vout: Int): UtxoId =
            UtxoId("${txId.value}:$vout")
    }

    fun txId(): xyz.funkybit.core.model.db.TxHash = xyz.funkybit.core.model.db.TxHash(value.split(":")[0])

    fun vout(): Long = value.split(":")[1].toLong()
}
