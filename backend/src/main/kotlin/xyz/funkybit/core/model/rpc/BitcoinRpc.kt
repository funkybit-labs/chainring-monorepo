package xyz.funkybit.core.model.rpc

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
import kotlinx.serialization.encoding.encodeCollection
import xyz.funkybit.apps.api.model.BigDecimalJson
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.db.BitcoinUtxoId
import java.util.*

@Serializable
data class BitcoinRpcRequest(
    val method: String,
    val params: BitcoinRpcParams = BitcoinRpcParams(emptyList<String>()),
    val id: String = UUID.randomUUID().toString(),
    val jsonrpc: String = "2.0",
)

@Serializable(with = BitcoinRpcParamsSerializer::class)
@JvmInline
value class BitcoinRpcParams(val value: Any)

object BitcoinRpcParamsSerializer : KSerializer<BitcoinRpcParams> {
    @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = buildSerialDescriptor("RpcParams", StructureKind.LIST)

    override fun serialize(encoder: Encoder, value: BitcoinRpcParams) {
        when (val param = value.value) {
            is List<*> -> encoder.encodeCollection(descriptor, param.size) {
                param.forEachIndexed { index, it ->
                    when (it) {
                        is String -> encodeSerializableElement(String.serializer().descriptor, index, String.serializer(), it)
                        is Int -> encodeSerializableElement(Int.serializer().descriptor, index, Int.serializer(), it)
                        is Long -> encodeSerializableElement(Long.serializer().descriptor, index, Long.serializer(), it)
                        is Boolean -> encodeSerializableElement(Boolean.serializer().descriptor, index, Boolean.serializer(), it)
                    }
                }
            }
        }
    }

    override fun deserialize(decoder: Decoder): BitcoinRpcParams {
        throw Exception("not required")
    }
}

sealed class BitcoinRpc {
    @Serializable
    data class ScriptPubKey(
        val asm: String,
        val hex: String,
        val reqSigs: Int?,
        val type: String,
        val addresses: List<String>?,
        val address: String? = null,
    )

    @Serializable
    data class ScriptSig(
        val asm: String,
        val hex: String,
    )

    @Serializable
    data class TxIn(
        @SerialName("txid")
        val txId: TxHash?,
        @SerialName("vout")
        val outIndex: Int?,
        val scriptSig: ScriptSig?,
    )

    @Serializable
    data class TxOut(
        val value: BigDecimalJson,
        @SerialName("n")
        val index: Int,
        val scriptPubKey: ScriptPubKey,
    )

    @Serializable
    data class Transaction(
        @SerialName("txid")
        val txId: TxHash,
        val hash: String,
        val size: Int,
        val vsize: Int,
        val weight: Int,
        @SerialName("vin")
        val txIns: List<TxIn>,
        @SerialName("vout")
        val txOuts: List<TxOut>,
        val hex: String,
        val confirmations: Int?,
    ) {
        fun outputsMatchingWallets(addresses: Set<String>) =
            txOuts.filter { txOut ->
                (
                    txOut.scriptPubKey.addresses != null &&
                        txOut.scriptPubKey.addresses.toSet().intersect(addresses).isNotEmpty()
                    ) ||
                    (txOut.scriptPubKey.address != null && addresses.contains(txOut.scriptPubKey.address))
            }

        fun inputsMatchingUnspentUtxos(unspentUtxos: Set<BitcoinUtxoId>) =
            txIns.mapNotNull { txIn ->
                if (txIn.txId != null && txIn.outIndex != null) {
                    BitcoinUtxoId.fromTxHashAndVout(
                        txIn.txId,
                        txIn.outIndex,
                    )
                } else {
                    null
                }
            }.filter {
                unspentUtxos.contains(it)
            }
    }

    @Serializable
    data class Block(
        val hash: String,
        val confirmations: Int,
        @SerialName("nTx")
        val numberOfTx: Int,
        @SerialName("tx")
        val transactions: List<Transaction>?,
        val time: Long,
        @SerialName("mediantime")
        val medianTime: Long,
        @SerialName("chainwork")
        val chainWork: String,
        val nonce: Long,
        val bits: String,
        @SerialName("previousblockhash")
        val previousBlockhash: String?,
        @SerialName("nextblockhash")
        val nextBlockhash: String?,
    )

    @Serializable
    data class SmartFeeInfo(
        @SerialName("feerate")
        val feeRate: BigDecimalJson?,
        val errors: List<String>?,
        val blocks: Int?,
    )
}
