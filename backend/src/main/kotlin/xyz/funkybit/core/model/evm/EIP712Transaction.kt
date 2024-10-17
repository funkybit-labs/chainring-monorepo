package xyz.funkybit.core.model.evm

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import org.web3j.abi.DefaultFunctionEncoder
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.DynamicStruct
import org.web3j.abi.datatypes.StaticStruct
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.abi.datatypes.generated.Uint64
import org.web3j.crypto.StructuredData
import xyz.funkybit.apps.api.model.BigIntegerJson
import xyz.funkybit.apps.api.model.OrderAmount
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.EvmSignature
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.utils.toHexBytes
import java.math.BigInteger

enum class EIP712TransactionType {
    Withdraw,
    Order,
    PercentageOrder,
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

        private object Abi {
            enum class TransactionType {
                Withdraw,
                WithdrawAll,
            }

            class Withdraw(sequence: Long, sender: String, token: String, amount: BigInteger, nonce: BigInteger, fee: BigInteger) : StaticStruct(
                Uint256(sequence),
                org.web3j.abi.datatypes.Address(160, sender),
                org.web3j.abi.datatypes.Address(160, token),
                Uint256(amount),
                Uint64(nonce),
                Uint256(fee),
            )

            class WithdrawWithSignature(tx: Withdraw, signature: ByteArray) : DynamicStruct(
                tx,
                DynamicBytes(signature),
            )
        }

        override fun getTxData(sequence: Long): ByteArray {
            val txType = if (withdrawAll) Abi.TransactionType.WithdrawAll else Abi.TransactionType.Withdraw
            val struct = Abi.WithdrawWithSignature(Abi.Withdraw(sequence, sender.toString(), token.address.toString(), amount, nonce.toBigInteger(), fee), signature.toByteArray())
            return listOf(txType.ordinal.toByte()).toByteArray() + DefaultFunctionEncoder().encodeParameters(listOf(struct)).toHexBytes()
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
            return when (amount) {
                is OrderAmount.Fixed -> EIP712TransactionType.Order
                is OrderAmount.Percent -> EIP712TransactionType.PercentageOrder
            }
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
