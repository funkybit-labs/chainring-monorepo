package xyz.funkybit.core.model.db

import de.fxlae.typeid.TypeId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.http4k.format.KotlinxSerialization
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.TransactionManager
import xyz.funkybit.apps.api.model.websocket.Balances
import xyz.funkybit.apps.api.model.websocket.Limits
import xyz.funkybit.apps.api.model.websocket.Prices
import xyz.funkybit.apps.api.model.websocket.Publishable
import xyz.funkybit.core.db.notifyDbListener
import xyz.funkybit.core.model.Address
import java.math.BigDecimal

private val logger = KotlinLogging.logger {}

@Serializable
data class BroadcasterNotification(
    val message: Publishable,
    val recipient: Address?,
) {
    companion object {
        fun pricesForMarketPeriods(marketId: MarketId, duration: OHLCDuration, ohlc: List<OHLCEntity>, full: Boolean, dailyChange: BigDecimal): BroadcasterNotification =
            BroadcasterNotification(Prices(marketId, duration, ohlc.map { it.toWSResponse() }, full, dailyChange.stripTrailingZeros()), null)

        fun limits(wallet: WalletEntity): BroadcasterNotification =
            BroadcasterNotification(Limits(LimitEntity.forUserId(wallet.userGuid)), recipient = wallet.address)

        fun walletBalances(wallet: WalletEntity): BroadcasterNotification =
            BroadcasterNotification(
                Balances(BalanceEntity.balancesAsApiResponse(wallet.userGuid).balances),
                recipient = wallet.address,
            )
    }
}

fun publishBroadcasterNotifications(notifications: List<BroadcasterNotification>) {
    if (notifications.isNotEmpty()) {
        logger.debug { "Scheduling broadcaster notifications: $notifications" }
        TransactionManager.current().notifyDbListener(
            "broadcaster_ctl",
            BroadcasterJobEntity.create(notifications).value,
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
        fun create(notifications: List<BroadcasterNotification>): BroadcasterJobId {
            return BroadcasterJobEntity.new(BroadcasterJobId.generate()) {
                this.createdAt = Clock.System.now()
                this.createdBy = "system"
                this.notificationData = notifications
            }.guid.value
        }
    }

    var createdAt by BroadcasterJobTable.createdAt
    var createdBy by BroadcasterJobTable.createdBy
    var notificationData by BroadcasterJobTable.notificationData
}
