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
class V49_TelegramMiniAppReactionTime : Migration() {
    object V49_TelegramMiniAppUserTable : GUIDTable<TelegramMiniAppUserId>("telegram_mini_app_user", ::TelegramMiniAppUserId) {
        val gameTickets = long("game_tickets").default(0)
    }

    enum class V49_TelegramMiniAppUserRewardType {
        GoalAchievement,
        ReactionGame,
        ReferralBonus,
    }

    object V49_TelegramMiniAppUserRewardTable : GUIDTable<TelegramMiniAppUserRewardId>("telegram_mini_app_user_reward", ::TelegramMiniAppUserRewardId) {
        val type = customEnumeration(
            "type",
            "TelegramMiniAppUserRewardType",
            { value -> V49_TelegramMiniAppUserRewardType.valueOf(value as String) },
            { PGEnum("TelegramMiniAppUserRewardType", it) },
        ).index()
    }

    object V49_TelegramMiniAppGameReactionTimeTable : GUIDTable<TelegramMiniAppGameReactionTimeId>("telegram_mini_app_game_reaction_time", ::TelegramMiniAppGameReactionTimeId) {
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val userGuid = reference("user_guid", V49_TelegramMiniAppUserTable).index()
        val reactionTimeMs = long("reaction_time_ms").index()
    }
    override fun run() {
        transaction {
            updateEnum<V49_TelegramMiniAppUserRewardType>(listOf(V49_TelegramMiniAppUserRewardTable.type), "TelegramMiniAppUserRewardType")
            SchemaUtils.createMissingTablesAndColumns(V49_TelegramMiniAppUserTable, V49_TelegramMiniAppGameReactionTimeTable)
        }
    }
}
