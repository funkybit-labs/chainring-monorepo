package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.db.updateEnum
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppUserId
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppUserRewardId

@Suppress("ClassName")
class V52_TelegramMiniAppCheckInStreak : Migration() {

    object V52_TelegramMiniAppUserTable : GUIDTable<TelegramMiniAppUserId>("telegram_mini_app_user", ::TelegramMiniAppUserId) {
        val checkInStreakDays = integer("check_in_streak_days").default(0)
        val lastStreakDayGrantedAt = timestamp("last_streak_day_granted_at").nullable()
    }

    enum class V52_TelegramMiniAppUserRewardType {
        GoalAchievement,
        DailyCheckIn,
        ReactionGame,
        ReferralBonus,
    }

    object V52_TelegramMiniAppUserRewardTable : GUIDTable<TelegramMiniAppUserRewardId>("telegram_mini_app_user_reward", ::TelegramMiniAppUserRewardId) {
        val type = customEnumeration(
            "type",
            "TelegramMiniAppUserRewardType",
            { value -> V52_TelegramMiniAppUserRewardType.valueOf(value as String) },
            { PGEnum("TelegramMiniAppUserRewardType", it) },
        ).index()
    }

    override fun run() {
        transaction {
            updateEnum<V52_TelegramMiniAppUserRewardType>(listOf(V52_TelegramMiniAppUserRewardTable.type), "TelegramMiniAppUserRewardType")
            SchemaUtils.createMissingTablesAndColumns(V52_TelegramMiniAppUserTable)
        }
    }
}
