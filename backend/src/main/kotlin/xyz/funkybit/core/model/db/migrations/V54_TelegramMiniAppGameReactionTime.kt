package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration

@Suppress("ClassName")
class V54_TelegramMiniAppGameReactionTime : Migration() {

    object V54_TelegramMiniAppGameReactionTimeTable : Table("telegram_mini_app_game_reaction_time") {
        val reactionTimeMs = long("reaction_time_ms")
        val count = long("count")

        override val primaryKey = PrimaryKey(reactionTimeMs)
    }

    override fun run() {
        transaction {
            exec("DROP TABLE telegram_mini_app_game_reaction_time")
            SchemaUtils.createMissingTablesAndColumns(V54_TelegramMiniAppGameReactionTimeTable)
        }
    }
}
