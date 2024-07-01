package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.telegram.miniapp.TelegramMiniAppUserId
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V53_TelegramMiniAppMilestones : Migration() {

    object V53_TelegramMiniAppUserTable : GUIDTable<TelegramMiniAppUserId>("telegram_mini_app_user", ::TelegramMiniAppUserId) {
        val invites = long("invites").default(0)
        val lastMilestoneGrantedAt = timestamp("last_milestone_granted_at").nullable()
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V53_TelegramMiniAppUserTable)
        }
    }
}
