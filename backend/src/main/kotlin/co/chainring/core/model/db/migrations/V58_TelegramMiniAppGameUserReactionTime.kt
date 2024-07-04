package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.telegram.miniapp.TelegramMiniAppUserId
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V58_TelegramMiniAppGameUserReactionTime : Migration() {
    object V58_TelegramMiniAppUserTable : GUIDTable<TelegramMiniAppUserId>("telegram_mini_app_user", ::TelegramMiniAppUserId) {
        val isBot = bool("is_bot").index().default(false)
    }

    object V58_TelegramMiniAppGameUserReactionTimeTable : LongIdTable("telegram_mini_app_game_user_reaction_time") {
        val createdAt = timestamp("created_at")
        val userGuid = reference("user_guid", V58_TelegramMiniAppUserTable).index()
        val reactionTimeMs = long("reaction_time_ms")
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V58_TelegramMiniAppUserTable, V58_TelegramMiniAppGameUserReactionTimeTable)
        }
    }
}
