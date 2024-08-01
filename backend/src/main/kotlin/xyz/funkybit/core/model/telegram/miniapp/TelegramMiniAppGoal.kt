package xyz.funkybit.core.model.telegram.miniapp

import kotlinx.serialization.Serializable
import xyz.funkybit.apps.api.model.BigDecimalJson
import xyz.funkybit.core.utils.crPoints

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
