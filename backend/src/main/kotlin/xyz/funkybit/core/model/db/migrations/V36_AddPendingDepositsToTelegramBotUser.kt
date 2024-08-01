package xyz.funkybit.core.model.db.migrations

import org.http4k.format.KotlinxSerialization
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.PGEnum
import xyz.funkybit.core.model.telegram.bot.TelegramBotUserId

@Suppress("ClassName")
class V36_AddPendingDepositsToTelegramBotUser : Migration() {
    object V36_MarketTable : GUIDTable<MarketId>(
        "market",
        ::MarketId,
    )

    enum class V36_TelegramUserReplyType {
        None,
        ImportKey,
        BuyAmount,
        SellAmount,
        DepositBaseAmount,
        DepositQuoteAmount,
        WithdrawBaseAmount,
        WithdrawQuoteAmount,
    }

    object V36_TelegramBotUserTable : GUIDTable<TelegramBotUserId>("telegram_bot_user", ::TelegramBotUserId) {
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val telegramUserId = long("telegram_user_id").uniqueIndex()
        val currentMarketGuid = reference("current_market_guid", V36_MarketTable).nullable()
        val expectedReplyMessageId = integer("expected_reply_message_id").nullable()
        val expectedReplyType = customEnumeration(
            "expected_reply_type",
            "TelegramUserReplyType",
            { value -> V36_TelegramUserReplyType.valueOf(value as String) },
            { PGEnum("TelegramUserReplyType", it) },
        ).default(V36_TelegramUserReplyType.None)
        val messageIdsForDeletion = array<Int>("message_ids_for_deletion").default(emptyList())
        val pendingDeposits = jsonb<List<TxHash>>("pending_deposits", KotlinxSerialization.json).default(emptyList())
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V36_TelegramBotUserTable)
        }
    }
}
