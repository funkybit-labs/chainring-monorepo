package co.chainring.core.model.telegram.miniapp

import co.chainring.apps.api.model.BigDecimalJson
import kotlinx.serialization.Serializable
import java.math.BigDecimal

@Serializable
data class TelegramMiniAppGoal(val id: Id, val reward: BigDecimalJson) {
    @Serializable
    enum class Id {
        GithubSubscription,
        DiscordSubscription,
        MediumSubscription,
        LinkedinSubscription,
        XSubscription,
    }

    companion object {
        val allPossible = listOf(
            TelegramMiniAppGoal(Id.GithubSubscription, reward = BigDecimal("1000")),
            TelegramMiniAppGoal(Id.DiscordSubscription, reward = BigDecimal("1000")),
            TelegramMiniAppGoal(Id.MediumSubscription, reward = BigDecimal("1000")),
            TelegramMiniAppGoal(Id.LinkedinSubscription, reward = BigDecimal("1000")),
            TelegramMiniAppGoal(Id.XSubscription, reward = BigDecimal("1000")),
        )
    }
}
