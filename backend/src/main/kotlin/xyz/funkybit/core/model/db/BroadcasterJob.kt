package xyz.funkybit.core.model.db

import de.fxlae.typeid.TypeId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import org.http4k.format.KotlinxSerialization
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.TransactionManager
import xyz.funkybit.apps.api.model.websocket.Balances
import xyz.funkybit.apps.api.model.websocket.Limits
import xyz.funkybit.apps.api.model.websocket.Prices
import xyz.funkybit.apps.api.model.websocket.Publishable
import xyz.funkybit.core.db.notifyDbListener
import java.math.BigDecimal

private val logger = KotlinLogging.logger {}

@Serializable
data class BroadcasterNotification(
    val message: Publishable,
    val recipient: UserId?,
) {
    companion object {
        fun pricesForMarketPeriods(marketId: MarketId, duration: OHLCDuration, ohlc: List<OHLCEntity>, full: Boolean, dailyChange: BigDecimal): BroadcasterNotification =
            BroadcasterNotification(Prices(marketId, duration, ohlc.map { it.toWSResponse() }, full, dailyChange.stripTrailingZeros()), null)

        fun limits(userId: UserId): BroadcasterNotification =
            BroadcasterNotification(Limits(LimitEntity.forUserId(userId)), recipient = userId)

        fun walletBalances(userId: UserId): BroadcasterNotification =
            BroadcasterNotification(
                Balances(BalanceEntity.balancesAsApiResponse(userId).balances),
                recipient = userId,
            )
    }
}

fun publishBroadcasterNotifications(notifications: List<BroadcasterNotification>) {
    if (notifications.isNotEmpty()) {
        logger.debug { "Scheduling broadcaster notifications: $notifications" }
        TransactionManager.current().notifyDbListener(
            "broadcaster_ctl",
            BroadcasterJobEntity.create(BroadcasterJobId.generate(), notifications, Clock.System.now()).value,
        )
    }
}

@JvmInline
value class BroadcasterJobId(override val value: String) : EntityId {
    companion object {
        fun generate(): BroadcasterJobId = BroadcasterJobId(TypeId.generate("bcjob").toString())
    }
    override fun toString(): String = value
}

object BroadcasterJobTable : GUIDTable<BroadcasterJobId>("broadcaster_job", ::BroadcasterJobId) {
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
    val notificationData = jsonb<List<BroadcasterNotification>>("notification_data", KotlinxSerialization.json)
}

class BroadcasterJobEntity(guid: EntityID<BroadcasterJobId>) : GUIDEntity<BroadcasterJobId>(guid) {
    companion object : EntityClass<BroadcasterJobId, BroadcasterJobEntity>(BroadcasterJobTable) {
        fun create(id: BroadcasterJobId, notifications: List<BroadcasterNotification>, currentTime: Instant): BroadcasterJobId {
            return BroadcasterJobEntity.new(id) {
                this.createdAt = currentTime
                this.createdBy = "system"
                this.notificationData = notifications
            }.guid.value
        }

        fun deleteOlderThan(timestamp: Instant): Int =
            BroadcasterJobTable.deleteWhere { createdAt.less(timestamp) }
    }

    var createdAt by BroadcasterJobTable.createdAt
    var createdBy by BroadcasterJobTable.createdBy
    var notificationData by BroadcasterJobTable.notificationData
}
