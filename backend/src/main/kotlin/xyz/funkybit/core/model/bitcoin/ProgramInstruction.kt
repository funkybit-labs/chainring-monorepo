package xyz.funkybit.core.model.bitcoin

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.serializer
import org.bitcoinj.core.NetworkParameters
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.rpc.ArchNetworkRpc
import xyz.funkybit.core.utils.bitcoin.ExchangeProgramProtocolDecoder
import xyz.funkybit.core.utils.bitcoin.ExchangeProgramProtocolEncoder
import xyz.funkybit.core.utils.bitcoin.ExchangeProgramProtocolFormat
import xyz.funkybit.core.utils.toHex

@Serializable(with = ProgramInstructionSerializer::class)
sealed class ProgramInstruction {

    @JvmInline
    @Serializable(with = WalletLast4Serializer::class)
    value class WalletLast4(val value: ByteArray) {
        init {
            require(value.size == 4) { "Invalid wallet last 4 - size is ${value.size}" }
        }

        companion object {
            fun fromWalletAddress(address: BitcoinAddress): WalletLast4 {
                return WalletLast4(address.value.toByteArray().takeLast(4).toByteArray())
            }
        }
    }

    @Serializable
    data class InitProgramStateParams(
        val feeAccount: BitcoinAddress,
        val programChangeAddress: BitcoinAddress,
        val networkType: BitcoinNetworkType,
    ) : ProgramInstruction()

    @Serializable
    data class InitTokenStateParams(
        val tokenId: String,
    ) : ProgramInstruction()

    @Serializable
    data class TokenBalanceSetup(
        val accountIndex: UByte,
        val walletAddresses: List<String>,
    )

    @Serializable
    data class InitTokenBalancesParams(
        val tokenBalanceSetups: List<TokenBalanceSetup>,
    ) : ProgramInstruction()

    @Serializable
    data class Adjustment(
        val addressIndex: AddressIndex,
        val amount: ULong,
    )

    @Serializable
    data class AddressIndex(
        val index: UInt,
        val last4: WalletLast4,
    )

    @Serializable
    data class TokenDeposits(
        val accountIndex: UByte,
        val deposits: List<Adjustment>,
    )

    @Serializable
    data class DepositBatchParams(
        val tokenDepositsList: List<TokenDeposits>,
    ) : ProgramInstruction()

    @Serializable
    data class Withdrawal(
        val addressIndex: AddressIndex,
        val amount: ULong,
        val feeAmount: ULong,
    )

    @Serializable
    data class TokenWithdrawals(
        val accountIndex: UByte,
        val withdrawals: List<Withdrawal>,
    )

    @Serializable
    data class WithdrawBatchParams(
        val txHex: ByteArray,
        val changeAmount: ULong,
        val tokenWithdrawalsList: List<TokenWithdrawals>,
    ) : ProgramInstruction()

    @Serializable
    data class SettlementAdjustments(
        val accountIndex: UByte,
        val increments: List<Adjustment>,
        val decrements: List<Adjustment>,
        val feeAmount: ULong,
    )

    @Serializable
    data class PrepareSettlementBatchParams(
        val settlements: List<SettlementAdjustments>,
    ) : ProgramInstruction()

    @Serializable
    data class SubmitSettlementBatchParams(
        val settlements: List<SettlementAdjustments>,
    ) : ProgramInstruction()

    @Serializable
    data object RollbackSettlement : ProgramInstruction()

    @Serializable
    data class RollbackWithdrawBatchParams(
        val tokenWithdrawalsList: List<TokenWithdrawals>,
    ) : ProgramInstruction()

    @OptIn(ExperimentalUnsignedTypes::class)
    fun serialize() = ExchangeProgramProtocolFormat.encodeToByteArray(this).toUByteArray()
}

@Serializable
enum class BitcoinNetworkType {
    Mainnet,
    Testnet,
    Signet,
    Regtest,
    ;

    companion object {
        fun fromNetworkParams(networkParams: NetworkParameters): BitcoinNetworkType {
            return when (networkParams.id) {
                NetworkParameters.ID_MAINNET -> Mainnet
                NetworkParameters.ID_TESTNET -> Testnet
                NetworkParameters.ID_REGTEST -> Regtest
                else -> Signet
            }
        }

        fun fromByte(ordinal: Int): BitcoinNetworkType {
            return when (ordinal) {
                0 -> Mainnet
                1 -> Testnet
                3 -> Regtest
                else -> Signet
            }
        }
    }
}

object WalletLast4Serializer : KSerializer<ProgramInstruction.WalletLast4> {

    @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = buildSerialDescriptor("WalletLast4", StructureKind.LIST)

    override fun serialize(encoder: Encoder, value: ProgramInstruction.WalletLast4) {
        when (encoder) {
            is ExchangeProgramProtocolEncoder -> {
                (0 until 4).forEach { encoder.encodeByte(value.value[it]) }
            }
            else -> throw Exception("not required")
        }
    }

    override fun deserialize(decoder: Decoder): ProgramInstruction.WalletLast4 {
        return when (decoder) {
            is ExchangeProgramProtocolDecoder -> {
                ProgramInstruction.WalletLast4((0 until 4).map { decoder.decodeByte() }.toByteArray())
            }
            else -> throw Exception("not required")
        }
    }
}

object ProgramInstructionSerializer : KSerializer<ProgramInstruction> {

    private const val INIT_PROGRAM_STATE: Byte = 0
    private const val INIT_TOKEN_STATE: Byte = 1
    private const val INIT_TOKEN_BALANCES: Byte = 2
    private const val DEPOSIT_BATCH: Byte = 3
    private const val WITHDRAW_BATCH: Byte = 4
    private const val PREPARE_SETTLEMENT_BATCH: Byte = 5
    private const val SUBMIT_SETTLEMENT_BATCH: Byte = 6
    private const val ROLLBACK_SETTLEMENT_BATCH: Byte = 7
    private const val ROLLBACK_WITHDRAW_BATCH: Byte = 8

    @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = buildSerialDescriptor("ProgramInstruction", StructureKind.LIST)

    @OptIn(InternalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: ProgramInstruction) {
        when (encoder) {
            is ExchangeProgramProtocolEncoder -> {
                when (value) {
                    is ProgramInstruction.InitProgramStateParams -> {
                        encoder.encodeByte(INIT_PROGRAM_STATE)
                        ProgramInstruction.InitProgramStateParams::class.serializer().serialize(encoder, value)
                    }
                    is ProgramInstruction.InitTokenStateParams -> {
                        encoder.encodeByte(INIT_TOKEN_STATE)
                        ProgramInstruction.InitTokenStateParams::class.serializer().serialize(encoder, value)
                    }
                    is ProgramInstruction.InitTokenBalancesParams -> {
                        encoder.encodeByte(INIT_TOKEN_BALANCES)
                        ProgramInstruction.InitTokenBalancesParams::class.serializer().serialize(encoder, value)
                    }
                    is ProgramInstruction.DepositBatchParams -> {
                        encoder.encodeByte(DEPOSIT_BATCH)
                        ProgramInstruction.DepositBatchParams::class.serializer().serialize(encoder, value)
                    }
                    is ProgramInstruction.WithdrawBatchParams -> {
                        encoder.encodeByte(WITHDRAW_BATCH)
                        ProgramInstruction.WithdrawBatchParams::class.serializer().serialize(encoder, value)
                    }
                    is ProgramInstruction.PrepareSettlementBatchParams -> {
                        encoder.encodeByte(PREPARE_SETTLEMENT_BATCH)
                        ProgramInstruction.PrepareSettlementBatchParams::class.serializer().serialize(encoder, value)
                    }
                    is ProgramInstruction.SubmitSettlementBatchParams -> {
                        encoder.encodeByte(SUBMIT_SETTLEMENT_BATCH)
                        ProgramInstruction.SubmitSettlementBatchParams::class.serializer().serialize(encoder, value)
                    }
                    is ProgramInstruction.RollbackSettlement -> {
                        encoder.encodeByte(ROLLBACK_SETTLEMENT_BATCH)
                    }
                    is ProgramInstruction.RollbackWithdrawBatchParams -> {
                        encoder.encodeByte(ROLLBACK_WITHDRAW_BATCH)
                        ProgramInstruction.RollbackWithdrawBatchParams::class.serializer().serialize(encoder, value)
                    }
                }
            }
            else -> throw Exception("not required")
        }
    }

    @OptIn(InternalSerializationApi::class)
    override fun deserialize(decoder: Decoder): ProgramInstruction {
        return when (decoder) {
            is ExchangeProgramProtocolDecoder -> {
                when (val code = decoder.decodeByte()) {
                    INIT_PROGRAM_STATE -> ProgramInstruction.InitProgramStateParams::class.serializer().deserialize(decoder)
                    INIT_TOKEN_STATE -> ProgramInstruction.InitTokenStateParams::class.serializer().deserialize(decoder)
                    else -> throw Exception("unknown enum ordinal $code")
                }
            }
            else -> throw Exception("not required")
        }
    }
}

sealed class ArchAccountState {

    companion object {

        private const val MAX_ADDRESS_LENGTH = 92
        private const val BYTE_ZERO = 0.toByte()

        fun deserializeAddress(bytes: ByteArray): BitcoinAddress {
            return BitcoinAddress.canonicalize(deserializePaddedString(bytes, MAX_ADDRESS_LENGTH))
        }

        fun deserializePaddedString(bytes: ByteArray, maxLength: Int): String {
            val position = bytes.indexOfFirst { it == BYTE_ZERO }
            return String(bytes.slice(0 until minOf(if (position == -1) maxLength else position, maxLength)).toByteArray())
        }
    }

    @Serializable(with = ProgramSerializer::class)
    data class Program(
        val version: Int,
        val feeAccount: BitcoinAddress,
        val programChangeAddress: BitcoinAddress,
        val networkType: BitcoinNetworkType,
        val settlementBatchHash: TxHash,
        val lastSettlementBatchHash: TxHash,
        val lastWithdrawalBatchHash: TxHash,
    )

    object ProgramSerializer : KSerializer<Program> {

        @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
        override val descriptor: SerialDescriptor = buildSerialDescriptor("Program", StructureKind.LIST)

        override fun serialize(encoder: Encoder, value: Program) {
            throw Exception("not required")
        }

        override fun deserialize(decoder: Decoder): Program {
            return when (decoder) {
                is ExchangeProgramProtocolDecoder -> {
                    Program(
                        version = decoder.decodeInt(),
                        feeAccount = deserializeAddress((0 until MAX_ADDRESS_LENGTH).map { decoder.decodeByte() }.toByteArray()),
                        programChangeAddress = deserializeAddress((0 until MAX_ADDRESS_LENGTH).map { decoder.decodeByte() }.toByteArray()),
                        networkType = BitcoinNetworkType.fromByte(decoder.decodeByte().toInt()),
                        settlementBatchHash = TxHash(((0 until 32).map { decoder.decodeByte() }.toByteArray()).toHex(false)),
                        lastSettlementBatchHash = TxHash(((0 until 32).map { decoder.decodeByte() }.toByteArray()).toHex(false)),
                        lastWithdrawalBatchHash = TxHash(((0 until 32).map { decoder.decodeByte() }.toByteArray()).toHex(false)),
                    )
                }
                else -> throw Exception("not required")
            }
        }
    }

    @Serializable(with = BalanceSerializer::class)
    data class Balance(
        val walletAddress: BitcoinAddress,
        val balance: ULong,
    )

    object BalanceSerializer : KSerializer<Balance> {

        @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
        override val descriptor: SerialDescriptor = buildSerialDescriptor("Balance", StructureKind.LIST)

        override fun serialize(encoder: Encoder, value: Balance) {
            throw Exception("not required")
        }

        override fun deserialize(decoder: Decoder): Balance {
            return when (decoder) {
                is ExchangeProgramProtocolDecoder -> {
                    Balance(
                        walletAddress = deserializeAddress((0 until MAX_ADDRESS_LENGTH).map { decoder.decodeByte() }.toByteArray()),
                        balance = decoder.decodeLong().toULong(),
                    )
                }
                else -> throw Exception("not required")
            }
        }
    }

    @Serializable(with = TokenSerializer::class)
    data class Token(
        val version: Int,
        val programStateAccount: ArchNetworkRpc.Pubkey,
        val tokenId: String,
        val balances: List<Balance>,
    ) {
        companion object {
            private const val TOKEN_STATE_HEADER_SIZE = 4 + 32 + 32 + 4
            private const val BALANCE_SIZE = MAX_ADDRESS_LENGTH + 8

            fun getBalanceAtIndex(data: ByteArray, balanceIndex: Int): Balance {
                val offset = TOKEN_STATE_HEADER_SIZE + balanceIndex * BALANCE_SIZE
                return ExchangeProgramProtocolFormat.decodeFromByteArray<Balance>(data.slice(offset until offset + BALANCE_SIZE).toByteArray())
            }
        }
    }

    object TokenSerializer : KSerializer<Token> {

        @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
        override val descriptor: SerialDescriptor = buildSerialDescriptor("Token", StructureKind.LIST)

        override fun serialize(encoder: Encoder, value: Token) {
            throw Exception("not required")
        }

        @OptIn(InternalSerializationApi::class)
        override fun deserialize(decoder: Decoder): Token {
            return when (decoder) {
                is ExchangeProgramProtocolDecoder -> {
                    Token(
                        version = decoder.decodeInt(),
                        programStateAccount = ArchNetworkRpc.Pubkey::class.serializer().deserialize(decoder),
                        tokenId = deserializePaddedString((0 until 32).map { decoder.decodeByte() }.toByteArray(), 32),
                        balances = (0 until decoder.decodeInt()).map {
                            Balance::class.serializer().deserialize(decoder)
                        },
                    )
                }
                else -> throw Exception("not required")
            }
        }
    }
}
