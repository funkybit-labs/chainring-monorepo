package co.chainring.core.model.db

import co.chainring.core.model.TxHash
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.http4k.format.KotlinxSerialization
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

@Serializable
enum class TelegramUserReplyType {
    None,
    ImportKey,
    BuyAmount,
    SellAmount,
    DepositBaseAmount,
    DepositQuoteAmount,
    WithdrawBaseAmount,
    WithdrawQuoteAmount,
}

@Serializable
@JvmInline
value class TelegramBotUserId(override val value: String) : EntityId {
    companion object {
        fun generate(telegramUserId: Long): TelegramBotUserId = TelegramBotUserId("tbuser_$telegramUserId")
    }

    override fun toString(): String = value
}

object TelegramBotUserTable : GUIDTable<TelegramBotUserId>("telegram_bot_user", ::TelegramBotUserId) {
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
    val telegramUserId = long("telegram_user_id").uniqueIndex()
    val currentMarketGuid = reference("current_market_guid", MarketTable).nullable()
    val expectedReplyMessageId = integer("expected_reply_message_id").nullable()
    val expectedReplyType = customEnumeration(
        "expected_reply_type",
        "TelegramUserReplyType",
        { value -> TelegramUserReplyType.valueOf(value as String) },
        { PGEnum("TelegramUserReplyType", it) },
    ).default(TelegramUserReplyType.None)
    val messageIdsForDeletion = array<Int>("message_ids_for_deletion").default(emptyList())
    val pendingDeposits = jsonb<List<TxHash>>("pending_deposits", KotlinxSerialization.json).default(emptyList())
}

class TelegramBotUserEntity(guid: EntityID<TelegramBotUserId>) : GUIDEntity<TelegramBotUserId>(guid) {
    companion object : EntityClass<TelegramBotUserId, TelegramBotUserEntity>(TelegramBotUserTable) {
        fun getOrCreate(telegramUserId: Long): TelegramBotUserEntity {
            return getByTelegramUserId(telegramUserId)
                ?: TelegramBotUserEntity.new(TelegramBotUserId.generate(telegramUserId)) {
                    this.telegramUserId = telegramUserId
                    this.createdAt = Clock.System.now()
                    this.createdBy = "telegramBot"
                }
        }

        private fun getByTelegramUserId(telegramUserId: Long): TelegramBotUserEntity? {
            return TelegramBotUserEntity.find {
                TelegramBotUserTable.telegramUserId.eq(telegramUserId)
            }.firstOrNull()
        }
    }

    fun updateMarket(marketId: MarketId) {
        this.currentMarketId = EntityID(marketId, MarketTable)
    }

    var createdAt by TelegramBotUserTable.createdAt
    var createdBy by TelegramBotUserTable.createdBy
    var telegramUserId by TelegramBotUserTable.telegramUserId
    var currentMarketId by TelegramBotUserTable.currentMarketGuid
    var expectedReplyMessageId by TelegramBotUserTable.expectedReplyMessageId
    var expectedReplyType by TelegramBotUserTable.expectedReplyType
    var messageIdsForDeletion by TelegramBotUserTable.messageIdsForDeletion
    var pendingDeposits by TelegramBotUserTable.pendingDeposits
    val wallets by TelegramBotUserWalletEntity referrersOn TelegramBotUserWalletTable.telegrambotUserGuid

    fun addPendingDeposit(txHash: TxHash) {
        this.pendingDeposits += txHash
    }

    fun removePendingDeposit(txHash: TxHash) {
        this.pendingDeposits -= txHash
    }
}
