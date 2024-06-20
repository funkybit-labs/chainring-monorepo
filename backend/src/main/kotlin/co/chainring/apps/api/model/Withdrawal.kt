package co.chainring.apps.api.model

import co.chainring.core.model.EvmSignature
import co.chainring.core.model.Symbol
import co.chainring.core.model.TxHash
import co.chainring.core.model.db.SymbolEntity
import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.model.db.WithdrawalId
import co.chainring.core.model.db.WithdrawalStatus
import co.chainring.core.utils.toFundamentalUnits
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import java.math.BigDecimal

@Serializable
data class CreateWithdrawalApiRequest(
    val symbol: Symbol,
    val amount: BigIntegerJson,
    val nonce: Long,
    val signature: EvmSignature,
) {
    constructor(symbol: SymbolEntity, amount: BigDecimal, nonce: Long, signature: EvmSignature) :
        this(Symbol(symbol.name), amount.toFundamentalUnits(symbol.decimals), nonce, signature)
}

@Serializable
data class Withdrawal(
    val id: WithdrawalId,
    val symbol: Symbol,
    val amount: BigIntegerJson,
    val status: WithdrawalStatus,
    val error: String?,
    val createdAt: Instant,
    val txHash: TxHash?,
) {
    companion object {
        fun fromEntity(entity: WithdrawalEntity): Withdrawal {
            return Withdrawal(
                id = entity.id.value,
                symbol = Symbol(entity.symbol.name),
                amount = entity.actualAmount ?: entity.amount,
                status = entity.status,
                error = entity.error,
                createdAt = entity.createdAt,
                txHash = entity.blockchainTransaction?.txHash?.let { TxHash(it.value) },
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
