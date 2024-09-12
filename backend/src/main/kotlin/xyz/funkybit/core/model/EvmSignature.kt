package xyz.funkybit.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import xyz.funkybit.core.utils.toHex
import xyz.funkybit.core.utils.toHexBytes

@Serializable(with = EvmSignatureSerializer::class)
data class EvmSignature(override val value: String) : Signature() {
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

object EvmSignatureSerializer : KSerializer<EvmSignature> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("EvmSignature", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: EvmSignature) = encoder.encodeString(value.value)
    override fun deserialize(decoder: Decoder) = EvmSignature(decoder.decodeString())
}
