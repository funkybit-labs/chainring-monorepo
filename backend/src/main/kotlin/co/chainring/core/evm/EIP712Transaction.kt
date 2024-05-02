package co.chainring.core.evm

import co.chainring.apps.api.model.BigIntegerJson
import co.chainring.core.model.Address
import co.chainring.core.model.EvmSignature
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
import java.math.BigInteger

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
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
sealed class EIP712Transaction {
    abstract val signature: EvmSignature

    @Serializable
    @SerialName("withdraw")
    data class WithdrawTx(
        val sender: Address,
        val token: Address?,
        val amount: BigIntegerJson,
        val nonce: Long,
        override val signature: EvmSignature,
    ) : EIP712Transaction() {

        override fun getTransactionType(): EIP712TransactionType {
            return EIP712TransactionType.Withdraw
        }

        override fun getModel(): List<StructuredData.Entry> = listOfNotNull(
            StructuredData.Entry("sender", "address"),
            token?.let {
                StructuredData.Entry("token", "address")
            },
            StructuredData.Entry("amount", "uint256"),
            StructuredData.Entry("nonce", "uint64"),
        )

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

        override fun getTxData(): ByteArray {
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

        override fun getTxData(): ByteArray {
            return ByteArray(0)
        }
    }

    @Serializable
    @SerialName("trade")
    data class Trade(
        val baseToken: Address,
        val quoteToken: Address,
        val amount: BigIntegerJson,
        val price: BigIntegerJson,
        val takerOrder: Order,
        val makerOrder: Order,
        val tradeId: TradeId,
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

        override fun getTxData(): ByteArray {
            return serializeTx(
                ExchangeTransactions.TransactionType.SettleTrade,
                ExchangeTransactions.SettleTrade(
                    baseToken.value,
                    quoteToken.value,
                    amount,
                    price,
                    BigInteger.ZERO,
                    BigInteger.ZERO,
                    ExchangeTransactions.OrderWithSignature(
                        ExchangeTransactions.Order(
                            takerOrder.sender.value,
                            takerOrder.amount,
                            takerOrder.price,
                            takerOrder.nonce,
                        ),
                        takerOrder.signature.toByteArray(),
                    ),
                    ExchangeTransactions.OrderWithSignature(
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

    abstract fun getModel(): List<StructuredData.Entry>
    abstract fun getTransactionType(): EIP712TransactionType
    abstract fun getMessage(): Map<String, String>
    abstract fun getTxData(): ByteArray
}
