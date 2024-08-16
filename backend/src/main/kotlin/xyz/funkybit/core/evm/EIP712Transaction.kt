package xyz.funkybit.core.evm

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import org.web3j.abi.DefaultFunctionEncoder
import org.web3j.abi.datatypes.DynamicStruct
import org.web3j.crypto.StructuredData
import xyz.funkybit.apps.api.model.BigIntegerJson
import xyz.funkybit.apps.api.model.OrderAmount
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.EvmSignature
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.utils.toHexBytes
import java.math.BigInteger

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
        val fee: BigIntegerJson = BigInteger.ZERO,
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
            message["sender"] = sender.toString()
            message["token"] = token.address.toString()
            message["amount"] = if (withdrawAll) "0" else amount.toString()
            message["nonce"] = nonce.toString()
            return message
        }

        override fun getTxData(sequence: Long): ByteArray {
            return serializeTx(
                if (withdrawAll) ExchangeTransactions.TransactionType.WithdrawAll else ExchangeTransactions.TransactionType.Withdraw,
                ExchangeTransactions.WithdrawWithSignature(ExchangeTransactions.Withdraw(sequence, sender.toString(), token.address.toString(), amount, nonce.toBigInteger(), fee), signature.toByteArray()),
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
                "sender" to sender.toString(),
                "baseChainId" to baseChainId.value.toString(),
                "baseToken" to baseToken.toString(),
                "quoteChainId" to quoteChainId.value.toString(),
                "quoteToken" to quoteToken.toString(),
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
                "sender" to sender.toString(),
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
