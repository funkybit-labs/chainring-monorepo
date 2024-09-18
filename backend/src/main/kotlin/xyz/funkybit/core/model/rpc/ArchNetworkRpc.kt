package xyz.funkybit.core.model.rpc

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeCollection
import kotlinx.serialization.serializer
import org.bitcoinj.core.ECKey
import org.web3j.crypto.Keys
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.bitcoin.SystemInstruction
import xyz.funkybit.core.model.db.BitcoinUtxoId
import xyz.funkybit.core.model.db.TxHash
import xyz.funkybit.core.utils.doubleSha256FromHex
import xyz.funkybit.core.utils.schnorr.Point
import xyz.funkybit.core.utils.toHex
import xyz.funkybit.core.utils.toHexBytes
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
            is ArchNetworkRpc.Pubkey -> ArchNetworkRpc.Pubkey::class.serializer().serialize(encoder, param)
            is ArchNetworkRpc.RuntimeTransaction -> ArchNetworkRpc.RuntimeTransaction::class.serializer().serialize(encoder, param)
            is List<*> -> encoder.encodeCollection(descriptor, param.size) {
                param.forEachIndexed { index, it ->
                    when (it) {
                        is ArchNetworkRpc.RuntimeTransaction -> encodeSerializableElement(
                            ArchNetworkRpc.RuntimeTransaction::class.serializer().descriptor,
                            index,
                            ArchNetworkRpc.RuntimeTransaction::class.serializer(),
                            it,
                        )
                    }
                }
            }
        }
    }

    override fun deserialize(decoder: Decoder): ArchRpcParams {
        throw Exception("not required")
    }
}

@OptIn(ExperimentalUnsignedTypes::class)
sealed class ArchNetworkRpc {

    @Serializable
    data class RuntimeTransaction(
        val version: Int,
        val signatures: List<Signature>,
        val message: Message,
    ) {
        fun serialize(): ByteArray {
            val serializedMessage = message.serialize()
            val buffer = ByteBuffer.allocate(4 + 1 + 64 * signatures.size + serializedMessage.size)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(version)
            buffer.put(signatures.size.toByte())
            signatures.forEach {
                buffer.put(it.bytes.toByteArray())
            }
            buffer.put(message.serialize())
            return buffer.array()
        }
    }

    @Serializable
    data class Message(
        val signers: List<Pubkey>,
        val instructions: List<Instruction>,
    ) {
        fun hash(): ByteArray {
            return doubleSha256FromHex(serialize())
        }

        fun serialize(): ByteArray {
            val instructionsArrays = instructions.map {
                it.serialize()
            }
            val buffer = ByteBuffer.allocate(2 + 32 * signers.size + instructionsArrays.sumOf { it.size })
            buffer.put(signers.size.toByte())
            signers.forEach {
                buffer.put(it.bytes.toByteArray())
            }
            buffer.put(instructions.size.toByte())
            instructionsArrays.forEach {
                buffer.put(it)
            }
            return buffer.array()
        }
    }

    @Serializable
    data class AccountMeta(
        val pubkey: Pubkey,
        @SerialName("is_signer")
        val isSigner: Boolean,
        @SerialName("is_writable")
        val isWritable: Boolean,
    ) {
        fun serialize(): ByteArray {
            val buffer = ByteBuffer.allocate(34)
            buffer.put(pubkey.bytes.toByteArray())
            buffer.put(if (isSigner) 1 else 0)
            buffer.put(if (isWritable) 1 else 0)
            return buffer.array()
        }
    }

    @Serializable
    data class Instruction(
        @SerialName("program_id")
        val programId: Pubkey,
        val accounts: List<AccountMeta>,
        val data: UByteArray,
    ) {
        fun serialize(): ByteArray {
            val buffer = ByteBuffer.allocate(32 + 1 + 34 * accounts.size + 8 + data.size)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(programId.bytes.toByteArray())
            buffer.put(accounts.size.toByte())
            accounts.forEach {
                buffer.put(it.serialize())
            }
            buffer.putLong(data.size.toLong())
            buffer.put(data.toByteArray())
            return buffer.array()
        }
    }

    @Serializable
    data class UtxoMeta(
        @SerialName("txid")
        val txId: TxHash,
        val vout: Int,
    ) {
        fun toUtxoId() = BitcoinUtxoId.fromTxHashAndVout(txId, vout)

        fun serialize(): ByteArray {
            val buffer = ByteBuffer.allocate(36)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(txId.value.toHexBytes())
            buffer.putInt(vout)
            return buffer.array()
        }
    }

    @Serializable()
    @JvmInline
    value class Pubkey(val bytes: UByteArray) {
        init {
            require(bytes.size == 32) {
                "Pubkey must be 32 bytes"
            }
        }

        companion object {

            val systemProgram = Pubkey(
                UByteArray(32).also {
                    it[31] = 1u
                },
            )

            fun fromECKey(ecKey: ECKey): Pubkey {
                return Pubkey(Point.genPubKey(ecKey.privKeyBytes).toUByteArray())
            }

            fun fromHexString(hex: String): Pubkey {
                return Pubkey(hex.toHexBytes().toUByteArray())
            }
        }

        fun serialize(): ByteArray = bytes.toByteArray()

        fun toContractAddress(): Address {
            return EvmAddress(Keys.toChecksumAddress(bytes.toHex()))
        }
    }

    @Serializable
    @JvmInline
    value class Signature(val bytes: UByteArray)

    @Serializable
    data class AccountInfoResult(
        val owner: Pubkey,
        val data: UByteArray,
        val utxo: String,
        @SerialName("is_executable")
        val isExecutable: Boolean,
    )

    @Serializable
    enum class Status {
        Processing,
        Processed,
        Failed,
    }

    @Serializable
    data class ProcessedTransaction(
        @SerialName("runtime_transaction")
        val runtimeTransaction: RuntimeTransaction,
        val status: Status,
        @SerialName("bitcoin_txids")
        val bitcoinTxIds: List<TxHash>,
    )

    companion object {
        fun createNewAccountInstruction(pubkey: Pubkey, utxoMeta: UtxoMeta): Instruction {
            return createSystemInstructionInstruction(
                pubkey,
                SystemInstruction.CreateNewAccount(utxoMeta),
            )
        }

        fun extendBytesInstruction(pubkey: Pubkey, bytes: ByteArray): Instruction {
            return createSystemInstructionInstruction(
                pubkey,
                SystemInstruction.ExtendBytes(bytes),
            )
        }

        fun makeAccountExecutableInstruction(pubkey: Pubkey): Instruction {
            return createSystemInstructionInstruction(
                pubkey,
                SystemInstruction.MakeAccountExecutable,
            )
        }

        fun changeOwnershipInstruction(pubkey: Pubkey, ownerPubkey: Pubkey): Instruction {
            return createSystemInstructionInstruction(
                pubkey,
                SystemInstruction.ChangeAccountOwnership(ownerPubkey),
            )
        }

        private fun createSystemInstructionInstruction(pubkey: Pubkey, systemInstruction: SystemInstruction): Instruction {
            return Instruction(
                programId = Pubkey.systemProgram,
                accounts = listOf(
                    AccountMeta(
                        pubkey = pubkey,
                        isSigner = true,
                        isWritable = true,
                    ),
                ),
                data = systemInstruction.serialize().toUByteArray(),
            )
        }
    }
}
