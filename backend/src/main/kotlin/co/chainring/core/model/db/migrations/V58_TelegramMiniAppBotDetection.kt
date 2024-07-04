package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.PGEnum
import co.chainring.core.model.db.enumDeclaration
import co.chainring.core.model.telegram.miniapp.TelegramMiniAppUserId
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V58_TelegramMiniAppBotDetection : Migration() {
    enum class V58_TelegramMiniAppUserIsBot {
        No,
        Maybe,
        Yes,
    }

    object V58_TelegramMiniAppUserTable : GUIDTable<TelegramMiniAppUserId>("telegram_mini_app_user", ::TelegramMiniAppUserId) {
        val isBot = customEnumeration(
            "is_bot",
            "TelegramMiniAppUserIsBot",
            { value -> V58_TelegramMiniAppUserIsBot.valueOf(value as String) },
            { PGEnum("TelegramMiniAppUserIsBot", it) },
        ).index().default(V58_TelegramMiniAppUserIsBot.No)
    }

    override fun run() {
        transaction {
            exec("CREATE TYPE TelegramMiniAppUserIsBot AS ENUM (${enumDeclaration<V58_TelegramMiniAppUserIsBot>()})")
            SchemaUtils.createMissingTablesAndColumns(V58_TelegramMiniAppUserTable)
        }
    }
}
