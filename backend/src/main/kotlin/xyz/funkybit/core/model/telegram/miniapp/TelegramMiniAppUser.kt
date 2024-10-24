package xyz.funkybit.core.model.telegram.miniapp

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.sum
import xyz.funkybit.core.model.db.EntityId
import xyz.funkybit.core.model.db.GUIDEntity
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum
import xyz.funkybit.core.model.db.UserEntity
import xyz.funkybit.core.model.db.UserTable
import xyz.funkybit.core.model.telegram.TelegramUserId
import java.math.BigDecimal
import kotlin.random.Random

@Serializable
@JvmInline
value class TelegramMiniAppUserId(override val value: String) : EntityId {
    companion object {
        fun generate(telegramUserId: TelegramUserId): TelegramMiniAppUserId = TelegramMiniAppUserId("tmauser_${telegramUserId.value}")
    }

    override fun toString(): String = value
}

@Serializable
@JvmInline
value class TelegramMiniAppInviteCode(val value: String) {
    companion object {
        private const val CODE_LENGTH = 9
        private val CHAR_POOL: List<Char> = ('A'..'Z') + ('0'..'9')

        fun generate(): TelegramMiniAppInviteCode {
            val code = (1..CODE_LENGTH)
                .map { Random.nextInt(0, CHAR_POOL.size) }
                .map(CHAR_POOL::get)
                .joinToString("")

            return TelegramMiniAppInviteCode(code)
        }
    }

    override fun toString(): String = value
}

enum class TelegramMiniAppUserIsBot {
    No,
    Maybe,
    Yes,
}

object TelegramMiniAppUserTable : GUIDTable<TelegramMiniAppUserId>("telegram_mini_app_user", ::TelegramMiniAppUserId) {
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
    val updatedAt = timestamp("updated_at").nullable()
    val telegramUserId = long("telegram_user_id").uniqueIndex()
    val gameTickets = long("game_tickets").default(0)
    val checkInStreakDays = integer("check_in_streak_days").default(0)
    val lastStreakDayGrantedAt = timestamp("last_streak_day_granted_at").nullable()
    val lastMilestoneGrantedAt = timestamp("last_milestone_granted_at").nullable()
    val invites = long("invites").default(5)
    val inviteCode = varchar("invite_code", 10485760).index()
    val invitedBy = reference("invited_by", TelegramMiniAppUserTable).index().nullable()
    val isBot = customEnumeration(
        "is_bot",
        "TelegramMiniAppUserIsBot",
        { value -> TelegramMiniAppUserIsBot.valueOf(value as String) },
        { PGEnum("TelegramMiniAppUserIsBot", it) },
    ).index().default(TelegramMiniAppUserIsBot.No)
    val userGuid = reference("user_guid", UserTable)
}

class TelegramMiniAppUserEntity(guid: EntityID<TelegramMiniAppUserId>) : GUIDEntity<TelegramMiniAppUserId>(guid) {
    companion object : EntityClass<TelegramMiniAppUserId, TelegramMiniAppUserEntity>(TelegramMiniAppUserTable) {
        private const val HUMAN_REACTION_TIME_THRESHOLD_MS = 25

        fun create(telegramUserId: TelegramUserId, invitedBy: TelegramMiniAppUserEntity?): TelegramMiniAppUserEntity {
            invitedBy?.let {
                it.invites -= 1
            }

            val user = UserEntity.create("tma:$telegramUserId")

            return TelegramMiniAppUserEntity.new(TelegramMiniAppUserId.generate(telegramUserId)) {
                this.telegramUserId = telegramUserId
                this.createdAt = Clock.System.now()
                this.updatedAt = this.createdAt
                this.createdBy = "telegramBot"
                this.inviteCode = TelegramMiniAppInviteCode.generate()
                this.invitedBy = invitedBy
                this.userGuid = user.guid
            }.also { it.flush() }
        }

        fun findByTelegramUserId(telegramUserId: TelegramUserId): TelegramMiniAppUserEntity? {
            return TelegramMiniAppUserEntity.find {
                TelegramMiniAppUserTable.telegramUserId.eq(telegramUserId.value)
            }.firstOrNull()
        }

        fun findByInviteCode(code: TelegramMiniAppInviteCode): TelegramMiniAppUserEntity? {
            return TelegramMiniAppUserEntity.find {
                TelegramMiniAppUserTable.inviteCode.eq(code.value)
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
    var gameTickets by TelegramMiniAppUserTable.gameTickets
    val rewards by TelegramMiniAppUserRewardEntity referrersOn TelegramMiniAppUserRewardTable.userGuid
    var checkInStreakDays by TelegramMiniAppUserTable.checkInStreakDays
    var lastStreakDayGrantedAt by TelegramMiniAppUserTable.lastStreakDayGrantedAt
    var invites by TelegramMiniAppUserTable.invites
    var lastMilestoneGrantedAt by TelegramMiniAppUserTable.lastMilestoneGrantedAt
    var inviteCode by TelegramMiniAppUserTable.inviteCode.transform(
        toColumn = { it.value },
        toReal = { TelegramMiniAppInviteCode(it) },
    )
    var invitedBy by TelegramMiniAppUserEntity optionalReferencedOn TelegramMiniAppUserTable.invitedBy
    var isBot by TelegramMiniAppUserTable.isBot
    var userGuid by TelegramMiniAppUserTable.userGuid

    fun pointsBalances(): Map<TelegramMiniAppUserRewardType, BigDecimal> {
        val sumColumn = TelegramMiniAppUserRewardTable.amount.sum().alias("amount_sum")
        val typeColumn = TelegramMiniAppUserRewardTable.type
        return TelegramMiniAppUserRewardTable
            .select(typeColumn, sumColumn)
            .where { TelegramMiniAppUserRewardTable.userGuid.eq(guid) }
            .groupBy(typeColumn)
            .associate {
                it[typeColumn] to (it[sumColumn]?.setScale(18) ?: BigDecimal.ZERO)
            }
    }

    fun achievedGoals(): Set<TelegramMiniAppGoal.Id> =
        TelegramMiniAppUserRewardTable
            .select(TelegramMiniAppUserRewardTable.goalId)
            .where { TelegramMiniAppUserRewardTable.userGuid.eq(guid) and TelegramMiniAppUserRewardTable.type.eq(TelegramMiniAppUserRewardType.GoalAchievement) }
            .andWhere { TelegramMiniAppUserRewardTable.goalId.isNotNull() }
            .distinct()
            .mapNotNull {
                it[TelegramMiniAppUserRewardTable.goalId]?.let { id -> TelegramMiniAppGoal.Id.valueOf(id) }
            }.toSet()

    fun grantReward(goalId: TelegramMiniAppGoal.Id) {
        val goal = TelegramMiniAppGoal.allPossible.first { it.id == goalId }
        TelegramMiniAppUserRewardEntity.createGoalAchievementReward(this, goal.reward, goal.id)
    }

    fun lockForUpdate(): TelegramMiniAppUserEntity {
        return TelegramMiniAppUserEntity.find { TelegramMiniAppUserTable.telegramUserId eq telegramUserId.value }.forUpdate().single()
    }

    fun useGameTicket(reactionTimeMs: Long): Int {
        this.gameTickets -= 1
        this.updatedAt = Clock.System.now()

        if (reactionTimeMs < HUMAN_REACTION_TIME_THRESHOLD_MS) {
            when (this.isBot) {
                TelegramMiniAppUserIsBot.No -> {
                    this.isBot = TelegramMiniAppUserIsBot.Maybe
                }
                TelegramMiniAppUserIsBot.Maybe -> {
                    this.isBot = TelegramMiniAppUserIsBot.Yes
                }
                TelegramMiniAppUserIsBot.Yes -> {}
            }
        } else if (this.isBot == TelegramMiniAppUserIsBot.Maybe) {
            this.isBot = TelegramMiniAppUserIsBot.No
        }

        val roundedTime = TelegramMiniAppGameReactionTime.roundTime(reactionTimeMs)
        val percentile = TelegramMiniAppGameReactionTime.calculatePercentile(roundedTime)

        if (this.isBot != TelegramMiniAppUserIsBot.Yes) {
            TelegramMiniAppGameReactionTime.record(roundedTime)
        }

        TelegramMiniAppUserRewardEntity.createReactionGameReward(this, percentile.toBigDecimal())

        return percentile
    }
}

fun Map<TelegramMiniAppUserRewardType, BigDecimal>.sum(): BigDecimal {
    return this.values.fold(BigDecimal.ZERO) { acc: BigDecimal, balance: BigDecimal -> acc + balance }
}

fun Map<TelegramMiniAppUserRewardType, BigDecimal>.ofType(type: TelegramMiniAppUserRewardType): BigDecimal {
    return this[type] ?: BigDecimal.ZERO
}
