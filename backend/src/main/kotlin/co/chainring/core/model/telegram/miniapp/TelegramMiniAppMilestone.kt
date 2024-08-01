package co.chainring.core.model.telegram.miniapp

import co.chainring.core.utils.crPoints
import java.math.BigDecimal

data class TelegramMiniAppMilestoneReward(val cp: BigDecimal, val invites: Long) {

    companion object {
        val milestones = listOf(
            TelegramMiniAppMilestoneReward(cp = "1000".crPoints(), invites = 3),
            TelegramMiniAppMilestoneReward(cp = "2000".crPoints(), invites = 3),
            TelegramMiniAppMilestoneReward(cp = "9000".crPoints(), invites = 11),
            TelegramMiniAppMilestoneReward(cp = "36000".crPoints(), invites = 15),
            TelegramMiniAppMilestoneReward(cp = "126000".crPoints(), invites = 20),
            TelegramMiniAppMilestoneReward(cp = "378000".crPoints(), invites = -1),
        )
    }
}

object TelegramMiniAppMilestone {

    fun nextMilestone(balance: BigDecimal): TelegramMiniAppMilestoneReward? {
        return TelegramMiniAppMilestoneReward.milestones
            .firstOrNull { milestone -> balance < milestone.cp }
    }

    fun previousMilestone(balance: BigDecimal): TelegramMiniAppMilestoneReward? {
        return TelegramMiniAppMilestoneReward.milestones
            .lastOrNull { milestone -> balance >= milestone.cp }
    }
}
