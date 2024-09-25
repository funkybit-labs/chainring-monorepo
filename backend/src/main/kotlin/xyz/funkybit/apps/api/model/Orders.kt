package xyz.funkybit.apps.api.model

import kotlinx.datetime.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import xyz.funkybit.core.model.Percentage
import xyz.funkybit.core.model.Signature
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.ClientOrderId
import xyz.funkybit.core.model.db.ExecutionRole
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.OrderId
import xyz.funkybit.core.model.db.OrderSide
import xyz.funkybit.core.model.db.OrderStatus
import xyz.funkybit.core.model.db.TradeId
import java.math.BigInteger

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
sealed class OrderAmount {
    @Serializable
    @SerialName("fixed")
    data class Fixed(
        val value: BigIntegerJson,
    ) : OrderAmount()

    @Serializable
    @SerialName("percent")
    data class Percent(
        val value: Percentage,
    ) : OrderAmount()

    fun negate(): OrderAmount {
        return when (this) {
            is Fixed -> Fixed(this.value.negate())
            else -> this
        }
    }

    fun fixedAmount(): BigIntegerJson {
        return when (this) {
            is Fixed -> this.value
            else -> BigInteger.ZERO
        }
    }

    fun percentage(): Int? {
        return when (this) {
            is Percent -> this.value.value
            else -> null
        }
    }
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
sealed class CreateOrderApiRequest {
    abstract val nonce: String
    abstract val marketId: MarketId
    abstract val side: OrderSide
    abstract val amount: OrderAmount
    abstract val signature: Signature
    abstract val verifyingChainId: ChainId
    abstract val clientOrderId: ClientOrderId?

    @Serializable
    @SerialName("market")
    data class Market(
        override val nonce: String,
        override val marketId: MarketId,
        override val side: OrderSide,
        override val amount: OrderAmount,
        override val signature: Signature,
        override val verifyingChainId: ChainId,
        override val clientOrderId: ClientOrderId? = null,
    ) : CreateOrderApiRequest()

    @Serializable
    @SerialName("backToBackMarket")
    data class BackToBackMarket(
        override val nonce: String,
        override val marketId: MarketId,
        val secondMarketId: MarketId,
        override val side: OrderSide,
        override val amount: OrderAmount,
        override val signature: Signature,
        override val verifyingChainId: ChainId,
        override val clientOrderId: ClientOrderId? = null,
    ) : CreateOrderApiRequest()

    @Serializable
    @SerialName("limit")
    data class Limit(
        override val nonce: String,
        override val marketId: MarketId,
        override val side: OrderSide,
        override val amount: OrderAmount,
        val price: BigDecimalJson,
        override val signature: Signature,
        override val verifyingChainId: ChainId,
        override val clientOrderId: ClientOrderId? = null,
    ) : CreateOrderApiRequest()
}

@Serializable
data class CreateOrderApiResponse(
    val orderId: OrderId,
    val clientOrderId: ClientOrderId?,
    val requestStatus: RequestStatus,
    val error: ApiError?,
    val order: CreateOrderApiRequest,
)

@Serializable
enum class RequestStatus {
    Accepted,
    Rejected,
}

@Serializable
data class CancelOrderApiRequest(
    val orderId: OrderId,
    val marketId: MarketId,
    val side: OrderSide,
    val amount: BigIntegerJson,
    val nonce: String,
    val signature: Signature,
    val verifyingChainId: ChainId,
)

@Serializable
data class CancelOrderApiResponse(
    val orderId: OrderId,
    val requestStatus: RequestStatus,
    val error: ApiError?,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
sealed class Order {
    abstract val id: OrderId
    abstract val clientOrderId: ClientOrderId?
    abstract val status: OrderStatus
    abstract val marketId: MarketId
    abstract val side: OrderSide
    abstract val amount: BigIntegerJson
    abstract val executions: List<Execution>
    abstract val timing: Timing

    @Serializable
    @SerialName("market")
    data class Market(
        override val id: OrderId,
        override val clientOrderId: ClientOrderId?,
        override val status: OrderStatus,
        override val marketId: MarketId,
        override val side: OrderSide,
        override val amount: BigIntegerJson,
        override val executions: List<Execution>,
        override val timing: Timing,
    ) : Order()

    @Serializable
    @SerialName("backToBackMarket")
    data class BackToBackMarket(
        override val id: OrderId,
        override val clientOrderId: ClientOrderId?,
        override val status: OrderStatus,
        override val marketId: MarketId,
        val secondMarketId: MarketId,
        override val side: OrderSide,
        override val amount: BigIntegerJson,
        override val executions: List<Execution>,
        override val timing: Timing,
    ) : Order()

    @Serializable
    @SerialName("limit")
    data class Limit(
        override val id: OrderId,
        override val clientOrderId: ClientOrderId?,
        override val status: OrderStatus,
        override val marketId: MarketId,
        override val side: OrderSide,
        override val amount: BigIntegerJson,
        val originalAmount: BigIntegerJson,
        val autoReduced: Boolean,
        val price: BigDecimalJson,
        override val executions: List<Execution>,
        override val timing: Timing,
    ) : Order()

    @Serializable
    data class Execution(
        val tradeId: TradeId,
        val timestamp: Instant,
        val amount: BigIntegerJson,
        val price: BigDecimalJson,
        val role: ExecutionRole,
        val feeAmount: BigIntegerJson,
        val feeSymbol: Symbol,
        val marketId: MarketId,
    )

    @Serializable
    data class Timing(
        val createdAt: Instant,
        val updatedAt: Instant?,
        val closedAt: Instant?,
        val sequencerTimeNs: BigIntegerJson,
    )
}

@Serializable
data class BatchOrdersApiRequest(
    val marketId: MarketId,
    val createOrders: List<CreateOrderApiRequest>,
    val cancelOrders: List<CancelOrderApiRequest>,
)

@Serializable
data class OrdersApiResponse(
    val orders: List<Order>,
)

@Serializable
data class BatchOrdersApiResponse(
    val createdOrders: List<CreateOrderApiResponse>,
    val canceledOrders: List<CancelOrderApiResponse>,
)
