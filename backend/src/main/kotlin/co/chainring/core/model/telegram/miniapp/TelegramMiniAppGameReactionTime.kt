package co.chainring.core.model.telegram.miniapp

import co.chainring.core.model.db.EntityId
import co.chainring.core.model.db.GUIDEntity
import co.chainring.core.model.db.GUIDTable
import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

@Serializable
@JvmInline
value class TelegramMiniAppGameReactionTimeId(override val value: String) : EntityId {
    companion object {
        fun generate(): TelegramMiniAppGameReactionTimeId = TelegramMiniAppGameReactionTimeId(TypeId.generate("tmagrt").toString())
    }

    override fun toString(): String = value
}

object TelegramMiniAppGameReactionTimeTable : GUIDTable<TelegramMiniAppGameReactionTimeId>("telegram_mini_app_game_reaction_time", ::TelegramMiniAppGameReactionTimeId) {
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
    val userGuid = reference("user_guid", TelegramMiniAppUserTable).index()
    val reactionTimeMs = long("reaction_time_ms").index()
}

class TelegramMiniAppGameReactionTimeEntity(guid: EntityID<TelegramMiniAppGameReactionTimeId>) : GUIDEntity<TelegramMiniAppGameReactionTimeId>(guid) {
    companion object : EntityClass<TelegramMiniAppGameReactionTimeId, TelegramMiniAppGameReactionTimeEntity>(TelegramMiniAppGameReactionTimeTable) {
        fun create(user: TelegramMiniAppUserEntity, reactionTimeMs: Long) {
            TelegramMiniAppGameReactionTimeTable.insert {
                val now = Clock.System.now()
                it[guid] = EntityID(TelegramMiniAppGameReactionTimeId.generate(), TelegramMiniAppGameReactionTimeTable)
                it[userGuid] = user.guid
                it[createdAt] = now
                it[createdBy] = user.guid.value.value
                it[TelegramMiniAppGameReactionTimeTable.reactionTimeMs] = reactionTimeMs
            }
        }
    }

    var createdAt by TelegramMiniAppGameReactionTimeTable.createdAt
    var createdBy by TelegramMiniAppGameReactionTimeTable.createdBy
    var userGuid by TelegramMiniAppGameReactionTimeTable.userGuid
    var reactionTimeMs by TelegramMiniAppGameReactionTimeTable.reactionTimeMs
}
