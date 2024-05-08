package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.TelegramBotUserId
import co.chainring.core.model.db.TelegramBotUserTable.nullable
import co.chainring.core.model.db.TelegramBotUserWalletId
import co.chainring.core.model.db.WalletId
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V27_TelegramBotTables : Migration() {

    object V27_WalletTable : GUIDTable<WalletId>("wallet", ::WalletId)

    object V27_MarketTable : GUIDTable<MarketId>("market", ::MarketId)

    object V27_TelegramBotUserTable : GUIDTable<TelegramBotUserId>(
        "telegram_bot_user",
        ::TelegramBotUserId,
    ) {
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val telegramUserId = long("telegram_user_id").uniqueIndex()
        val currentMarketGuid = reference("current_market_guid", V27_MarketTable).nullable()
    }

    object V27_TelegramBotUserWalletTable : GUIDTable<TelegramBotUserWalletId>(
        "telegram_bot_user_wallet",
        ::TelegramBotUserWalletId,
    ) {
        val walletGuid = reference("wallet_guid", V27_WalletTable).index()
        val telegrambotUserGuid = reference("telegram_bot_user_guid", V27_TelegramBotUserTable).index()
        val encryptedPrivateKey = varchar("encrypted_private_key", 10485760)
        val isCurrent = bool("is_current")
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V27_TelegramBotUserTable, V27_TelegramBotUserWalletTable)
        }
    }
}
