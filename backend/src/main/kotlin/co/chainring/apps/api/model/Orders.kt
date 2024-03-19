package co.chainring.apps.api.model

import co.chainring.core.model.Market
import co.chainring.core.model.OrderId
import co.chainring.core.model.Symbol
import kotlinx.datetime.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

object Order {
    @Serializable
    enum class Type {
        Market,
        Limit,
    }

    @Serializable
    enum class Side {
        Buy,
        Sell,
    }

    @Serializable
    enum class Status {
        Open,
        Partial,
        Filled,
        Cancelled, // TODO reason (user initiated, insufficient liquidity, withdrawal, cancelled immediately)
        Expired,
    }

    @Serializable
    sealed class TimeInForce {
        @Serializable
        @SerialName("GoodTillCancelled")
        data object GoodTillCancelled : TimeInForce()

        @Serializable
        @SerialName("GoodTillTime")
        data class GoodTillTime(val timestamp: Long) : TimeInForce()

        @Serializable
        @SerialName("ImmediateOrCancel")
        data object ImmediateOrCancel : TimeInForce()

        // FillOrKill?
    }

    @Serializable
    data class Execution(
        val fee: BigDecimalJson,
        val feeSymbol: Symbol,
        val amountExecuted: BigDecimalJson,
    )

    @Serializable
    data class Timing(
        val createdAt: Instant,
        val updatedAt: Instant? = null,
        val filledAt: Instant? = null,
        val closedAt: Instant? = null,
        val expiredAt: Instant? = null,
    )
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("action")
sealed class OrderApiRequest {
    @Serializable
    @JsonClassDiscriminator("type")
    @SerialName("create")
    sealed class Create {
        abstract val nonce: String
        abstract val market: co.chainring.core.model.Market
        abstract val side: Order.Side
        abstract val timeInForce: Order.TimeInForce
        abstract val amount: BigDecimalJson

        @Serializable
        @SerialName("market")
        data class Market(
            override val nonce: String,
            override val market: co.chainring.core.model.Market,
            override val side: Order.Side,
            override val timeInForce: Order.TimeInForce,
            override val amount: BigDecimalJson,
        ) : Create()

        @Serializable
        @SerialName("limit")
        data class Limit(
            override val nonce: String,
            override val market: co.chainring.core.model.Market,
            override val side: Order.Side,
            override val timeInForce: Order.TimeInForce,
            override val amount: BigDecimalJson,
            val price: BigDecimalJson,
        ) : Create()
    }

    @Serializable
    @JsonClassDiscriminator("type")
    @SerialName("update")
    sealed class Update {
        abstract val orderId: OrderId
        abstract val timeInForce: Order.TimeInForce?
        abstract val amount: BigDecimalJson?

        @Serializable
        @SerialName("market")
        data class Market(
            override val orderId: OrderId,
            override val timeInForce: Order.TimeInForce? = null,
            override val amount: BigDecimalJson? = null,
        ) : Update()

        @Serializable
        @SerialName("limit")
        data class Limit(
            override val orderId: OrderId,
            override val timeInForce: Order.TimeInForce? = null,
            override val amount: BigDecimalJson? = null,
            val price: BigDecimalJson?,
        ) : Update()
    }

    @Serializable
    @SerialName("delete")
    data class Delete(
        val orderId: OrderId,
    )
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
sealed class OrderApiResponse {
    abstract val orderId: OrderId
    abstract val status: Order.Status
    abstract val market: Market
    abstract val side: Order.Side
    abstract val amount: BigDecimalJson
    abstract val timeInForce: Order.TimeInForce
    abstract val execution: Order.Execution?
    abstract val timing: Order.Timing

    @Serializable
    @SerialName("market")
    data class MarketOrder(
        override val orderId: OrderId,
        override val status: Order.Status,
        override val market: Market,
        override val side: Order.Side,
        override val amount: BigDecimalJson,
        override val timeInForce: Order.TimeInForce,
        override val execution: Order.Execution? = null,
        override val timing: Order.Timing,
    ) : OrderApiResponse()

    @Serializable
    @SerialName("limit")
    data class Limit(
        override val orderId: OrderId,
        override val status: Order.Status,
        override val market: Market,
        override val side: Order.Side,
        override val amount: BigDecimalJson,
        val price: BigDecimalJson,
        override val timeInForce: Order.TimeInForce,
        override val execution: Order.Execution? = null,
        override val timing: Order.Timing,
    ) : OrderApiResponse()
}

@Serializable
data class OrdersApiRequest(
    val orders: List<OrderApiRequest>,
)

@Serializable
data class OrdersApiResponse(
    val orders: List<OrderApiResponse>,
)
