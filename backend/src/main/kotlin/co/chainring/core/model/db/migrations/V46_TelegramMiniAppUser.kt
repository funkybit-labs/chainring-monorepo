package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.telegram.miniapp.TelegramMiniAppUserId
import co.chainring.core.model.telegram.miniapp.TelegramMiniAppUserRewardId
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V46_TelegramMiniAppUser : Migration() {
    object V46_TelegramMiniAppUserTable : GUIDTable<TelegramMiniAppUserId>("telegram_mini_app_user", ::TelegramMiniAppUserId) {
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val updatedAt = timestamp("updated_at")
        val telegramUserId = long("telegram_user_id").uniqueIndex()
    }

    object V46_TelegramMiniAppUserRewardTable : GUIDTable<TelegramMiniAppUserRewardId>("telegram_mini_app_user_reward", ::TelegramMiniAppUserRewardId) {
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val updatedAt = timestamp("updated_at")
        val userGuid = reference("user_guid", V46_TelegramMiniAppUserTable).index()
        val amount = decimal("amount", 30, 18)
        val goalId = varchar("goal_id", 10485760).nullable()
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V46_TelegramMiniAppUserTable, V46_TelegramMiniAppUserRewardTable)
        }
    }
}
