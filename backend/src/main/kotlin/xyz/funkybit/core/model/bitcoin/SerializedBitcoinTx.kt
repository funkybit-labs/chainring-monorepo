package xyz.funkybit.core.model.bitcoin

import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class SerializedBitcoinTx(val value: ByteArray)
