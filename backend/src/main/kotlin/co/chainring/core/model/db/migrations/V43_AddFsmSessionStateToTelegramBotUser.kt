package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.telegram.bot.TelegramBotUserId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import org.http4k.format.KotlinxSerialization
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V43_AddFsmSessionStateToTelegramBotUser : Migration() {
    @OptIn(ExperimentalSerializationApi::class)
    @JsonClassDiscriminator("type")
    @Serializable
    sealed class V43_BotSessionState {
        @Serializable
        @SerialName("Initial")
        data object Initial : V43_BotSessionState()
    }

    object V43_TelegramBotUserTable : GUIDTable<TelegramBotUserId>("telegram_bot_user", ::TelegramBotUserId) {
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val updatedAt = timestamp("updated_at")
        val telegramUserId = long("telegram_user_id").uniqueIndex()
        val messageIdsForDeletion = array<Int>("message_ids_for_deletion").default(emptyList())
        val sessionState = jsonb<V43_BotSessionState>("session_state", KotlinxSerialization.json).default(V43_BotSessionState.Initial)
    }

    override fun run() {
        transaction {
            exec("ALTER TABLE telegram_bot_user ADD COLUMN updated_at timestamp without time zone")
            exec("UPDATE telegram_bot_user SET updated_at = now()")
            exec("ALTER TABLE telegram_bot_user ALTER COLUMN updated_at SET NOT NULL")
            SchemaUtils.createMissingTablesAndColumns(V43_TelegramBotUserTable)
            exec("ALTER TABLE telegram_bot_user DROP COLUMN current_market_guid")
            exec("ALTER TABLE telegram_bot_user DROP COLUMN expected_reply_message_id")
            exec("ALTER TABLE telegram_bot_user DROP COLUMN expected_reply_type")
            exec("DROP TYPE TelegramUserReplyType")
            exec("ALTER TABLE telegram_bot_user DROP COLUMN pending_deposits")
            exec("CREATE INDEX telegram_bot_user_session_state_type ON telegram_bot_user ((session_state->>'type'))")
        }
    }
}
