package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.PGEnum
import co.chainring.core.model.db.TelegramBotUserId
import co.chainring.core.model.db.enumDeclaration
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V34_AddSessionStateToTelegramBotUser : Migration() {
    object V34_MarketTable : GUIDTable<MarketId>(
        "market",
        ::MarketId,
    )

    enum class V34_TelegramUserReplyType {
        None,
        ImportKey,
        BuyAmount,
        SellAmount,
        DepositBaseAmount,
        DepositQuoteAmount,
        WithdrawBaseAmount,
        WithdrawQuoteAmount,
    }

    object V34_TelegramBotUserTable : GUIDTable<TelegramBotUserId>("telegram_bot_user", ::TelegramBotUserId) {
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val telegramUserId = long("telegram_user_id").uniqueIndex()
        val currentMarketGuid = reference("current_market_guid", V34_MarketTable).nullable()
        val expectedReplyMessageId = integer("expected_reply_message_id").nullable()
        val expectedReplyType = customEnumeration(
            "expected_reply_type",
            "TelegramUserReplyType",
            { value -> V34_TelegramUserReplyType.valueOf(value as String) },
            { PGEnum("TelegramUserReplyType", it) },
        ).default(V34_TelegramUserReplyType.None)
        val messageIdsForDeletion = array<Int>("message_ids_for_deletion").default(emptyList())
    }

    override fun run() {
        transaction {
            exec("CREATE TYPE TelegramUserReplyType AS ENUM (${enumDeclaration<V34_TelegramUserReplyType>()})")
            SchemaUtils.createMissingTablesAndColumns(V34_TelegramBotUserTable)
        }
    }
}
