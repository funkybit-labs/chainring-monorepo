package xyz.funkybit.core.model.rpc

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeCollection
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.serializer
import org.bitcoinj.core.ECKey
import org.web3j.crypto.Keys
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.bitcoin.SystemInstruction
import xyz.funkybit.core.utils.bitcoin.ExchangeProgramProtocolDecoder
import xyz.funkybit.core.utils.bitcoin.ExchangeProgramProtocolEncoder
import xyz.funkybit.core.utils.doubleSha256FromHex
import xyz.funkybit.core.utils.schnorr.Point
import xyz.funkybit.core.utils.toHex
import xyz.funkybit.core.utils.toHexBytes
import xyz.funkybit.core.utils.toJsonElement
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

    enum class ContractError(code: Int) {
        INVALID_ADDRESS_INDEX(601),
        INVALID_ACCOUNT_INDEX(602),
        INSUFFICIENT_BALANCE(603),
        ADDRESS_MISMATCH(604),
        SETTLEMENT_IN_PROGRESS(605),
        NO_SETTLEMENT_IN_PROGRESS(606),
        SETTLEMENT_BATCH_MISMATCH(607),
        NETTING(608),
        ALREADY_INITIALIZED(609),
        PROGRAM_STATE_MISMATCH(610),
        NO_OUTPUTS_ALLOWED(611),
        INVALID_ADDRESS(612),
        INVALID_SIGNER(613),
        VALUE_TOO_LONG(614),
        WALLET_LAST4_MISMATCH(615),
        INVALID_ADDRESS_NETWORK(616),
        INVALID_INPUT_TX(617),
        ;

        companion object {
            fun fromCode(code: Int): ContractError? {
                return entries.firstOrNull { it.ordinal == code - 601 }
            }
        }
    }

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
        fun serialize(): ByteArray {
            val buffer = ByteBuffer.allocate(36)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(txId.value.toHexBytes())
            buffer.putInt(vout)
            return buffer.array()
        }
    }

    @Serializable(with = PubkeySerializer::class)
    data class Pubkey(val bytes: UByteArray) {
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

        fun toHexString() = serialize().toHex(false)
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Pubkey

            return bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int {
            return bytes.hashCode()
        }
    }

    @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
    object PubkeySerializer : KSerializer<Pubkey> {
        @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
        override val descriptor: SerialDescriptor = buildSerialDescriptor("Pubkey", StructureKind.LIST)

        @OptIn(InternalSerializationApi::class)
        override fun serialize(encoder: Encoder, value: Pubkey) {
            when (encoder) {
                is ExchangeProgramProtocolEncoder -> {
                    value.bytes.forEach { encoder.encodeByte(it.toByte()) }
                }
                else -> UByteArray::class.serializer().serialize(encoder, value.bytes)
            }
        }

        @OptIn(InternalSerializationApi::class)
        override fun deserialize(decoder: Decoder): Pubkey {
            return when (decoder) {
                is ExchangeProgramProtocolDecoder -> {
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

    data class ErrorInfo(
        val msg: String,
        val code: ContractError?,
    )

    data class StatusInfo(
        val status: Status,
        val errorInfo: ErrorInfo?,
    )

    object StatusSerializer : JsonTransformingSerializer<String>(serializer()) {
        override fun transformDeserialize(element: JsonElement): JsonElement =
            if (element is JsonObject) {
                element["Failed"] ?: "unknown".toJsonElement()
            } else {
                element
            }
    }

    @Serializable
    data class ProcessedTransaction(
        @SerialName("runtime_transaction")
        val runtimeTransaction: RuntimeTransaction,
        @SerialName("status")
        @Serializable(with = StatusSerializer::class)
        val rawStatus: String,
        @SerialName("bitcoin_txid")
        val bitcoinTxId: TxHash?,
    ) {
        @OptIn(ExperimentalStdlibApi::class)
        @Transient
        val statusInfo: StatusInfo = when (rawStatus) {
            "Processed" -> StatusInfo(Status.Processed, null)
            "Processing" -> StatusInfo(Status.Processing, null)
            else -> {
                val errorCode = Regex(".*Custom program error: (.*)$").matchEntire(rawStatus)?.let { match ->
                    if (match.groups.size == 2) {
                        ContractError.fromCode(match.groups[1]!!.value.hexToInt(HexFormat { number.prefix = "0x" }))
                    } else {
                        null
                    }
                }
                StatusInfo(Status.Failed, ErrorInfo(rawStatus, errorCode))
            }
        }
    }

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
