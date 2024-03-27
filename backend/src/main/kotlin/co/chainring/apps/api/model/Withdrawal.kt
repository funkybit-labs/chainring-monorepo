package co.chainring.apps.api.model

import co.chainring.core.evm.EIP712Transaction
import co.chainring.core.model.Address
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.model.db.WithdrawalId
import co.chainring.core.model.db.WithdrawalStatus
import kotlinx.serialization.Serializable

@Serializable
data class WithdrawTx(
    val sender: Address,
    val token: Address?,
    val amount: BigIntegerJson,
    val nonce: Long,
)

@Serializable
data class CreateWithdrawalApiRequest(
    val tx: WithdrawTx,
    val signature: EvmSignature,
) {

    companion object {
        fun fromEntity(entity: WithdrawalEntity): CreateWithdrawalApiRequest {
            return CreateWithdrawalApiRequest(
                WithdrawTx(
                    entity.walletAddress,
                    entity.symbol.contractAddress,
                    entity.amount,
                    entity.nonce,
                ),
                entity.signature,
            )
        }
    }
    fun toEip712Transaction() = EIP712Transaction.WithdrawTx(
        tx.sender,
        tx.token,
        tx.amount,
        tx.nonce,
        signature,
    )
}

@Serializable
data class Withdrawal(
    val id: WithdrawalId,
    val tx: WithdrawTx,
    val status: WithdrawalStatus,
    val error: String?,
) {
    companion object {
        fun fromEntity(entity: WithdrawalEntity): Withdrawal {
            return Withdrawal(
                entity.id.value,
                WithdrawTx(
                    entity.walletAddress,
                    entity.symbol.contractAddress,
                    entity.amount,
                    entity.nonce,
                ),
                entity.status,
                entity.error,
            )
        }
    }
}

@Serializable
data class WithdrawalApiResponse(
    val withdrawal: Withdrawal,
)
