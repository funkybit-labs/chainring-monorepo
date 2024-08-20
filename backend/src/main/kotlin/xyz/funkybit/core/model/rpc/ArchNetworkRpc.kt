package xyz.funkybit.core.model.rpc

import com.funkatronics.kborsh.Borsh
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.serializer
import xyz.funkybit.core.model.bitcoin.UtxoId
import xyz.funkybit.core.model.db.TxHash
import xyz.funkybit.core.utils.sha256
import xyz.funkybit.core.utils.toHex
import java.util.*

@Serializable
data class ArchRpcRequest(
    val method: String,
    val params: ArchRpcParams? = null,
    val id: String = UUID.randomUUID().toString(),
    val jsonrpc: String = "2.0",
)

@Serializable(with = ArchRpcParamsSerializer::class)
@JvmInline
value class ArchRpcParams(val value: Any)

object ArchRpcParamsSerializer : KSerializer<ArchRpcParams> {
    @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = buildSerialDescriptor("RpcParams", StructureKind.LIST)

    @OptIn(InternalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: ArchRpcParams) {
        when (val param = value.value) {
            is String -> encoder.encodeString(param)
            is Int -> encoder.encodeInt(param)
            is Long -> encoder.encodeLong(param)
            is Boolean -> encoder.encodeBoolean(param)
            is ArchNetworkRpc.DeployProgramParams -> ArchNetworkRpc.DeployProgramParams::class.serializer().serialize(encoder, param)
            is ArchNetworkRpc.GetContractAddress -> ArchNetworkRpc.GetContractAddress::class.serializer().serialize(encoder, param)
            is ArchNetworkRpc.RuntimeTransaction -> ArchNetworkRpc.RuntimeTransaction::class.serializer().serialize(encoder, param)
            is ArchNetworkRpc.ReadUtxoParams -> ArchNetworkRpc.ReadUtxoParams::class.serializer().serialize(encoder, param)
        }
    }

    override fun deserialize(decoder: Decoder): ArchRpcParams {
        throw Exception("not required")
    }
}

@OptIn(ExperimentalUnsignedTypes::class)
sealed class ArchNetworkRpc {
    @Serializable
    data class DeployProgramParams(
        val elf: UByteArray,
    )

    @Serializable
    data class GetContractAddress(
        val data: UByteArray,
    )

    @Serializable
    data class RuntimeTransaction(
        val version: Int,
        val signatures: List<Signature>,
        val message: Message,
    )

    @Serializable
    data class Message(
        val signers: List<Pubkey>,
        val instructions: List<Instruction>,
    ) {
        fun hash(): ByteArray {
            return sha256(sha256(Borsh.encodeToByteArray(this)).toHex(false).toByteArray())
        }
    }

    @Serializable
    data class Instruction(
        @SerialName("program_id")
        val programId: Pubkey,
        val utxos: List<UtxoMeta>,
        val data: UByteArray,
    )

    @Serializable
    data class UtxoMeta(
        @SerialName("txid")
        val txId: TxHash,
        val vout: Int,
    ) {
        fun toUtxoId() = UtxoId.fromTxHashAndVout(txId, vout)
    }

    @Serializable(with = PubkeySerializer::class)
    @JvmInline
    value class Pubkey(val bytes: UByteArray) {
        init {
            require(bytes.size == 32) {
                "Pubkey must be 32 bytes"
            }
        }
    }

    // this custom serializer is to handle the Pubkey - in rust it is defined as [u8; 32], so when serializing
    // the length does not precede the array since it's a fixed size. This mimics this behaviour on kotlin since
    // Pubkey is a value class around a UByteArray so default serialization writes the length out
    @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
    object PubkeySerializer : KSerializer<Pubkey> {
        @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
        override val descriptor: SerialDescriptor = buildSerialDescriptor("Pubkey", StructureKind.LIST)

        @OptIn(InternalSerializationApi::class)
        override fun serialize(encoder: Encoder, value: Pubkey) {
            when (encoder) {
                is com.funkatronics.kborsh.BorshEncoder -> {
                    value.bytes.forEach { encoder.encodeByte(it.toByte()) }
                }
                else -> UByteArray::class.serializer().serialize(encoder, value.bytes)
            }
        }

        @OptIn(InternalSerializationApi::class)
        override fun deserialize(decoder: Decoder): Pubkey {
            return when (decoder) {
                is com.funkatronics.kborsh.BorshDecoder -> {
                    Pubkey((0..31).map { decoder.decodeByte() }.toByteArray().toUByteArray())
                }
                else -> Pubkey(UByteArray::class.serializer().deserialize(decoder))
            }
        }
    }

    @Serializable
    @JvmInline
    value class Signature(val bytes: UByteArray)

    @Serializable
    data class ReadUtxoParams(
        @SerialName("utxo_id")
        val utxoId: String,
    )

    @Serializable
    data class ReadUtxoResult(
        @SerialName("utxo_id")
        val utxoId: String,
        val data: UByteArray,
        val authority: Pubkey,
    )

    @Serializable
    enum class Status {
        Processing,
        Success,
        Failed,
    }

    @Serializable
    data class ProcessedTransaction(
        @SerialName("runtime_transaction")
        val runtimeTransaction: RuntimeTransaction,
        val status: Status,
        @SerialName("bitcoin_txids")
        val bitcoinTxIds: Map<String, TxHash>,
    )

    @Serializable
    data class Utxo(
        @SerialName("txid")
        val txId: String,
        val vout: Int,
        val value: ULong,
    )
}
