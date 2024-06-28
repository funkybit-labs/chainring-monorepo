package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.db.updateEnum
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.PGEnum
import co.chainring.core.model.telegram.miniapp.TelegramMiniAppUserId
import co.chainring.core.model.telegram.miniapp.TelegramMiniAppUserRewardId
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V51_TelegramMiniAppCheckInStreak : Migration() {

    object V51_TelegramMiniAppUserTable : GUIDTable<TelegramMiniAppUserId>("telegram_mini_app_user", ::TelegramMiniAppUserId) {
        val checkInStreakDays = integer("check_in_streak_days").default(0)
        val lastStreakDayGrantedAt = timestamp("last_streak_day_granted_at").nullable()
    }

    enum class V51_TelegramMiniAppUserRewardType {
        GoalAchievement,
        DailyCheckIn,
        ReactionGame,
        ReferralBonus,
    }

    object V51_TelegramMiniAppUserRewardTable : GUIDTable<TelegramMiniAppUserRewardId>("telegram_mini_app_user_reward", ::TelegramMiniAppUserRewardId) {
        val type = customEnumeration(
            "type",
            "TelegramMiniAppUserRewardType",
            { value -> V51_TelegramMiniAppUserRewardType.valueOf(value as String) },
            { PGEnum("TelegramMiniAppUserRewardType", it) },
        ).index()
    }

    override fun run() {
        transaction {
            updateEnum<V51_TelegramMiniAppUserRewardType>(listOf(V51_TelegramMiniAppUserRewardTable.type), "TelegramMiniAppUserRewardType")
            SchemaUtils.createMissingTablesAndColumns(V51_TelegramMiniAppUserTable)
        }
    }
}
