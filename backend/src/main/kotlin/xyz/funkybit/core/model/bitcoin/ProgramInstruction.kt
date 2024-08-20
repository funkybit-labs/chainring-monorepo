package xyz.funkybit.core.model.bitcoin

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
import xyz.funkybit.core.model.BitcoinAddress

@Serializable(with = ProgramInstructionSerializer::class)
sealed class ProgramInstruction {
    abstract val txHex: SerializedBitcoinTx

    @Serializable
    data class InitStateParams(
        val feeAccount: BitcoinAddress,
        override val txHex: SerializedBitcoinTx,
    ) : ProgramInstruction()

    fun withFeeTx(txHex: SerializedBitcoinTx): ProgramInstruction {
        return when (this) {
            is InitStateParams -> this.copy(txHex = txHex)
        }
    }
}

object ProgramInstructionSerializer : KSerializer<ProgramInstruction> {

    private const val INIT_STATE: Byte = 0

    @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = buildSerialDescriptor("Pubkey", StructureKind.LIST)

    @OptIn(InternalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: ProgramInstruction) {
        when (encoder) {
            is com.funkatronics.kborsh.BorshEncoder -> {
                when (value) {
                    is ProgramInstruction.InitStateParams -> {
                        encoder.encodeByte(INIT_STATE)
                        ProgramInstruction.InitStateParams::class.serializer().serialize(encoder, value)
                    }
                    // add other instructions here
                }
            }
            else -> throw Exception("not required")
        }
    }

    @OptIn(InternalSerializationApi::class)
    override fun deserialize(decoder: Decoder): ProgramInstruction {
        return when (decoder) {
            is com.funkatronics.kborsh.BorshDecoder -> {
                when (val code = decoder.decodeByte()) {
                    INIT_STATE -> {
                        ProgramInstruction.InitStateParams::class.serializer().deserialize(decoder)
                    }
                    else -> throw Exception("unknown enum ordinal $code")
                }
            }
            else -> throw Exception("not required")
        }
    }
}

@Serializable
data class ProgramState(
    val feeAccount: BitcoinAddress,
    val lastSettlementBatchHash: String,
    val lastWithdrawalBatchHash: String,
)
