package co.chainring.core.model.telegram.miniapp

import co.chainring.core.model.db.EntityId
import co.chainring.core.model.db.GUIDEntity
import co.chainring.core.model.db.GUIDTable
import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import java.math.BigDecimal

@Serializable
@JvmInline
value class TelegramMiniAppUserRewardId(override val value: String) : EntityId {
    companion object {
        fun generate(): TelegramMiniAppUserRewardId = TelegramMiniAppUserRewardId(TypeId.generate("tmaurwd").toString())
    }

    override fun toString(): String = value
}

object TelegramMiniAppUserRewardTable : GUIDTable<TelegramMiniAppUserRewardId>("telegram_mini_app_user_reward", ::TelegramMiniAppUserRewardId) {
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
    val updatedAt = timestamp("updated_at")
    val userGuid = reference("user_guid", TelegramMiniAppUserTable).index()
    val amount = decimal("amount", 30, 18)
    val goalId = varchar("goal_id", 10485760).nullable()
}

class TelegramMiniAppUserRewardEntity(guid: EntityID<TelegramMiniAppUserRewardId>) : GUIDEntity<TelegramMiniAppUserRewardId>(guid) {
    companion object : EntityClass<TelegramMiniAppUserRewardId, TelegramMiniAppUserRewardEntity>(TelegramMiniAppUserRewardTable) {
        fun create(user: TelegramMiniAppUserEntity, amount: BigDecimal, goalId: TelegramMiniAppGoal.Id?) {
            TelegramMiniAppUserRewardEntity.new(TelegramMiniAppUserRewardId.generate()) {
                this.userGuid = user.guid
                this.createdAt = Clock.System.now()
                this.updatedAt = this.createdAt
                this.createdBy = user.guid.value.value
                this.goalId = goalId
                this.amount = amount
            }
        }
    }

    var createdAt by TelegramMiniAppUserRewardTable.createdAt
    var createdBy by TelegramMiniAppUserRewardTable.createdBy
    var updatedAt by TelegramMiniAppUserRewardTable.updatedAt
    var userGuid by TelegramMiniAppUserRewardTable.userGuid
    var user by TelegramMiniAppUserEntity referencedOn TelegramMiniAppUserRewardTable.userGuid
    var amount by TelegramMiniAppUserRewardTable.amount
    var goalId by TelegramMiniAppUserRewardTable.goalId.transform(
        toReal = { it?.let(TelegramMiniAppGoal.Id::valueOf) },
        toColumn = { it?.name },
    )
}
