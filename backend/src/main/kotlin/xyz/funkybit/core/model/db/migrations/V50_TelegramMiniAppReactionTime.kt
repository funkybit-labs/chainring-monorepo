package xyz.funkybit.core.model.db.migrations

import de.fxlae.typeid.TypeId
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.db.updateEnum
import xyz.funkybit.core.model.db.EntityId
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppUserId
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppUserRewardId

@Suppress("ClassName")
class V50_TelegramMiniAppReactionTime : Migration() {

    @Serializable
    @JvmInline
    value class V50_TelegramMiniAppGameReactionTimeId(override val value: String) : EntityId {
        companion object {
            fun generate(): V50_TelegramMiniAppGameReactionTimeId = V50_TelegramMiniAppGameReactionTimeId(TypeId.generate("tmagrt").toString())
        }

        override fun toString(): String = value
    }

    object V50_TelegramMiniAppUserTable : GUIDTable<TelegramMiniAppUserId>("telegram_mini_app_user", ::TelegramMiniAppUserId) {
        val gameTickets = long("game_tickets").default(0)
    }

    enum class V50_TelegramMiniAppUserRewardType {
        GoalAchievement,
        ReactionGame,
        ReferralBonus,
    }

    object V50_TelegramMiniAppUserRewardTable : GUIDTable<TelegramMiniAppUserRewardId>("telegram_mini_app_user_reward", ::TelegramMiniAppUserRewardId) {
        val type = customEnumeration(
            "type",
            "TelegramMiniAppUserRewardType",
            { value -> V50_TelegramMiniAppUserRewardType.valueOf(value as String) },
            { PGEnum("TelegramMiniAppUserRewardType", it) },
        ).index()
    }

    object V50_TelegramMiniAppGameReactionTimeTable : GUIDTable<V50_TelegramMiniAppGameReactionTimeId>("telegram_mini_app_game_reaction_time", ::V50_TelegramMiniAppGameReactionTimeId) {
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val userGuid = reference("user_guid", V50_TelegramMiniAppUserTable).index()
        val reactionTimeMs = long("reaction_time_ms").index()
    }
    override fun run() {
        transaction {
            updateEnum<V50_TelegramMiniAppUserRewardType>(listOf(V50_TelegramMiniAppUserRewardTable.type), "TelegramMiniAppUserRewardType")
            SchemaUtils.createMissingTablesAndColumns(V50_TelegramMiniAppUserTable, V50_TelegramMiniAppGameReactionTimeTable)
        }
    }
}
