package co.chainring.telegrambot.app

import co.chainring.core.model.Address
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.TelegramBotUserEntity
import co.chainring.core.model.db.TelegramBotUserWalletEntity
import co.chainring.core.model.db.TelegramBotUserWalletId
import co.chainring.core.model.db.WalletEntity
import com.github.ehsannarmani.bot.Bot
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.transactions.transaction
import org.web3j.crypto.Credentials
import org.web3j.crypto.Keys

enum class ReplyType {
    None,
    ImportKey,
    BuyAmount,
    SellAmount,
    DepositBaseAmount,
    DepositQuoteAmount,
    WithdrawBaseAmount,
    WithdrawQuoteAmount,
}

data class BotWallet(
    val id: TelegramBotUserWalletId,
    val address: Address,
    val isCurrent: Boolean,
)

data class BotMarket(
    val id: MarketId,
    val isCurrent: Boolean,
)

class BotSession(private val botUserEntity: TelegramBotUserEntity, val bot: Bot) {

    private val logger = KotlinLogging.logger { }

    val id = botUserEntity.telegramUserId.toString()
    private var expectedReplyMessageId: Int? = null
    private var waitingForReplyType: ReplyType = ReplyType.None
    private var currentWalletId: TelegramBotUserWalletId? = null
    lateinit var userWallet: TelegramUserWallet
    val messageIdsForDeletion = mutableListOf<Int>()

    fun loadUserWallet(): TelegramUserWallet {
        return if (this::userWallet.isInitialized) {
            currentWalletId?.let { walletId ->
                if (userWallet.walletId != walletId) {
                    userWallet.stop()
                    val walletEntity = transaction {
                        TelegramBotUserWalletEntity[walletId].also {
                            TelegramBotUserWalletEntity.makeCurrent(it)
                        }
                    }
                    startWallet(walletEntity)
                }
            }
            userWallet
        } else {
            transaction {
                val currentWallet = TelegramBotUserWalletEntity.findCurrentForTelegramBotUser(botUserEntity)
                if (currentWallet != null) {
                    currentWalletId = currentWallet.id.value
                    startWallet(currentWallet)
                } else {
                    val newWallet = createNewWallet()
                    currentWalletId = newWallet.id.value
                    startWallet(newWallet)
                }
            }
        }
    }

    fun switchWallet(id: TelegramBotUserWalletId) {
        currentWalletId = id
        loadUserWallet()
    }

    private fun startWallet(wallet: TelegramBotUserWalletEntity): TelegramUserWallet {
        return TelegramUserWallet(
            wallet.id.value,
            this,
            Credentials.create(wallet.encryptedPrivateKey.decrypt()).ecKeyPair,
            bot,
        ).also { telegramUserWallet ->
            telegramUserWallet.start()
            transaction {
                botUserEntity.currentMarketId?.value
            }?.let {
                telegramUserWallet.switchCurrentMarket(it)
            }
            userWallet = telegramUserWallet
        }
    }

    private fun importWallet(privateKey: String): Address {
        val creds = Credentials.create(privateKey)
        val address = Address(Keys.toChecksumAddress(creds.address))
        val botWalletEntity = transaction {
            val wallet = WalletEntity.getOrCreate(address)
            TelegramBotUserWalletEntity.create(wallet, botUserEntity, privateKey, false)
        }
        switchWallet(botWalletEntity.id.value)
        return address
    }

    private fun createNewWallet(): TelegramBotUserWalletEntity {
        val privateKey = Keys.createEcKeyPair().privateKey.toString(16)
        return transaction {
            val creds = Credentials.create(privateKey)
            val address = Address(Keys.toChecksumAddress(creds.address))
            val wallet = WalletEntity.getOrCreate(address)
            TelegramBotUserWalletEntity.create(wallet, botUserEntity, privateKey, true)
        }
    }

    fun getWallets(): List<BotWallet> {
        return transaction {
            TelegramBotUserWalletEntity.findForTelegramBotUser(botUserEntity).map {
                BotWallet(
                    it.id.value,
                    Address(Keys.toChecksumAddress(Credentials.create(it.encryptedPrivateKey.decrypt()).address)),
                    it.id.value == currentWalletId,
                )
            }
        }
    }

    fun getPrivateKey(botWalletId: TelegramBotUserWalletId): String {
        return transaction {
            TelegramBotUserWalletEntity[botWalletId].encryptedPrivateKey.decrypt()
        }
    }

    fun setExpectReplyMessage(messageId: Int, replyType: ReplyType) {
        expectedReplyMessageId = messageId
        waitingForReplyType = replyType
    }

    fun deleteMessages() {
        runBlocking {
            messageIdsForDeletion.forEach {
                bot.deleteMessage(id, it)
            }
        }
        messageIdsForDeletion.clear()
    }

    fun handleReplyMessage(replyingToMessageId: Int, text: String, messageId: Int): String? {
        if (replyingToMessageId != expectedReplyMessageId) {
            return "❌ Unexpected reply message"
        }
        return when (waitingForReplyType) {
            ReplyType.ImportKey -> {
                messageIdsForDeletion.add(messageId)
                runUpdate {
                    val address = importWallet(text)
                    "✅ Your private key has been imported. Your wallet address is ${address.value}"
                }
            }
            ReplyType.DepositBaseAmount -> {
                runUpdate {
                    userWallet.depositBase(text)
                    "✅ Deposit in progress"
                }
            }

            ReplyType.DepositQuoteAmount -> {
                runUpdate {
                    userWallet.depositQuote(text)
                    "✅ Deposit in progress"
                }
            }

            ReplyType.WithdrawBaseAmount -> {
                val result = userWallet.withdrawBase(text)
                if (result.isRight()) {
                    "✅ Withdraw succeeded"
                } else {
                    result.leftOrNull()?.let {
                        "❌ Withdraw failed (${it.error?.displayMessage ?: "Unknown Error"})"
                    }
                }
            }

            ReplyType.WithdrawQuoteAmount -> {
                val result = userWallet.withdrawQuote(text)
                if (result.isRight()) {
                    "✅ Withdraw succeeded"
                } else {
                    result.leftOrNull()?.let {
                        "❌ Withdraw failed (${it.error?.displayMessage ?: "Unknown Error"})"
                    }
                }
            }

            ReplyType.BuyAmount -> {
                val result = userWallet.createOrder(text, OrderSide.Buy)
                result.leftOrNull()?.let {
                    "❌ Order failed (${it.error?.displayMessage ?: "Unknown Error"})"
                }
            }

            ReplyType.SellAmount -> {
                val result = userWallet.createOrder(text, OrderSide.Sell)
                result.leftOrNull()?.let {
                    "❌ Order failed (${it.error?.displayMessage ?: "Unknown Error"})"
                }
            }

            else -> "❌ Unexpected reply"
        }
    }

    fun showMenuAfterReply(): Boolean {
        return when (waitingForReplyType) {
            ReplyType.ImportKey, ReplyType.WithdrawBaseAmount, ReplyType.WithdrawQuoteAmount -> true
            else -> false
        }
    }

    private fun runUpdate(logic: () -> String): String {
        return try {
            logic()
        } catch (e: Exception) {
            logger.error(e) { "error is ${e.message}" }
            "❌ ${e.message}"
        }
    }

    fun getMarkets(): List<BotMarket> {
        return userWallet.config.markets.map {
            BotMarket(
                MarketId(it.id),
                it.id == userWallet.currentMarket.id,
            )
        }
    }

    fun switchMarket(marketId: MarketId) {
        userWallet.switchCurrentMarket(marketId)
        transaction {
            botUserEntity.updateMarket(marketId)
        }
    }
}
