package xyz.funkybit.core.model.telegram.miniapp

import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.compoundAnd
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.sum
import xyz.funkybit.core.model.db.EntityId
import xyz.funkybit.core.model.db.GUIDEntity
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum
import java.math.BigDecimal

@Serializable
@JvmInline
value class TelegramMiniAppUserRewardId(override val value: String) : EntityId {
    companion object {
        fun generate(): TelegramMiniAppUserRewardId = TelegramMiniAppUserRewardId(TypeId.generate("tmaurwd").toString())
    }

    override fun toString(): String = value
}

enum class TelegramMiniAppUserRewardType {
    GoalAchievement,
    DailyCheckIn,
    ReactionGame,
    ReferralBonus,
}

object TelegramMiniAppUserRewardTable : GUIDTable<TelegramMiniAppUserRewardId>("telegram_mini_app_user_reward", ::TelegramMiniAppUserRewardId) {
    val createdAt = timestamp("created_at").index()
    val createdBy = varchar("created_by", 10485760)
    val userGuid = reference("user_guid", TelegramMiniAppUserTable).index()
    val amount = decimal("amount", 30, 18)
    val type = customEnumeration(
        "type",
        "TelegramMiniAppUserRewardType",
        { value -> TelegramMiniAppUserRewardType.valueOf(value as String) },
        { PGEnum("TelegramMiniAppUserRewardType", it) },
    ).index()
    val goalId = varchar("goal_id", 10485760).nullable()

    init {
        uniqueIndex(
            customIndexName = "uix_tma_user_reward_user_guid_goal_id",
            columns = arrayOf(userGuid, goalId),
            filterCondition = {
                goalId.isNotNull()
            },
        )
    }
}

class TelegramMiniAppUserRewardEntity(guid: EntityID<TelegramMiniAppUserRewardId>) : GUIDEntity<TelegramMiniAppUserRewardId>(guid) {
    companion object : EntityClass<TelegramMiniAppUserRewardId, TelegramMiniAppUserRewardEntity>(TelegramMiniAppUserRewardTable) {
        fun createGoalAchievementReward(user: TelegramMiniAppUserEntity, amount: BigDecimal, goalId: TelegramMiniAppGoal.Id) {
            create(user, TelegramMiniAppUserRewardType.GoalAchievement, amount, goalId = goalId)
        }

        fun createDailyCheckInReward(user: TelegramMiniAppUserEntity, amount: BigDecimal) {
            create(user, TelegramMiniAppUserRewardType.DailyCheckIn, amount)
        }

        fun createReactionGameReward(user: TelegramMiniAppUserEntity, amount: BigDecimal) {
            create(user, TelegramMiniAppUserRewardType.ReactionGame, amount)
        }

        fun createReferralBonusReward(user: TelegramMiniAppUserEntity, amount: BigDecimal) {
            create(user, TelegramMiniAppUserRewardType.ReferralBonus, amount, by = "system")
        }

        private fun create(user: TelegramMiniAppUserEntity, type: TelegramMiniAppUserRewardType, amount: BigDecimal, goalId: TelegramMiniAppGoal.Id? = null, by: String = user.guid.value.value) {
            val now = Clock.System.now()

            val previousBalance = user.pointsBalances().sum()
            val newBalance = previousBalance + amount

            TelegramMiniAppUserRewardTable.insertIgnore {
                it[guid] = EntityID(TelegramMiniAppUserRewardId.generate(), TelegramMiniAppUserRewardTable)
                it[userGuid] = user.guid
                it[createdAt] = now
                it[createdBy] = by
                it[TelegramMiniAppUserRewardTable.type] = type
                goalId?.let { goal -> it[TelegramMiniAppUserRewardTable.goalId] = goal.name }
                it[TelegramMiniAppUserRewardTable.amount] = amount
            }

            // check if a milestone was reached
            val previousBalanceNextMilestone = TelegramMiniAppMilestone.nextMilestone(previousBalance)
            val newBalanceNextMilestone = TelegramMiniAppMilestone.nextMilestone(newBalance)
            if (previousBalanceNextMilestone != newBalanceNextMilestone) {
                previousBalanceNextMilestone?.let { reachedMilestone ->

                    if (reachedMilestone.invites == -1L) {
                        // special case for unlimited invites
                        user.invites = -1L
                    } else {
                        // otherwise sum invites
                        user.invites += reachedMilestone.invites
                    }

                    user.lastMilestoneGrantedAt = Clock.System.now()
                }
            }
        }

        fun inviteePointsPerInviter(from: Instant, to: Instant): Map<TelegramMiniAppUserId, BigDecimal> {
            return TelegramMiniAppUserRewardTable
                .join(TelegramMiniAppUserTable, JoinType.LEFT, TelegramMiniAppUserTable.guid, TelegramMiniAppUserRewardTable.userGuid)
                .select(TelegramMiniAppUserTable.invitedBy, TelegramMiniAppUserRewardTable.amount.sum())
                .where {
                    listOf(
                        TelegramMiniAppUserTable.invitedBy.isNotNull(),
                        TelegramMiniAppUserTable.isBot.neq(TelegramMiniAppUserIsBot.Yes),
                        TelegramMiniAppUserRewardTable.createdAt greater from,
                        TelegramMiniAppUserRewardTable.createdAt lessEq to,
                    ).compoundAnd()
                }
                .groupBy(TelegramMiniAppUserTable.invitedBy)
                .associate {
                    it[TelegramMiniAppUserTable.invitedBy]!!.value to (
                        it[TelegramMiniAppUserRewardTable.amount.sum()]?.setScale(18)
                            ?: BigDecimal.ZERO
                        )
                }
        }
    }

    var createdAt by TelegramMiniAppUserRewardTable.createdAt
    var createdBy by TelegramMiniAppUserRewardTable.createdBy
    var userGuid by TelegramMiniAppUserRewardTable.userGuid
    var user by TelegramMiniAppUserEntity referencedOn TelegramMiniAppUserRewardTable.userGuid
    var amount by TelegramMiniAppUserRewardTable.amount
    var goalId by TelegramMiniAppUserRewardTable.goalId.transform(
        toReal = { it?.let(TelegramMiniAppGoal.Id::valueOf) },
        toColumn = { it?.name },
    )
}
