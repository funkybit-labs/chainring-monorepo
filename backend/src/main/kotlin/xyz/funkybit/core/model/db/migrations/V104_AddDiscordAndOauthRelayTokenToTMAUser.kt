package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppUserId

@Suppress("ClassName")
class V104_AddDiscordAndOauthRelayTokenToTMAUser : Migration() {
    object V104_TelegramMiniAppUserTable : GUIDTable<TelegramMiniAppUserId>("telegram_mini_app_user", ::TelegramMiniAppUserId) {
        val oauthRelayAuthToken = varchar("oauth_relay_auth_token", 10485760).nullable().uniqueIndex()
        val oauthRelayAuthTokenExpiresAt = timestamp("oauth_relay_auth_token_expires_at").nullable()
        val discordUserId = varchar("discord_user_id", 10485760).nullable()
        val discordAccessToken = varchar("discord_access_token", 10485760).nullable()
        val discordRefreshToken = varchar("discord_refresh_token", 10485760).nullable()
    }
    override fun run() {
        SchemaUtils.createMissingTablesAndColumns(V104_TelegramMiniAppUserTable)
    }
}
