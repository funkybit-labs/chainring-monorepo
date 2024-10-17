package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.UserId

@Suppress("ClassName")
class V100_AddDiscordUserIdToUser : Migration() {
    object V100_UserTable : GUIDTable<UserId>("user", ::UserId) {
        val discordUserId = varchar("discord_user_id", 10485760).nullable()
        val discordAccessToken = varchar("discord_access_token", 10485760).nullable()
        val discordRefreshToken = varchar("discord_refresh_token", 10485760).nullable()
    }
    override fun run() {
        SchemaUtils.createMissingTablesAndColumns(V100_UserTable)
    }
}
