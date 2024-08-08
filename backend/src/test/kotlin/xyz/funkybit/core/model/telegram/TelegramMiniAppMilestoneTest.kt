package xyz.funkybit.core.model.telegram

import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppMilestone
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppMilestoneReward
import xyz.funkybit.core.utils.crPoints
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class TelegramMiniAppMilestoneTest {

    @Test
    fun testNextMilestone() {
        val testCases = listOf(
            BigDecimal("0") to TelegramMiniAppMilestoneReward(cp = "1000".crPoints(), invites = 3),
            BigDecimal("15") to TelegramMiniAppMilestoneReward(cp = "1000".crPoints(), invites = 3),
            BigDecimal("1000") to TelegramMiniAppMilestoneReward(cp = "2000".crPoints(), invites = 3),
            BigDecimal("5000") to TelegramMiniAppMilestoneReward(cp = "9000".crPoints(), invites = 11),
            BigDecimal("36000") to TelegramMiniAppMilestoneReward(cp = "126000".crPoints(), invites = 20),
            BigDecimal("126000") to TelegramMiniAppMilestoneReward(cp = "378000".crPoints(), invites = -1),
            BigDecimal("369999") to TelegramMiniAppMilestoneReward(cp = "378000".crPoints(), invites = -1),
            BigDecimal("378000") to null,
        )

        for ((balance, expected) in testCases) {
            val result = TelegramMiniAppMilestone.nextMilestone(balance)
            assertEquals(expected, result)
        }
    }

    @Test
    fun testPreviousMilestone() {
        val testCases = listOf(
            BigDecimal("0") to null,
            BigDecimal("500") to null,
            BigDecimal("999") to null,
            BigDecimal("1000") to TelegramMiniAppMilestoneReward(cp = "1000".crPoints(), invites = 3),
            BigDecimal("5000") to TelegramMiniAppMilestoneReward(cp = "2000".crPoints(), invites = 3),
            BigDecimal("9000") to TelegramMiniAppMilestoneReward(cp = "9000".crPoints(), invites = 11),
            BigDecimal("36000") to TelegramMiniAppMilestoneReward(cp = "36000".crPoints(), invites = 15),
            BigDecimal("126000") to TelegramMiniAppMilestoneReward(cp = "126000".crPoints(), invites = 20),
            BigDecimal("378000") to TelegramMiniAppMilestoneReward(cp = "378000".crPoints(), invites = -1),
            BigDecimal("500000") to TelegramMiniAppMilestoneReward(cp = "378000".crPoints(), invites = -1),
        )

        for ((balance, expected) in testCases) {
            val result = TelegramMiniAppMilestone.previousMilestone(balance)
            assertEquals(expected, result)
        }
    }
}
