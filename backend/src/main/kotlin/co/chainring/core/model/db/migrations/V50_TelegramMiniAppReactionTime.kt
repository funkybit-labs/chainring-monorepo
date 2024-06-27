package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.db.updateEnum
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.PGEnum
import co.chainring.core.model.telegram.miniapp.TelegramMiniAppGameReactionTimeId
import co.chainring.core.model.telegram.miniapp.TelegramMiniAppUserId
import co.chainring.core.model.telegram.miniapp.TelegramMiniAppUserRewardId
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V50_TelegramMiniAppReactionTime : Migration() {
    object V50_TelegramMiniAppUserTable : GUIDTable<TelegramMiniAppUserId>("telegram_mini_app_user", ::TelegramMiniAppUserId) {
        val gameTickets = long("game_tickets").default(0)
    }

    enum class V50_TelegramMiniAppUserRewardType {
        GoalAchievement,
        ReactionGame,
        ReferralBonus,
    }

    object V50_TelegramMiniAppUserRewardTable : GUIDTable<TelegramMiniAppUserRewardId>("telegram_mini_app_user_reward", ::TelegramMiniAppUserRewardId) {
        val type = customEnumeration(
            "type",
            "TelegramMiniAppUserRewardType",
            { value -> V50_TelegramMiniAppUserRewardType.valueOf(value as String) },
            { PGEnum("TelegramMiniAppUserRewardType", it) },
        ).index()
    }

    object V50_TelegramMiniAppGameReactionTimeTable : GUIDTable<TelegramMiniAppGameReactionTimeId>("telegram_mini_app_game_reaction_time", ::TelegramMiniAppGameReactionTimeId) {
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val userGuid = reference("user_guid", V50_TelegramMiniAppUserTable).index()
        val reactionTimeMs = long("reaction_time_ms").index()
    }
    override fun run() {
        transaction {
            updateEnum<V50_TelegramMiniAppUserRewardType>(listOf(V50_TelegramMiniAppUserRewardTable.type), "TelegramMiniAppUserRewardType")
            SchemaUtils.createMissingTablesAndColumns(V50_TelegramMiniAppUserTable, V50_TelegramMiniAppGameReactionTimeTable)
        }
    }
}
