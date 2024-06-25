package co.chainring.apps.api.model.tma

import co.chainring.apps.api.model.BigDecimalJson
import co.chainring.core.model.telegram.miniapp.TelegramMiniAppGoal
import co.chainring.core.model.telegram.miniapp.TelegramMiniAppUserEntity
import kotlinx.serialization.Serializable

@Serializable
data class GetUserApiResponse(
    val balance: BigDecimalJson,
    val goals: List<Goal>,
) {
    companion object {
        fun fromEntity(user: TelegramMiniAppUserEntity): GetUserApiResponse =
            GetUserApiResponse(
                balance = user.pointsBalance(),
                goals = TelegramMiniAppGoal.allPossible.let { allGoals ->
                    val achievedGoals = user.achievedGoals()
                    allGoals.map {
                        Goal(id = it.id, reward = it.reward, achieved = achievedGoals.contains(it.id))
                    }
                },
            )
    }

    @Serializable
    data class Goal(
        val id: TelegramMiniAppGoal.Id,
        val reward: BigDecimalJson,
        val achieved: Boolean,
    )
}

@Serializable
data class ClaimRewardApiRequest(
    val goalId: TelegramMiniAppGoal.Id,
)
