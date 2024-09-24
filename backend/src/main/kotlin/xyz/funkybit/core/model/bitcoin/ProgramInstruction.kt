package xyz.funkybit.core.model.bitcoin

import com.funkatronics.kborsh.Borsh
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.serializer
import org.bitcoinj.core.NetworkParameters
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.rpc.ArchNetworkRpc

@Serializable(with = ProgramInstructionSerializer::class)
sealed class ProgramInstruction {

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
        val addressIndex: UInt,
        val amount: ULong,
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
        val addressIndex: UInt,
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
        val tokenWithdrawalsList: List<TokenWithdrawals>,
        val changeAmount: ULong,
        val txHex: ByteArray,
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

    @OptIn(ExperimentalUnsignedTypes::class)
    fun serialize() = Borsh.encodeToByteArray(this).toUByteArray()
}

@Serializable
enum class BitcoinNetworkType {
    Bitcoin,
    Testnet,
    Signet,
    Regtest,
    ;

    companion object {
        fun fromNetworkParams(networkParams: NetworkParameters): BitcoinNetworkType {
            return when (networkParams.id) {
                NetworkParameters.ID_MAINNET -> Bitcoin
                NetworkParameters.ID_TESTNET -> Testnet
                NetworkParameters.ID_REGTEST -> Regtest
                else -> Signet
            }
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

    @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = buildSerialDescriptor("Pubkey", StructureKind.LIST)

    @OptIn(InternalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: ProgramInstruction) {
        when (encoder) {
            is com.funkatronics.kborsh.BorshEncoder -> {
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

    @Serializable
    data class Program(
        val version: Short,
        val feeAccount: BitcoinAddress,
        val programChangeAddress: BitcoinAddress,
        val networkType: BitcoinNetworkType,
        val settlementBatchHash: String,
        val lastSettlementBatchHash: String,
        val lastWithdrawalBatchHash: String,
    )

    @Serializable
    data class Balance(
        val walletAddress: String,
        val balance: ULong,
    )

    @Serializable
    data class Token(
        val version: Short,
        val programStateAccount: ArchNetworkRpc.Pubkey,
        val tokenId: String,
        val balances: List<Balance>,
    )
}
