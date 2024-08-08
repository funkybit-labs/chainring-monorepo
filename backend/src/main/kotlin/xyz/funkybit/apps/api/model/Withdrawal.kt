package xyz.funkybit.apps.api.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import xyz.funkybit.core.model.EvmSignature
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.WithdrawalEntity
import xyz.funkybit.core.model.db.WithdrawalId
import xyz.funkybit.core.model.db.WithdrawalStatus
import xyz.funkybit.core.utils.toFundamentalUnits
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
    val fee: BigIntegerJson,
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
                fee = entity.fee,
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
