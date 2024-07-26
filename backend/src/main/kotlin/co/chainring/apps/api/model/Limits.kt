package co.chainring.apps.api.model

import co.chainring.core.model.db.MarketId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeCollection
import kotlinx.serialization.serializer
import java.math.BigInteger

@Serializable
data class MarketLimits(
    val marketId: MarketId,
    val base: BigIntegerJson,
    val quote: BigIntegerJson,
) {
    object AsArraySerializer : KSerializer<MarketLimits> {
        private val marketIdSerializer = serializer(MarketId::class.javaObjectType)
        private val baseSerializer = BigIntegerSerializer
        private val quoteSerializer = BigIntegerSerializer

        @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
        override val descriptor: SerialDescriptor = buildSerialDescriptor("MarketLimits", StructureKind.LIST) {
            element("marketId", marketIdSerializer.descriptor)
            element("base", baseSerializer.descriptor)
            element("quote", quoteSerializer.descriptor)
        }

        override fun serialize(encoder: Encoder, value: MarketLimits) =
            encoder.encodeCollection(descriptor, 3) {
                encodeSerializableElement(marketIdSerializer.descriptor, 0, marketIdSerializer, value.marketId)
                encodeSerializableElement(baseSerializer.descriptor, 1, baseSerializer, value.base)
                encodeSerializableElement(quoteSerializer.descriptor, 2, quoteSerializer, value.quote)
            }

        override fun deserialize(decoder: Decoder): MarketLimits =
            decoder.decodeStructure(descriptor) {
                var marketId: MarketId? = null
                var base: BigInteger? = null
                var quote: BigInteger? = null

                while (true) {
                    when (val index = decodeElementIndex(descriptor)) {
                        0 -> marketId = decodeSerializableElement(marketIdSerializer.descriptor, 0, marketIdSerializer) as MarketId
                        1 -> base = decodeSerializableElement(baseSerializer.descriptor, 1, baseSerializer)
                        2 -> quote = decodeSerializableElement(quoteSerializer.descriptor, 2, quoteSerializer)
                        CompositeDecoder.DECODE_DONE -> break
                        else -> error("Unexpected index: $index")
                    }
                }
                MarketLimits(
                    marketId ?: throw SerializationException("Market id is missing in json array"),
                    base = base ?: throw SerializationException("Base limit is missing in json array"),
                    quote = quote ?: throw SerializationException("Quote limit is missing in json array"),
                )
            }
    }
}

@Serializable
data class GetLimitsApiResponse(
    val limits: List<MarketLimits>,
)
