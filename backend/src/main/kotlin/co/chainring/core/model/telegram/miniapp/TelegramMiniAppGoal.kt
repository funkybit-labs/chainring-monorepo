package co.chainring.core.model.telegram.miniapp

import co.chainring.apps.api.model.BigDecimalJson
import co.chainring.core.utils.crPoints
import kotlinx.serialization.Serializable

@Serializable
data class TelegramMiniAppGoal(val id: Id, val reward: BigDecimalJson) {
    @Serializable
    enum class Id {
        GithubSubscription,
        MediumSubscription,
        LinkedinSubscription,
        XSubscription,
    }

    companion object {
        val allPossible = listOf(
            TelegramMiniAppGoal(Id.GithubSubscription, reward = "240".crPoints()),
            TelegramMiniAppGoal(Id.MediumSubscription, reward = "240".crPoints()),
            TelegramMiniAppGoal(Id.LinkedinSubscription, reward = "240".crPoints()),
            TelegramMiniAppGoal(Id.XSubscription, reward = "240".crPoints()),
        )
    }
}
