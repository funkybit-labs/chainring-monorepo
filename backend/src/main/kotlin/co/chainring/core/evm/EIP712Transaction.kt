package co.chainring.core.evm

import co.chainring.apps.api.model.BigIntegerJson
import co.chainring.core.model.Address
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.TradeId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import org.bouncycastle.util.encoders.Hex
import org.web3j.abi.DefaultFunctionEncoder
import org.web3j.abi.datatypes.DynamicStruct
import org.web3j.crypto.StructuredData
import org.web3j.crypto.transaction.type.TransactionType

fun serializeTx(txType: ExchangeTransactions.TransactionType, struct: DynamicStruct): ByteArray {
    return listOf(txType.ordinal.toByte()).toByteArray() + Hex.decode(DefaultFunctionEncoder().encodeParameters(listOf(struct)))
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
        override val signature: EvmSignature,
    ) : EIP712Transaction() {

        override fun getTransactionType(): EIP712TransactionType {
            return EIP712TransactionType.Withdraw
        }

        override fun getModel(): List<StructuredData.Entry> = listOfNotNull(
            StructuredData.Entry("sender", "address"),
            if (token.address != Address.zero) {
                StructuredData.Entry("token", "address")
            } else {
                null
            },
            StructuredData.Entry("amount", "uint256"),
            StructuredData.Entry("nonce", "uint64"),
        )

        override fun getMessage(): Map<String, String> {
            val message = mutableMapOf<String, String>()
            message["sender"] = sender.value
            if (token.address != Address.zero) {
                message["token"] = token.address.value
            }
            message["amount"] = amount.toString()
            message["nonce"] = nonce.toString()
            return message
        }

        override fun getTxData(sequence: Long): ByteArray {
            return if (token.address != Address.zero) {
                serializeTx(
                    ExchangeTransactions.TransactionType.Withdraw,
                    ExchangeTransactions.WithdrawWithSignature(ExchangeTransactions.Withdraw(sequence, sender.value, token.address.value, amount, nonce.toBigInteger()), signature.toByteArray()),
                )
            } else {
                serializeTx(
                    ExchangeTransactions.TransactionType.WithdrawNative,
                    ExchangeTransactions.WithdrawNativeWithSignature(ExchangeTransactions.WithdrawNative(sequence, sender.value, amount, nonce.toBigInteger()), signature.toByteArray()),
                )
            }
        }
    }

    @Serializable
    @SerialName("order")
    data class Order(
        val sender: Address,
        val baseToken: Address,
        val quoteToken: Address,
        val amount: BigIntegerJson,
        val price: BigIntegerJson,
        val nonce: BigIntegerJson,
        override val signature: EvmSignature,
    ) : EIP712Transaction() {

        override fun getTransactionType(): EIP712TransactionType {
            return EIP712TransactionType.Order
        }

        override fun getModel(): List<StructuredData.Entry> = listOf(
            StructuredData.Entry("sender", "address"),
            StructuredData.Entry("baseToken", "address"),
            StructuredData.Entry("quoteToken", "address"),
            StructuredData.Entry("amount", "int256"),
            StructuredData.Entry("price", "uint256"),
            StructuredData.Entry("nonce", "int256"),
        )

        override fun getMessage(): Map<String, String> {
            return mapOf(
                "sender" to sender.value,
                "baseToken" to baseToken.value,
                "quoteToken" to quoteToken.value,
                "amount" to amount.toString(),
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

    @Serializable
    @SerialName("trade")
    data class Trade(
        val baseToken: TokenAddressAndChain,
        val quoteToken: TokenAddressAndChain,
        val amount: BigIntegerJson,
        val price: BigIntegerJson,
        val takerOrder: Order,
        val makerOrder: Order,
        val tradeId: TradeId,
        val takerFee: BigIntegerJson,
        val makerFee: BigIntegerJson,
        override val signature: EvmSignature = EvmSignature.emptySignature(),
    ) : EIP712Transaction() {

        override fun getTransactionType(): EIP712TransactionType {
            return EIP712TransactionType.Trade
        }

        override fun getModel(): List<StructuredData.Entry> {
            return emptyList()
        }

        override fun getMessage(): Map<String, String> {
            return emptyMap()
        }

        override fun getTxData(sequence: Long): ByteArray {
            return serializeTx(
                ExchangeTransactions.TransactionType.SettleTrade,
                ExchangeTransactions.SettleTrade(
                    sequence = sequence,
                    baseToken = baseToken.address.value,
                    quoteToken = quoteToken.address.value,
                    amount = amount,
                    price = price,
                    takerFee = takerFee,
                    makerFee = makerFee,
                    takerOrder = ExchangeTransactions.OrderWithSignature(
                        ExchangeTransactions.Order(
                            takerOrder.sender.value,
                            takerOrder.amount,
                            takerOrder.price,
                            takerOrder.nonce,
                        ),
                        takerOrder.signature.toByteArray(),
                    ),
                    makerOrder = ExchangeTransactions.OrderWithSignature(
                        ExchangeTransactions.Order(
                            makerOrder.sender.value,
                            makerOrder.amount,
                            makerOrder.price,
                            makerOrder.nonce,
                        ),
                        makerOrder.signature.toByteArray(),
                    ),
                ),
            )
        }
    }

    fun getChainId(): ChainId {
        return when (this) {
            is Trade -> this.baseToken.chainId
            is WithdrawTx -> this.token.chainId
            else -> throw Exception("Not valid for this tx type")
        }
    }

    abstract fun getModel(): List<StructuredData.Entry>
    abstract fun getTransactionType(): EIP712TransactionType
    abstract fun getMessage(): Map<String, String>
    abstract fun getTxData(sequence: Long): ByteArray
}
