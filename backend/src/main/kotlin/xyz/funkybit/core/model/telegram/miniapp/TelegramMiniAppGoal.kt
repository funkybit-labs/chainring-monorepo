package xyz.funkybit.core.model.telegram.miniapp

import kotlinx.serialization.Serializable
import xyz.funkybit.apps.api.model.BigDecimalJson
import xyz.funkybit.core.utils.crPoints

@Serializable
data class TelegramMiniAppGoal(val id: Id, val reward: BigDecimalJson, val verified: Boolean) {
    @Serializable
    enum class Id {
        GithubSubscription,
        DiscordSubscription,
        LinkedinSubscription,
        XSubscription,
    }

    companion object {
        val allPossible = listOf(
            TelegramMiniAppGoal(Id.GithubSubscription, reward = "240".crPoints(), verified = false),
            TelegramMiniAppGoal(Id.DiscordSubscription, reward = "240".crPoints(), verified = true),
            TelegramMiniAppGoal(Id.LinkedinSubscription, reward = "240".crPoints(), verified = false),
            TelegramMiniAppGoal(Id.XSubscription, reward = "240".crPoints(), verified = false),
        )
    }
}
