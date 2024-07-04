package co.chainring.core.model.telegram.miniapp

import kotlinx.datetime.Clock
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object TelegramMiniAppGameUserReactionTimeTable : LongIdTable("telegram_mini_app_game_user_reaction_time") {
    val createdAt = timestamp("created_at")
    val userGuid = reference("user_guid", TelegramMiniAppUserTable).index()
    val reactionTimeMs = long("reaction_time_ms")
}

class TelegramMiniAppGameUserReactionTimeEntity(guid: EntityID<Long>) : Entity<Long>(guid) {
    companion object : EntityClass<Long, TelegramMiniAppGameUserReactionTimeEntity>(TelegramMiniAppGameUserReactionTimeTable) {
        private const val HUMAN_REACTION_TIME_THRESHOLD_MS = 25
        private const val CONSECUTIVE_NON_HUMAN_REACTION_TIMES_THRESHOLD = 2

        fun create(user: TelegramMiniAppUserEntity, reactionTimeMs: Long): TelegramMiniAppGameUserReactionTimeEntity =
            TelegramMiniAppGameUserReactionTimeEntity.new {
                val now = Clock.System.now()
                this.userGuid = user.guid
                this.createdAt = now
                this.reactionTimeMs = reactionTimeMs
            }

        fun detectBot(user: TelegramMiniAppUserEntity): Boolean =
            findLatestForUser(user, limit = CONSECUTIVE_NON_HUMAN_REACTION_TIMES_THRESHOLD)
                .all { it.reactionTimeMs < HUMAN_REACTION_TIME_THRESHOLD_MS }

        private fun findLatestForUser(user: TelegramMiniAppUserEntity, limit: Int): List<TelegramMiniAppGameUserReactionTimeEntity> =
            TelegramMiniAppGameUserReactionTimeEntity
                .find { TelegramMiniAppGameUserReactionTimeTable.userGuid.eq(user.guid) }
                .orderBy(Pair(TelegramMiniAppGameUserReactionTimeTable.createdAt, SortOrder.DESC))
                .limit(limit)
                .toList()
    }

    var createdAt by TelegramMiniAppGameUserReactionTimeTable.createdAt
    var userGuid by TelegramMiniAppGameUserReactionTimeTable.userGuid
    var reactionTimeMs by TelegramMiniAppGameUserReactionTimeTable.reactionTimeMs
}
