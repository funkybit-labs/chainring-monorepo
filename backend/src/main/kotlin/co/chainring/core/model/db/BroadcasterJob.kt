package co.chainring.core.model.db

import co.chainring.apps.api.model.websocket.Publishable
import co.chainring.core.model.Address
import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.http4k.format.KotlinxSerialization
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

@Serializable
data class PrincipalNotifications(
    val principal: Address,
    val notifications: List<Publishable>,
)

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
    val notificationData = jsonb<List<PrincipalNotifications>>("notification_data", KotlinxSerialization.json)
}

class BroadcasterJobEntity(guid: EntityID<BroadcasterJobId>) : GUIDEntity<BroadcasterJobId>(guid) {

    companion object : EntityClass<BroadcasterJobId, BroadcasterJobEntity>(BroadcasterJobTable) {

        fun create(notificationData: List<PrincipalNotifications>): BroadcasterJobId {
            return BroadcasterJobEntity.new(BroadcasterJobId.generate()) {
                this.createdAt = Clock.System.now()
                this.createdBy = "system"
                this.notificationData = notificationData
            }.guid.value
        }
    }

    var createdAt by BroadcasterJobTable.createdAt
    var createdBy by BroadcasterJobTable.createdBy
    var notificationData by BroadcasterJobTable.notificationData
}
