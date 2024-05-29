package co.chainring.core.model.db

import co.chainring.core.model.Address
import co.chanring.core.model.EncryptedString
import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

@Serializable
@JvmInline
value class TelegramBotUserWalletId(override val value: String) : EntityId {
    companion object {
        fun generate(): TelegramBotUserWalletId = TelegramBotUserWalletId(TypeId.generate("tbuw").toString())
    }

    override fun toString(): String = value
}

object TelegramBotUserWalletTable : GUIDTable<TelegramBotUserWalletId>("telegram_bot_user_wallet", ::TelegramBotUserWalletId) {
    val walletGuid = reference("wallet_guid", WalletTable).index()
    val telegrambotUserGuid = reference("telegram_bot_user_guid", TelegramBotUserTable).index()
    val encryptedPrivateKey = varchar("encrypted_private_key", 10485760)
    val isCurrent = bool("is_current")
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
}

class TelegramBotUserWalletEntity(guid: EntityID<TelegramBotUserWalletId>) : GUIDEntity<TelegramBotUserWalletId>(guid) {
    companion object : EntityClass<TelegramBotUserWalletId, TelegramBotUserWalletEntity>(TelegramBotUserWalletTable) {
        fun create(
            wallet: WalletEntity,
            telegramBotUser: TelegramBotUserEntity,
            privateKey: EncryptedString,
            isCurrent: Boolean,
        ) = TelegramBotUserWalletEntity.new(TelegramBotUserWalletId.generate()) {
            val now = Clock.System.now()
            this.encryptedPrivateKey = privateKey
            this.createdAt = now
            this.createdBy = telegramBotUser.guid.value.value
            this.walletGuid = wallet.guid
            this.telegramBotUserGuid = telegramBotUser.guid
            this.isCurrent = isCurrent
        }
    }

    var walletGuid by TelegramBotUserWalletTable.walletGuid
    var wallet by WalletEntity referencedOn WithdrawalTable.walletGuid
    var telegramBotUserGuid by TelegramBotUserWalletTable.telegrambotUserGuid
    var telegramBotUser by TelegramBotUserEntity referencedOn TelegramBotUserWalletTable.telegrambotUserGuid
    var createdAt by TelegramBotUserWalletTable.createdAt
    var createdBy by TelegramBotUserWalletTable.createdBy
    var encryptedPrivateKey by TelegramBotUserWalletTable.encryptedPrivateKey.transform(
        toReal = { EncryptedString(it) },
        toColumn = { it.encrypted },
    )
    var isCurrent by TelegramBotUserWalletTable.isCurrent

    val address: Address
        get() = Address.fromPrivateKey(encryptedPrivateKey.decrypt())
}
