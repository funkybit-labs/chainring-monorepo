package co.chainring.core.model.rpc

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.serializer
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
            is ArchNetworkRpc.AssignAuthorityParams -> ArchNetworkRpc.AssignAuthorityParams::class.serializer().serialize(encoder, param)
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
    )

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
        val txId: String,
        val vout: Int,
    )

    @Serializable
    @JvmInline
    value class Pubkey(val bytes: UByteArray)
    // TODO verify 32 bytes

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
        val authority: UByteArray,
    )

    @Serializable
    enum class Status {
        Processing,
        Sucess, // was this way in rust code
        Failed,
    }

    @Serializable
    data class ProcessedTransaction(
        @SerialName("runtime_transaction")
        val runtimeTransaction: RuntimeTransaction,
        // val receipts: Map<String, Receipt>,  // TODO Receipt comes from risc0-zkvm and is large
        val status: Status,
        @SerialName("bitcoin_txids")
        val bitcoinTxIds: Map<String, String>,
    )

    @Serializable
    data class Utxo(
        @SerialName("txid")
        val txId: String,
        val vout: Int,
        val value: ULong,
    )

    @Serializable
    data class AuthorityMessage(
        val utxo: Utxo,
        val data: UByteArray,
        val authority: Pubkey,
    )

    @Serializable
    data class AssignAuthorityParams(
        val signature: Signature,
        val message: AuthorityMessage,
    )
}
