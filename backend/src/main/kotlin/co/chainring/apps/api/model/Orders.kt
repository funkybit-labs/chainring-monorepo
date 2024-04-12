package co.chainring.apps.api.model

import co.chainring.core.evm.EIP712Transaction
import co.chainring.core.model.Address
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.ExecutionRole
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.OrderStatus
import co.chainring.core.model.db.OrderType
import co.chainring.core.utils.toFundamentalUnits
import co.chainring.core.utils.toHexBytes
import kotlinx.datetime.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import java.math.BigDecimal
import java.math.BigInteger

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
sealed class CreateOrderApiRequest {
    abstract val nonce: String
    abstract val marketId: MarketId
    abstract val side: OrderSide
    abstract val amount: BigIntegerJson
    abstract val signature: EvmSignature

    @Serializable
    @SerialName("market")
    data class Market(
        override val nonce: String,
        override val marketId: MarketId,
        override val side: OrderSide,
        override val amount: BigIntegerJson,
        override val signature: EvmSignature,
    ) : CreateOrderApiRequest() {
        fun toEip712Transaction(sender: Address, baseToken: Address?, quoteToken: Address?) = EIP712Transaction.Order(
            sender,
            baseToken ?: Address.zero,
            quoteToken ?: Address.zero,
            if (side == OrderSide.Buy) this.amount else this.amount.negate(),
            BigInteger.ZERO,
            BigInteger(1, nonce.toHexBytes()),
            this.signature,
        )
    }

    @Serializable
    @SerialName("limit")
    data class Limit(
        override val nonce: String,
        override val marketId: MarketId,
        override val side: OrderSide,
        override val amount: BigIntegerJson,
        val price: BigDecimalJson,
        override val signature: EvmSignature,
    ) : CreateOrderApiRequest() {
        fun toEip712Transaction(sender: Address, baseToken: Address?, quoteToken: Address?, quoteDecimals: Int) = EIP712Transaction.Order(
            sender,
            baseToken ?: Address.zero,
            quoteToken ?: Address.zero,
            if (side == OrderSide.Buy) this.amount else this.amount.negate(),
            this.price.toFundamentalUnits(quoteDecimals),
            BigInteger(1, nonce.toHexBytes()),
            this.signature,
        )
    }
    fun getResolvedPrice(): BigDecimal? {
        return when (this) {
            is Limit -> price
            is Market -> null
        }
    }

    fun getOrderType(): OrderType {
        return when (this) {
            is Market -> OrderType.Market
            is Limit -> OrderType.Limit
        }
    }
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
sealed class UpdateOrderApiRequest {
    abstract val orderId: OrderId
    abstract val amount: BigIntegerJson

    @Serializable
    @SerialName("market")
    data class Market(
        override val orderId: OrderId,
        override val amount: BigIntegerJson,
    ) : UpdateOrderApiRequest()

    @Serializable
    @SerialName("limit")
    data class Limit(
        override val orderId: OrderId,
        override val amount: BigIntegerJson,
        val price: BigDecimalJson,
    ) : UpdateOrderApiRequest()

    fun getResolvedPrice(): BigDecimal? {
        return when (this) {
            is Limit -> price
            is Market -> null
        }
    }
}

@Serializable
data class CancelUpdateOrderApiRequest(
    val orderId: OrderId,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
sealed class Order {
    abstract val id: OrderId
    abstract val status: OrderStatus
    abstract val marketId: MarketId
    abstract val side: OrderSide
    abstract val amount: BigIntegerJson
    abstract val originalAmount: BigIntegerJson
    abstract val executions: List<Execution>
    abstract val timing: Timing

    @Serializable
    @SerialName("market")
    data class Market(
        override val id: OrderId,
        override val status: OrderStatus,
        override val marketId: MarketId,
        override val side: OrderSide,
        override val amount: BigIntegerJson,
        override val originalAmount: BigIntegerJson,
        override val executions: List<Execution>,
        override val timing: Timing,
    ) : Order()

    @Serializable
    @SerialName("limit")
    data class Limit(
        override val id: OrderId,
        override val status: OrderStatus,
        override val marketId: MarketId,
        override val side: OrderSide,
        override val amount: BigIntegerJson,
        override val originalAmount: BigIntegerJson,
        val price: BigDecimalJson,
        override val executions: List<Execution>,
        override val timing: Timing,
    ) : Order()

    @Serializable
    data class Execution(
        val timestamp: Instant,
        val amount: BigIntegerJson,
        val price: BigDecimalJson,
        val role: ExecutionRole,
        val feeAmount: BigIntegerJson,
        val feeSymbol: Symbol,
    )

    @Serializable
    data class Timing(
        val createdAt: Instant,
        val updatedAt: Instant?,
        val closedAt: Instant?,
    )
}

@Serializable
data class BatchOrdersApiRequest(
    val marketId: MarketId,
    val createOrders: List<CreateOrderApiRequest>,
    val updateOrders: List<UpdateOrderApiRequest>,
    val cancelOrders: List<CancelUpdateOrderApiRequest>,
)

@Serializable
data class OrdersApiResponse(
    val orders: List<Order>,
)
