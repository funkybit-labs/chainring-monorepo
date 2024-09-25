package xyz.funkybit.core.model.telegram.bot

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.http4k.format.KotlinxSerialization
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.web3j.crypto.Keys
import xyz.funkybit.core.db.executeRaw
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.db.EntityId
import xyz.funkybit.core.model.db.GUIDEntity
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.WalletEntity
import xyz.funkybit.core.model.encrypt
import xyz.funkybit.core.model.telegram.TelegramUserId

@Serializable
@JvmInline
value class TelegramBotUserId(override val value: String) : EntityId {
    companion object {
        fun generate(telegramUserId: TelegramUserId): TelegramBotUserId = TelegramBotUserId("tbuser_${telegramUserId.value}")
    }

    override fun toString(): String = value
}

object TelegramBotUserTable : GUIDTable<TelegramBotUserId>("telegram_bot_user", ::TelegramBotUserId) {
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
    val updatedAt = timestamp("updated_at")
    val telegramUserId = long("telegram_user_id").uniqueIndex()
    val messageIdsForDeletion = array<Int>("message_ids_for_deletion").default(emptyList())
    val sessionState = jsonb<SessionState>("session_state", KotlinxSerialization.json).default(
        SessionState.Initial,
    )
}

class TelegramBotUserEntity(guid: EntityID<TelegramBotUserId>) : GUIDEntity<TelegramBotUserId>(guid) {
    companion object : EntityClass<TelegramBotUserId, TelegramBotUserEntity>(
        TelegramBotUserTable,
    ) {
        fun getOrCreate(telegramUserId: TelegramUserId): TelegramBotUserEntity {
            return getByTelegramUserId(telegramUserId)
                ?: TelegramBotUserEntity.new(
                    TelegramBotUserId.generate(
                        telegramUserId,
                    ),
                ) {
                    this.telegramUserId = telegramUserId
                    this.createdAt = Clock.System.now()
                    this.updatedAt = this.createdAt
                    this.createdBy = "telegramBot"
                }.also { user ->
                    user.flush()
                    val privateKey = Keys.createEcKeyPair().privateKey.toString(16)
                    val wallet = TelegramBotUserWalletEntity.create(
                        WalletEntity.getOrCreateWithUser(
                            EvmAddress.fromPrivateKey(
                                privateKey,
                            ),
                        ).first,
                        user,
                        privateKey.encrypt(),
                        isCurrent = true,
                    )
                    wallet.flush()
                }
        }

        private fun getByTelegramUserId(telegramUserId: TelegramUserId): TelegramBotUserEntity? {
            return TelegramBotUserEntity.find {
                TelegramBotUserTable.telegramUserId.eq(telegramUserId.value)
            }.firstOrNull()
        }

        fun leastRecentlyUpdatedWithPendingSession(): TelegramBotUserEntity? =
            TransactionManager.current().executeRaw(
                """
                    SELECT ${TelegramBotUserTable.columns.joinToString(",") { it.name }}
                    FROM ${TelegramBotUserTable.tableName} 
                    WHERE  ${TelegramBotUserTable.sessionState.name}->>'type' in ('AirdropPending', 'DepositPending', 'WithdrawalPending', 'SwapPending')
                    ORDER BY ${TelegramBotUserTable.updatedAt.name} ASC
                    LIMIT 1
                """.trimIndent(),
                TelegramBotUserEntity,
            ).firstOrNull()
    }

    var createdAt by TelegramBotUserTable.createdAt
    var createdBy by TelegramBotUserTable.createdBy
    var updatedAt by TelegramBotUserTable.updatedAt
    var telegramUserId by TelegramBotUserTable.telegramUserId.transform(
        toReal = { TelegramUserId(it) },
        toColumn = { it.value },
    )
    var messageIdsForDeletion by TelegramBotUserTable.messageIdsForDeletion.transform(
        toReal = { it.map { msgId -> TelegramMessageId(msgId) } },
        toColumn = { it.map { msgId -> msgId.value } },
    )
    val wallets by TelegramBotUserWalletEntity referrersOn TelegramBotUserWalletTable.telegrambotUserGuid

    var sessionState by TelegramBotUserTable.sessionState

    fun currentWallet(): TelegramBotUserWalletEntity =
        wallets.first { it.isCurrent }

    fun updateSessionState(newState: SessionState) {
        sessionState = newState
        updatedAt = Clock.System.now()
    }

    fun addWallet(privateKey: String): TelegramBotUserWalletEntity {
        return TelegramBotUserWalletEntity.create(
            // TODO do we really need to create a user here?
            WalletEntity.getOrCreateWithUser(EvmAddress.fromPrivateKey(privateKey)).first,
            this,
            privateKey.encrypt(),
            isCurrent = false,
        ).also {
            it.flush()
        }
    }
}
