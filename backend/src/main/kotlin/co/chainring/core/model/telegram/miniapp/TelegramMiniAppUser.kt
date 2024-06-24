package co.chainring.core.model.telegram.miniapp

import co.chainring.core.model.db.EntityId
import co.chainring.core.model.db.GUIDEntity
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.telegram.TelegramUserId
import co.chainring.core.utils.crPoints
import co.chainring.core.utils.setScale
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.sum
import java.math.BigDecimal

@Serializable
@JvmInline
value class TelegramMiniAppUserId(override val value: String) : EntityId {
    companion object {
        fun generate(telegramUserId: TelegramUserId): TelegramMiniAppUserId = TelegramMiniAppUserId("tmauser_${telegramUserId.value}")
    }

    override fun toString(): String = value
}

object TelegramMiniAppUserTable : GUIDTable<TelegramMiniAppUserId>("telegram_mini_app_user", ::TelegramMiniAppUserId) {
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
    val updatedAt = timestamp("updated_at")
    val telegramUserId = long("telegram_user_id").uniqueIndex()
}

class TelegramMiniAppUserEntity(guid: EntityID<TelegramMiniAppUserId>) : GUIDEntity<TelegramMiniAppUserId>(guid) {
    companion object : EntityClass<TelegramMiniAppUserId, TelegramMiniAppUserEntity>(TelegramMiniAppUserTable) {
        fun create(telegramUserId: TelegramUserId): TelegramMiniAppUserEntity =
            TelegramMiniAppUserEntity.new(TelegramMiniAppUserId.generate(telegramUserId)) {
                this.telegramUserId = telegramUserId
                this.createdAt = Clock.System.now()
                this.updatedAt = this.createdAt
                this.createdBy = "telegramBot"
            }.also { user ->
                user.flush()
            }

        fun findByTelegramUserId(telegramUserId: TelegramUserId): TelegramMiniAppUserEntity? {
            return TelegramMiniAppUserEntity.find {
                TelegramMiniAppUserTable.telegramUserId.eq(telegramUserId.value)
            }.firstOrNull()
        }
    }

    var createdAt by TelegramMiniAppUserTable.createdAt
    var createdBy by TelegramMiniAppUserTable.createdBy
    var updatedAt by TelegramMiniAppUserTable.updatedAt
    var telegramUserId by TelegramMiniAppUserTable.telegramUserId.transform(
        toReal = { TelegramUserId(it) },
        toColumn = { it.value },
    )
    val rewards by TelegramMiniAppUserRewardEntity referrersOn TelegramMiniAppUserRewardTable.userGuid

    fun pointsBalance(): BigDecimal {
        val sumColumn = TelegramMiniAppUserRewardTable.amount.sum().alias("amount_sum")
        return TelegramMiniAppUserRewardTable
            .select(sumColumn)
            .where { TelegramMiniAppUserRewardTable.userGuid.eq(guid) }
            .map {
                it[sumColumn]?.setScale(18)
            }
            .firstOrNull() ?: "0".crPoints()
    }

    fun achievedGoals(): Set<TelegramMiniAppGoal.Id> =
        TelegramMiniAppUserRewardTable
            .select(TelegramMiniAppUserRewardTable.goalId)
            .where { TelegramMiniAppUserRewardTable.userGuid.eq(guid) }
            .andWhere { TelegramMiniAppUserRewardTable.goalId.isNotNull() }
            .distinct()
            .mapNotNull {
                it[TelegramMiniAppUserRewardTable.goalId]?.let { id -> TelegramMiniAppGoal.Id.valueOf(id) }
            }.toSet()

    fun grantReward(goalId: TelegramMiniAppGoal.Id) {
        val goal = TelegramMiniAppGoal.allPossible.first { it.id == goalId }
        TelegramMiniAppUserRewardEntity.create(this, goal.reward, goal.id)
    }
}
