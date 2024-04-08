package co.chainring.core.evm

import co.chainring.apps.api.model.BigIntegerJson
import co.chainring.core.model.Address
import co.chainring.core.model.EvmSignature
import org.bouncycastle.util.encoders.Hex
import org.web3j.abi.DefaultFunctionEncoder
import org.web3j.abi.datatypes.DynamicStruct
import org.web3j.crypto.StructuredData

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

        override fun getModel(): List<StructuredData.Entry> {
            val model = mutableListOf<StructuredData.Entry>()
            model.add(StructuredData.Entry("sender", "address"))
            token?.let {
                model.add(StructuredData.Entry("token", "address"))
            }
            model.add(StructuredData.Entry("amount", "uint256"))
            model.add(StructuredData.Entry("nonce", "uint64"))
            return model
        }

        override fun getMessage(): Map<String, String> {
            val message = mutableMapOf<String, String>()
            message["sender"] = sender.value
            token?.let {
                message["token"] = token.value
            }
            message["amount"] = amount.toString()
            message["nonce"] = nonce.toString()
            return message
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

    abstract fun getModel(): List<StructuredData.Entry>
    abstract fun getTransactionType(): EIP712TransactionType
    abstract fun getMessage(): Map<String, String>
    abstract fun getTxData(): ByteArray
}
