package co.chainring.core.model

import co.chainring.core.model.db.ExecutionId
import co.chainring.core.model.db.OrderId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class BroadcasterNotification {

    @Serializable
    @SerialName("TradesCreated")
    data class TradesCreated(
        val ids: List<ExecutionId>,
    ) : BroadcasterNotification()

    @Serializable
    @SerialName("TradesUpdated")
    data class TradesUpdated(
        val ids: List<ExecutionId>,
    ) : BroadcasterNotification()

    @Serializable
    @SerialName("Balances")
    data object Balances : BroadcasterNotification()

    @Serializable
    @SerialName("OrdersCreated")
    data class OrdersCreated(
        val ids: List<OrderId>,
    ) : BroadcasterNotification()

    @Serializable
    @SerialName("OrdersUpdated")
    data class OrdersUpdated(
        val ids: List<OrderId>,
    ) : BroadcasterNotification()

    @Serializable
    @SerialName("Orders")
    data object Orders : BroadcasterNotification()
}

@Serializable
data class PrincipalNotifications(
    val principal: Address,
    val notifications: List<BroadcasterNotification>,
)
