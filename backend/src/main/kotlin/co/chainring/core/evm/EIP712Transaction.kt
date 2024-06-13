package co.chainring.core.evm

import co.chainring.apps.api.model.BigIntegerJson
import co.chainring.apps.api.model.OrderAmount
import co.chainring.core.model.Address
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.MarketId
import co.chainring.core.utils.toHexBytes
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import org.web3j.abi.DefaultFunctionEncoder
import org.web3j.abi.datatypes.DynamicStruct
import org.web3j.crypto.StructuredData

fun serializeTx(txType: ExchangeTransactions.TransactionType, struct: DynamicStruct): ByteArray {
    return listOf(txType.ordinal.toByte()).toByteArray() + DefaultFunctionEncoder().encodeParameters(listOf(struct)).toHexBytes()
}

enum class EIP712TransactionType {
    Withdraw,
    Order,
    Trade,
    CancelOrder,
}

@Serializable
data class TokenAddressAndChain(
    val address: Address,
    val chainId: ChainId,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
sealed class EIP712Transaction {
    abstract val signature: EvmSignature

    @Serializable
    @SerialName("withdraw")
    data class WithdrawTx(
        val sender: Address,
        val token: TokenAddressAndChain,
        val amount: BigIntegerJson,
        val nonce: Long,
        val withdrawAll: Boolean,
        override val signature: EvmSignature,
    ) : EIP712Transaction() {

        override fun getTransactionType(): EIP712TransactionType {
            return EIP712TransactionType.Withdraw
        }

        override fun getModel(): List<StructuredData.Entry> = listOf(
            StructuredData.Entry("sender", "address"),
            StructuredData.Entry("token", "address"),
            StructuredData.Entry("amount", "uint256"),
            StructuredData.Entry("nonce", "uint64"),
        )

        override fun getMessage(): Map<String, String> {
            val message = mutableMapOf<String, String>()
            message["sender"] = sender.value
            message["token"] = token.address.value
            message["amount"] = if (withdrawAll) "0" else amount.toString()
            message["nonce"] = nonce.toString()
            return message
        }

        override fun getTxData(sequence: Long): ByteArray {
            return serializeTx(
                if (withdrawAll) ExchangeTransactions.TransactionType.WithdrawAll else ExchangeTransactions.TransactionType.Withdraw,
                ExchangeTransactions.WithdrawWithSignature(ExchangeTransactions.Withdraw(sequence, sender.value, token.address.value, amount, nonce.toBigInteger()), signature.toByteArray()),
            )
        }
    }

    @Serializable
    @SerialName("order")
    data class Order(
        val sender: Address,
        val baseChainId: ChainId,
        val baseToken: Address,
        val quoteChainId: ChainId,
        val quoteToken: Address,
        val amount: OrderAmount,
        val price: BigIntegerJson,
        val nonce: BigIntegerJson,
        override val signature: EvmSignature,
    ) : EIP712Transaction() {

        override fun getTransactionType(): EIP712TransactionType {
            return EIP712TransactionType.Order
        }

        override fun getModel(): List<StructuredData.Entry> = listOf(
            StructuredData.Entry("sender", "address"),
            StructuredData.Entry("baseChainId", "uint256"),
            StructuredData.Entry("baseToken", "address"),
            StructuredData.Entry("quoteChainId", "uint256"),
            StructuredData.Entry("quoteToken", "address"),
            when (amount) {
                is OrderAmount.Fixed -> StructuredData.Entry("amount", "int256")
                is OrderAmount.Percent -> StructuredData.Entry("percentage", "int256")
            },
            StructuredData.Entry("price", "uint256"),
            StructuredData.Entry("nonce", "int256"),
        )

        override fun getMessage(): Map<String, String> {
            return mapOf(
                "sender" to sender.value,
                "baseChainId" to baseChainId.value.toString(),
                "baseToken" to baseToken.value,
                "quoteChainId" to quoteChainId.value.toString(),
                "quoteToken" to quoteToken.value,
                when (amount) {
                    is OrderAmount.Fixed -> "amount" to amount.value.toString()
                    is OrderAmount.Percent -> "percentage" to amount.value.value.toString()
                },
                "price" to price.toString(),
                "nonce" to nonce.toString(),
            )
        }

        override fun getTxData(sequence: Long): ByteArray {
            return ByteArray(0)
        }
    }

    @Serializable
    @SerialName("cancelOrder")
    data class CancelOrder(
        val sender: Address,
        val marketId: MarketId,
        val amount: BigIntegerJson,
        val nonce: BigIntegerJson,
        override val signature: EvmSignature,
    ) : EIP712Transaction() {

        override fun getTransactionType(): EIP712TransactionType {
            return EIP712TransactionType.CancelOrder
        }

        override fun getModel(): List<StructuredData.Entry> = listOf(
            StructuredData.Entry("sender", "address"),
            StructuredData.Entry("marketId", "string"),
            StructuredData.Entry("amount", "int256"),
            StructuredData.Entry("nonce", "int256"),
        )

        override fun getMessage(): Map<String, String> {
            return mapOf(
                "sender" to sender.value,
                "marketId" to marketId.value,
                "amount" to amount.toString(),
                "nonce" to nonce.toString(),
            )
        }

        override fun getTxData(sequence: Long): ByteArray {
            return ByteArray(0)
        }
    }

    abstract fun getModel(): List<StructuredData.Entry>
    abstract fun getTransactionType(): EIP712TransactionType
    abstract fun getMessage(): Map<String, String>
    abstract fun getTxData(sequence: Long): ByteArray
}
