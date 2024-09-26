package xyz.funkybit.core.model.telegram.miniapp

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppCheckInStreakDailyReward.Companion.streakDailyRewards
import xyz.funkybit.core.utils.crPoints
import java.math.BigDecimal
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

data class TelegramMiniAppCheckInStreakDailyReward(val day: Int, val cp: BigDecimal, val gameTickets: Long) {

    companion object {
        val streakDailyRewards = listOf(
            TelegramMiniAppCheckInStreakDailyReward(1, cp = "20".crPoints(), gameTickets = 3),
            TelegramMiniAppCheckInStreakDailyReward(2, cp = "25".crPoints(), gameTickets = 3),
            TelegramMiniAppCheckInStreakDailyReward(3, cp = "30".crPoints(), gameTickets = 3),
            TelegramMiniAppCheckInStreakDailyReward(4, cp = "35".crPoints(), gameTickets = 5),
            TelegramMiniAppCheckInStreakDailyReward(5, cp = "40".crPoints(), gameTickets = 7),
            TelegramMiniAppCheckInStreakDailyReward(6, cp = "45".crPoints(), gameTickets = 10),
            TelegramMiniAppCheckInStreakDailyReward(7, cp = "50".crPoints(), gameTickets = 13),
        )
    }
}

object TelegramMiniAppCheckInStreak {

    fun grantDailyReward(userEntity: TelegramMiniAppUserEntity): TelegramMiniAppCheckInStreakDailyReward {
        val streakDayPeriod = 1.days

        val userRegisteredAt = userEntity.createdAt
        val lastStreakDayGrantedAt = userEntity.lastStreakDayGrantedAt
        val now = Clock.System.now()

        val ticksUntilLastStrikeGranted: Long? = lastStreakDayGrantedAt?.let { calculateIntervalsOccurred(userRegisteredAt, streakDayPeriod, lastStreakDayGrantedAt) }
        val ticksUntilNow: Long = calculateIntervalsOccurred(userRegisteredAt, streakDayPeriod, now)

        val (streakDay, grantedAt) = when {
            // new user
            ticksUntilLastStrikeGranted == null -> 1 to now
            // same day
            (ticksUntilNow - ticksUntilLastStrikeGranted) == 0L -> userEntity.checkInStreakDays to userEntity.lastStreakDayGrantedAt
            // next day
            (ticksUntilNow - ticksUntilLastStrikeGranted) == 1L -> userEntity.checkInStreakDays + 1 to now
            // reset strike
            else -> 1 to now
        }

        val config = streakDailyRewards.find { it.day == streakDay } ?: streakDailyRewards.maxBy { it.day }

        if (userEntity.lastStreakDayGrantedAt != grantedAt) {
            userEntity.checkInStreakDays = streakDay
            userEntity.lastStreakDayGrantedAt = now
            userEntity.gameTickets += config.gameTickets
            TelegramMiniAppUserRewardEntity.createDailyCheckInReward(userEntity, config.cp)
        }

        return config
    }

    private fun calculateIntervalsOccurred(initialDate: Instant, interval: Duration, targetDate: Instant): Long {
        val durationBetween = targetDate - initialDate
        return durationBetween.inWholeMilliseconds / interval.inWholeMilliseconds
    }
}
