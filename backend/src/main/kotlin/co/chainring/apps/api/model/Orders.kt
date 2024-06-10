package co.chainring.apps.api.model

import co.chainring.core.model.EvmSignature
import co.chainring.core.model.Percentage
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.ExecutionRole
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.OrderStatus
import kotlinx.datetime.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
sealed class CreateOrderApiRequest {
    abstract val nonce: String
    abstract val marketId: MarketId
    abstract val side: OrderSide
    abstract val amount: BigIntegerJson
    abstract val signature: EvmSignature
    abstract val verifyingChainId: ChainId

    @Serializable
    @SerialName("market")
    data class Market(
        override val nonce: String,
        override val marketId: MarketId,
        override val side: OrderSide,
        override val amount: BigIntegerJson,
        override val signature: EvmSignature,
        override val verifyingChainId: ChainId,
        val percentage: Percentage? = null,
    ) : CreateOrderApiRequest()

    @Serializable
    @SerialName("limit")
    data class Limit(
        override val nonce: String,
        override val marketId: MarketId,
        override val side: OrderSide,
        override val amount: BigIntegerJson,
        val price: BigDecimalJson,
        override val signature: EvmSignature,
        override val verifyingChainId: ChainId,
    ) : CreateOrderApiRequest()
}

@Serializable
data class CreateOrderApiResponse(
    val orderId: OrderId,
    val requestStatus: RequestStatus,
    val error: ApiError?,
    val order: CreateOrderApiRequest,
)

@Serializable
data class UpdateOrderApiRequest(
    val orderId: OrderId,
    val marketId: MarketId,
    val side: OrderSide,
    val amount: BigIntegerJson,
    val price: BigDecimalJson,
    val nonce: String,
    val signature: EvmSignature,
    val verifyingChainId: ChainId,
)

@Serializable
data class UpdateOrderApiResponse(
    val requestStatus: RequestStatus,
    val error: ApiError?,
    val order: UpdateOrderApiRequest,
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
    val signature: EvmSignature,
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
        val sequencerTimeNs: BigIntegerJson,
    )
}

@Serializable
data class BatchOrdersApiRequest(
    val marketId: MarketId,
    val createOrders: List<CreateOrderApiRequest>,
    val updateOrders: List<UpdateOrderApiRequest>,
    val cancelOrders: List<CancelOrderApiRequest>,
)

@Serializable
data class OrdersApiResponse(
    val orders: List<Order>,
)

@Serializable
data class BatchOrdersApiResponse(
    val createdOrders: List<CreateOrderApiResponse>,
    val updatedOrders: List<UpdateOrderApiResponse>,
    val canceledOrders: List<CancelOrderApiResponse>,
)
