package xyz.funkybit.apps.api.model
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.db.DepositEntity
import xyz.funkybit.core.model.db.DepositId
import xyz.funkybit.core.model.db.DepositStatus
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.utils.toFundamentalUnits
import java.math.BigDecimal

@Serializable
data class CreateDepositApiRequest(
    val symbol: Symbol,
    val amount: BigIntegerJson,
    val txHash: TxHash,
) {
    constructor(symbol: SymbolEntity, amount: BigDecimal, txHash: TxHash) :
        this(Symbol(symbol.name), amount.toFundamentalUnits(symbol.decimals), txHash)
}

@Serializable
data class Deposit(
    val id: DepositId,
    val symbol: Symbol,
    val amount: BigIntegerJson,
    val status: Status,
    val error: String?,
    val createdAt: Instant,
    val txHash: TxHash,
) {
    companion object {
        fun fromEntity(entity: DepositEntity): Deposit {
            return Deposit(
                entity.id.value,
                Symbol(entity.symbol.name),
                entity.amount,
                when (entity.status) {
                    DepositStatus.Pending, DepositStatus.Confirmed, DepositStatus.SentToSequencer -> Status.Pending
                    DepositStatus.Complete -> Status.Complete
                    DepositStatus.Failed -> Status.Failed
                },
                entity.error,
                entity.createdAt,
                entity.transactionHash,
            )
        }
    }

    @Serializable
    enum class Status {
        Pending,
        Complete,
        Failed,
    }
}

@Serializable
data class DepositApiResponse(
    val deposit: Deposit,
)

@Serializable
data class ListDepositsApiResponse(
    val deposits: List<Deposit>,
)
