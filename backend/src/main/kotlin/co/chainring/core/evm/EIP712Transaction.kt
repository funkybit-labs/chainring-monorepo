package co.chainring.core.evm

import co.chainring.apps.api.model.BigIntegerJson
import co.chainring.core.model.Address
import co.chainring.core.model.EvmSignature
import org.bouncycastle.util.encoders.Hex
import org.web3j.abi.DefaultFunctionEncoder
import org.web3j.abi.datatypes.DynamicStruct

fun serializeTx(txType: ExchangeTransactions.TransactionType, struct: DynamicStruct): ByteArray {
    return listOf(txType.ordinal.toByte()).toByteArray() + Hex.decode(DefaultFunctionEncoder().encodeParameters(listOf(struct)))
}

enum class EIP712TransactionType {
    Withdraw,
}

sealed class EIP712Transaction {
    abstract val sender: Address
    abstract val nonce: Long

    data class WithdrawTx(
        override val sender: Address,
        val token: Address?,
        val amount: BigIntegerJson,
        override val nonce: Long,
        val signature: EvmSignature,
    ) : EIP712Transaction() {

        override fun getTransactionType(): EIP712TransactionType {
            return EIP712TransactionType.Withdraw
        }

        override fun getModel(): String {
            return """
            [
                {"name": "sender", "type": "address"},
                ${token?.let { "{\"name\": \"token\", \"type\": \"address\"}," } ?: ""}
                {"name": "amount", "type": "uint256"},
                {"name": "nonce", "type": "uint64"}
            ]
            """.trimIndent()
        }

        override fun getMessage(): String {
            return """
            {
                "sender": "${sender.value}",
                ${token?.let { "\"token\": \"${it.value}\"," } ?: ""}
                "amount": $amount,
                "nonce": $nonce
            }
            """.trimIndent()
        }

        override fun getTxData(): ByteArray {
            return if (token != null) {
                serializeTx(
                    ExchangeTransactions.TransactionType.Withdraw,
                    ExchangeTransactions.WithdrawWithSignature(ExchangeTransactions.Withdraw(sender.value, token.value, amount, nonce.toBigInteger()), signature.toByteArray()),
                )
            } else {
                serializeTx(
                    ExchangeTransactions.TransactionType.WithdrawNative,
                    ExchangeTransactions.WithdrawNativeWithSignature(ExchangeTransactions.WithdrawNative(sender.value, amount, nonce.toBigInteger()), signature.toByteArray()),
                )
            }
        }
    }

    abstract fun getModel(): String
    abstract fun getTransactionType(): EIP712TransactionType
    abstract fun getMessage(): String
    abstract fun getTxData(): ByteArray
}
