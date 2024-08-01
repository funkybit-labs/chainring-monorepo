package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum
import xyz.funkybit.core.model.db.enumDeclaration
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppUserId
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppUserRewardId

@Suppress("ClassName")
class V47_TelegramMiniAppUser : Migration() {
    object V47_TelegramMiniAppUserTable : GUIDTable<TelegramMiniAppUserId>("telegram_mini_app_user", ::TelegramMiniAppUserId) {
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val updatedAt = timestamp("updated_at").nullable()
        val telegramUserId = long("telegram_user_id").uniqueIndex()
    }

    enum class V47_TelegramMiniAppUserRewardType {
        GoalAchievement,
        ReferralBonus,
    }

    object V47_TelegramMiniAppUserRewardTable : GUIDTable<TelegramMiniAppUserRewardId>("telegram_mini_app_user_reward", ::TelegramMiniAppUserRewardId) {
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val updatedAt = timestamp("updated_at").nullable()
        val userGuid = reference("user_guid", V47_TelegramMiniAppUserTable).index()
        val amount = decimal("amount", 30, 18)
        val type = customEnumeration(
            "type",
            "TelegramMiniAppUserRewardType",
            { value -> V47_TelegramMiniAppUserRewardType.valueOf(value as String) },
            { PGEnum("TelegramMiniAppUserRewardType", it) },
        ).index()
        val goalId = varchar("goal_id", 10485760).nullable()

        init {
            uniqueIndex(
                customIndexName = "uix_tma_user_reward_user_guid_goal_id",
                columns = arrayOf(userGuid, goalId),
                filterCondition = {
                    goalId.isNotNull()
                },
            )
        }
    }

    override fun run() {
        transaction {
            exec("CREATE TYPE TelegramMiniAppUserRewardType AS ENUM (${enumDeclaration<V47_TelegramMiniAppUserRewardType>()})")
            SchemaUtils.createMissingTablesAndColumns(V47_TelegramMiniAppUserTable, V47_TelegramMiniAppUserRewardTable)
        }
    }
}
