package co.chainring.apps.api.model
import co.chainring.core.model.Symbol
import co.chainring.core.model.TxHash
import co.chainring.core.model.db.DepositEntity
import co.chainring.core.model.db.DepositId
import co.chainring.core.model.db.DepositStatus
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class CreateDepositApiRequest(
    val symbol: Symbol,
    val amount: BigIntegerJson,
    val txHash: TxHash,
)

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
                    DepositStatus.Pending, DepositStatus.Confirmed -> Status.Pending
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
