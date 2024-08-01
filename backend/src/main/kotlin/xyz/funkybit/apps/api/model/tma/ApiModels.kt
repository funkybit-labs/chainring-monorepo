package xyz.funkybit.apps.api.model.tma

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import xyz.funkybit.apps.api.model.BigDecimalJson
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppCheckInStreak
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppGoal
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppInviteCode
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppMilestone
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppUserEntity
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppUserRewardType
import xyz.funkybit.core.model.telegram.miniapp.ofType
import xyz.funkybit.core.model.telegram.miniapp.sum
import xyz.funkybit.core.utils.truncateTo

@Serializable
data class GetUserApiResponse(
    val balance: BigDecimalJson,
    val referralBalance: BigDecimalJson,
    val goals: List<Goal>,
    val gameTickets: Long,
    val checkInStreak: CheckInStreak,
    val invites: Long,
    val inviteCode: TelegramMiniAppInviteCode,
    val nextMilestoneAt: BigDecimalJson?,
    val lastMilestone: Milestone?,
) {
    companion object {
        fun fromEntity(user: TelegramMiniAppUserEntity): GetUserApiResponse {
            val dailyReward = TelegramMiniAppCheckInStreak.grantDailyReward(user)

            val balances = user.pointsBalances()
            val fullBalance = balances.sum()
            val previousMilestone = TelegramMiniAppMilestone.previousMilestone(fullBalance)
            val nextMilestone = TelegramMiniAppMilestone.nextMilestone(fullBalance)

            return GetUserApiResponse(
                balance = fullBalance,
                referralBalance = balances.ofType(TelegramMiniAppUserRewardType.ReferralBonus),
                goals = TelegramMiniAppGoal.allPossible.let { allGoals ->
                    val achievedGoals = user.achievedGoals()
                    allGoals.map {
                        Goal(id = it.id, reward = it.reward, achieved = achievedGoals.contains(it.id))
                    }
                },
                gameTickets = user.gameTickets,
                checkInStreak = dailyReward.let {
                    CheckInStreak(
                        days = it.day,
                        reward = it.cp,
                        gameTickets = it.gameTickets,
                        grantedAt = user.lastStreakDayGrantedAt!!.truncateTo(DateTimeUnit.MILLISECOND),
                    )
                },
                invites = user.invites,
                inviteCode = user.inviteCode,
                nextMilestoneAt = nextMilestone?.cp,
                lastMilestone = user.lastMilestoneGrantedAt?.let { grantedAt ->
                    previousMilestone?.let {
                        Milestone(
                            invites = previousMilestone.invites,
                            grantedAt = grantedAt,
                            points = previousMilestone.cp,
                        )
                    }
                },
            )
        }
    }

    @Serializable
    data class Goal(
        val id: TelegramMiniAppGoal.Id,
        val reward: BigDecimalJson,
        val achieved: Boolean,
    )

    @Serializable
    data class CheckInStreak(
        val days: Int,
        val reward: BigDecimalJson,
        val gameTickets: Long,
        val grantedAt: Instant,
    )

    @Serializable
    data class Milestone(
        val invites: Long,
        val grantedAt: Instant,
        val points: BigDecimalJson,
    )
}

@Serializable
data class SingUpApiRequest(
    val inviteCode: TelegramMiniAppInviteCode? = null,
)

@Serializable
data class ClaimRewardApiRequest(
    val goalId: TelegramMiniAppGoal.Id,
)

@Serializable
data class ReactionTimeApiRequest(
    val reactionTimeMs: Long,
)

@Serializable
data class ReactionsTimeApiResponse(
    val percentile: Int,
    val reward: BigDecimalJson,
    val balance: BigDecimalJson,
)
