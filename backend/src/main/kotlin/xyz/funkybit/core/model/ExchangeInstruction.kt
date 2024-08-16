package xyz.funkybit.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.serializer

@Serializable(with = ExchangeInstructionSerializer::class)
sealed class ExchangeInstruction {

    @Serializable
    enum class ExchangeInstructionEnum {
        InitState,
    }

    @Serializable
    data class InitStateParams(
        val feeAccount: BitcoinAddress,
        val txHex: ByteArray,
    ) : ExchangeInstruction() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as InitStateParams

            if (feeAccount != other.feeAccount) return false
            if (!txHex.contentEquals(other.txHex)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = feeAccount.hashCode()
            result = 31 * result + txHex.contentHashCode()
            return result
        }
    }
}

object ExchangeInstructionSerializer : KSerializer<ExchangeInstruction> {
    @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = buildSerialDescriptor("Pubkey", StructureKind.LIST)

    @OptIn(InternalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: ExchangeInstruction) {
        when (encoder) {
            is com.funkatronics.kborsh.BorshEncoder -> {
                when (value) {
                    is ExchangeInstruction.InitStateParams -> {
                        encoder.encodeByte((ExchangeInstruction.ExchangeInstructionEnum.InitState.ordinal).toByte())
                        ExchangeInstruction.InitStateParams::class.serializer().serialize(encoder, value)
                    }
                    // add other instructions here
                }
            }
            else -> throw Exception("not required")
        }
    }

    @OptIn(InternalSerializationApi::class)
    override fun deserialize(decoder: Decoder): ExchangeInstruction {
        return when (decoder) {
            is com.funkatronics.kborsh.BorshDecoder -> {
                when (val code = decoder.decodeByte()) {
                    (ExchangeInstruction.ExchangeInstructionEnum.InitState.ordinal).toByte() -> {
                        ExchangeInstruction.InitStateParams::class.serializer().deserialize(decoder)
                    }
                    else -> throw Exception("unknown enum ordinal $code")
                }
            }
            else -> throw Exception("not required")
        }
    }
}

@Serializable
data class ExchangeState(
    val feeAccount: BitcoinAddress,
    val lastSettlementBatchHash: String,
    val lastWithdrawalBatchHash: String,
)
