package xyz.funkybit.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object SignatureSerializer : KSerializer<Signature> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Address", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Signature) = encoder.encodeString(value.value)
    override fun deserialize(decoder: Decoder) = Signature.auto(decoder.decodeString())
}

@Serializable(with = SignatureSerializer::class)
sealed class Signature {
    abstract val value: String

    companion object {
        fun auto(value: String): Signature = when {
            value.startsWith("0x") -> EvmSignature(value)
            else -> BitcoinSignature(value)
        }
    }
}
