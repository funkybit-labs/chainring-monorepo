package co.chainring.apps.api.model
import co.chainring.core.model.Symbol
import co.chainring.core.model.TxHash
import co.chainring.core.model.db.DepositEntity
import co.chainring.core.model.db.DepositId
import co.chainring.core.model.db.DepositStatus
import co.chainring.core.model.db.SymbolEntity
import co.chainring.core.utils.toFundamentalUnits
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
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
