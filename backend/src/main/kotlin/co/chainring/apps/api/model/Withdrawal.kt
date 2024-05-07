package co.chainring.apps.api.model

import co.chainring.core.model.EvmSignature
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.model.db.WithdrawalId
import co.chainring.core.model.db.WithdrawalStatus
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class CreateWithdrawalApiRequest(
    val symbol: Symbol,
    val amount: BigIntegerJson,
    val nonce: Long,
    val signature: EvmSignature,
)

@Serializable
data class Withdrawal(
    val id: WithdrawalId,
    val symbol: Symbol,
    val amount: BigIntegerJson,
    val status: WithdrawalStatus,
    val error: String?,
    val createdAt: Instant,
) {
    companion object {
        fun fromEntity(entity: WithdrawalEntity): Withdrawal {
            return Withdrawal(
                entity.id.value,
                Symbol(entity.symbol.name),
                entity.amount,
                entity.status,
                entity.error,
                entity.createdAt,
            )
        }
    }
}

@Serializable
data class WithdrawalApiResponse(
    val withdrawal: Withdrawal,
)

@Serializable
data class ListWithdrawalsApiResponse(
    val withdrawals: List<Withdrawal>,
)
