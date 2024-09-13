package xyz.funkybit.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Serializable(with = BitcoinSignatureSerializer::class)
data class BitcoinSignature(override val value: String) : Signature() {

    companion object {
        @OptIn(ExperimentalEncodingApi::class, ExperimentalStdlibApi::class)
        fun emptySignature(): BitcoinSignature {
            return BitcoinSignature(
                value = Base64.encode("0".repeat(130).hexToByteArray()),
            )
        }
    }
}

object BitcoinSignatureSerializer : KSerializer<BitcoinSignature> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("BitcoinSignature", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: BitcoinSignature) = encoder.encodeString(value.value)
    override fun deserialize(decoder: Decoder) = BitcoinSignature(decoder.decodeString())
}
