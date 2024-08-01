package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppUserId

@Suppress("ClassName")
class V55_TelegramMiniAppMilestones : Migration() {

    object V55_TelegramMiniAppUserTable : GUIDTable<TelegramMiniAppUserId>("telegram_mini_app_user", ::TelegramMiniAppUserId) {
        val invites = long("invites").default(5)
        val lastMilestoneGrantedAt = timestamp("last_milestone_granted_at").nullable()
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V55_TelegramMiniAppUserTable)
        }
    }
}
