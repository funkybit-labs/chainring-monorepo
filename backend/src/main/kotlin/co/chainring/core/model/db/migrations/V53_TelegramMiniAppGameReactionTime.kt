package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V53_TelegramMiniAppGameReactionTime : Migration() {

    object V53_TelegramMiniAppGameReactionTimeTable : Table("telegram_mini_app_game_reaction_time") {
        val reactionTimeMs = long("reaction_time_ms")
        val count = long("count")

        override val primaryKey = PrimaryKey(reactionTimeMs)
    }

    override fun run() {
        transaction {
            exec("DROP TABLE telegram_mini_app_game_reaction_time")
            SchemaUtils.createMissingTablesAndColumns(V53_TelegramMiniAppGameReactionTimeTable)
        }
    }
}
