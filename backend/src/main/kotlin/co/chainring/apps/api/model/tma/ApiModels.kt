package co.chainring.apps.api.model.tma

import co.chainring.apps.api.model.BigDecimalJson
import co.chainring.core.model.telegram.miniapp.TelegramMiniAppCheckInStreak
import co.chainring.core.model.telegram.miniapp.TelegramMiniAppGoal
import co.chainring.core.model.telegram.miniapp.TelegramMiniAppUserEntity
import co.chainring.core.utils.truncateTo
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class GetUserApiResponse(
    val balance: BigDecimalJson,
    val goals: List<Goal>,
    val gameTickets: Long,
    val checkInStreak: CheckInStreak,
) {
    companion object {
        fun fromEntity(user: TelegramMiniAppUserEntity): GetUserApiResponse {
            val dailyReward = TelegramMiniAppCheckInStreak.grantDailyReward(user)

            return GetUserApiResponse(
                balance = user.pointsBalance(),
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
}

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
