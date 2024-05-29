package co.chainring.telegrambot.app

import co.chainring.core.model.Address
import co.chainring.core.model.abbreviated
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.TelegramBotUserEntity
import co.chainring.core.model.db.TelegramBotUserWalletEntity
import co.chainring.core.model.db.TelegramUserReplyType
import co.chainring.core.model.db.WalletEntity
import co.chanring.core.model.encrypt
import com.github.ehsannarmani.bot.Bot
import com.github.ehsannarmani.bot.model.message.TextMessage
import com.github.ehsannarmani.bot.model.message.keyboard.ForceReply
import com.github.ehsannarmani.bot.model.message.keyboard.InlineKeyboard
import com.github.ehsannarmani.bot.model.message.keyboard.inline.InlineKeyboardItem
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.transactions.transaction
import org.web3j.crypto.Keys

class BotSession(private val telegramUserId: Long, val bot: Bot) {
    private val logger = KotlinLogging.logger { }

    val chatId = telegramUserId.toString()
    private lateinit var currentWallet: BotSessionCurrentWallet

    private val botUser: TelegramBotUserEntity
        get() = TelegramBotUserEntity.getOrCreate(telegramUserId)

    init {
        transaction {
            if (botUser.wallets.empty()) {
                val privateKey = Keys.createEcKeyPair().privateKey.toString(16)
                switchWallet(
                    TelegramBotUserWalletEntity.create(
                        WalletEntity.getOrCreate(Address.fromPrivateKey(privateKey)),
                        botUser,
                        privateKey.encrypt(),
                        isCurrent = true,
                    ).also {
                        it.flush()
                    },
                )
            } else {
                switchWallet(
                    botUser.wallets.find { it.isCurrent }
                        ?: botUser.wallets.first(),
                )
            }
        }
    }

    suspend fun sendMainMenu() {
        val balances = currentWallet.getBalances()
        deleteMessages()
        sendMessage(
            TextMessage(
                text = "-- Current Wallet --" +
                    "\n<code>${currentWallet.walletAddress.value}</code>" +
                    "\n\n-- Current Market --" +
                    "\n<code>${balances.baseSymbol.value + "/" + balances.quoteSymbol.value}</code>" +
                    "\nMarket Price: <b>${(currentWallet.getMarketPrice()?.toPlainString() ?: "Unknown") + " " + balances.quoteSymbol.value}</b>" +
                    "\n\n-- Available Balances --" +
                    "\n${balances.baseSymbol.value}: <b>${balances.availableBaseBalance}</b>" +
                    "\n${balances.quoteSymbol.value}: <b>${balances.availableQuoteBalance}</b>",
                chatId = chatId,
                parseMode = "HTML",
                keyboard = InlineKeyboard(
                    listOf(
                        listOf(
                            callbackButton("Buy ${balances.baseSymbol.value}", CallbackData.Buy),
                            callbackButton("Sell ${balances.baseSymbol.value}", CallbackData.Sell),
                        ),
                        listOf(
                            callbackButton("Switch Markets", CallbackData.ListMarkets),
                            callbackButton("Switch Wallets", CallbackData.ListWallets),
                        ),
                        listOf(
                            callbackButton("Deposit ${balances.baseSymbol.value}", CallbackData.DepositBase),
                            callbackButton("Deposit ${balances.quoteSymbol.value}", CallbackData.DepositQuote),
                        ),
                        listOf(
                            callbackButton("Withdraw ${balances.baseSymbol.value}", CallbackData.WithdrawBase),
                            callbackButton("Withdraw ${balances.quoteSymbol.value}", CallbackData.WithdrawQuote),
                        ),
                        listOf(
                            callbackButton("Settings", CallbackData.Settings),
                        ),
                    ),
                ),
            ),
        )
    }

    suspend fun handleCallbackButtonClick(callbackData: CallbackData) {
        when (callbackData) {
            CallbackData.MainMenu -> {
                sendMainMenu()
            }

            CallbackData.Settings -> {
                val wallets = transaction { botUser.wallets.toList() }
                deleteMessages()
                sendMessage(
                    TextMessage(
                        text = "Use this menu to change or view settings",
                        chatId = chatId,
                        keyboard = InlineKeyboard(
                            listOf(
                                listOf(
                                    // TODO - this should be done by standing up a web page. Then when they click on the button, telegram pops
                                    // up a window and navigates to that web page. That page has an input box for the private key and would
                                    // submit to our backend. This way the private key does not flow through telegram servers or appear in
                                    // the chat window. Same applies for viewing the private key below. For now they would paste it into
                                    // the reply box here.
                                    callbackButton("Import Wallet", CallbackData.ImportWallet),
                                ),
                                listOf(
                                    callbackButton("Show Addresses", CallbackData.ShowAddresses),
                                ),
                            ) + wallets.map { wallet ->
                                val abbreviateAddress = wallet.address.abbreviated()
                                listOf(
                                    callbackButton("Show Private Key for $abbreviateAddress", CallbackData.ShowPrivateKey(abbreviateAddress)),
                                )
                            } + listOf(
                                listOf(
                                    callbackButton("Main Menu", CallbackData.MainMenu),
                                ),
                            ),
                        ),
                    ),
                )?.result?.messageId?.also { id -> scheduleMessageDeletion(id) }
            }

            CallbackData.ImportWallet -> {
                sendMessageForReply(
                    "Import the private key of an existing wallet",
                    TelegramUserReplyType.ImportKey,
                    "Paste key here",
                )
            }

            CallbackData.ShowAddresses -> {
                sendMessage(
                    TextMessage(
                        text = transaction { botUser.wallets.toList() }.joinToString("\n") {
                            "<code>${it.address.value}</code>"
                        },
                        chatId = chatId,
                        parseMode = "HTML",
                    ),
                )
            }

            is CallbackData.ShowPrivateKey -> {
                sendMessage(
                    TextMessage(
                        text = "<code>${currentWallet.wallet.encryptedPrivateKey.decrypt()}</code>",
                        chatId = chatId,
                        parseMode = "HTML",
                    ),
                )?.result?.messageId?.also { id -> scheduleMessageDeletion(id) }
            }

            CallbackData.Buy -> {
                val balances = currentWallet.getBalances()
                sendMessageForReply(
                    "Enter amount (${balances.availableQuoteBalance} ${balances.quoteSymbol.value} available)",
                    TelegramUserReplyType.BuyAmount,
                )
            }

            CallbackData.Sell -> {
                val balances = currentWallet.getBalances()
                sendMessageForReply(
                    "Enter amount (${balances.availableBaseBalance} ${balances.baseSymbol.value} available)",
                    TelegramUserReplyType.SellAmount,
                )
            }

            CallbackData.ListMarkets -> {
                val markets = currentWallet.config.markets
                sendMessage(
                    TextMessage(
                        text = "Press the appropriate button to switch markets",
                        chatId = chatId,
                        keyboard = InlineKeyboard(
                            markets.map { market ->
                                listOf(
                                    callbackButton("${if (market == currentWallet.currentMarket) "✔ " else ""}${market.id.value}", CallbackData.SwitchMarket(market.id.value)),
                                )
                            },
                        ),
                    ),
                )?.result?.messageId?.let { id -> scheduleMessageDeletion(id) }
            }

            is CallbackData.SwitchMarket -> {
                switchMarket(MarketId(callbackData.to))
                sendMainMenu()
            }

            CallbackData.ListWallets -> {
                val wallets = transaction { botUser.wallets.toList() }
                sendMessage(
                    TextMessage(
                        text = "Press the appropriate button to switch wallets",
                        chatId = chatId,
                        keyboard = InlineKeyboard(
                            wallets.map { wallet ->
                                val abbreviateAddress = wallet.address.abbreviated()
                                listOf(
                                    callbackButton("${if (wallet.isCurrent) "✔ " else ""}$abbreviateAddress", CallbackData.SwitchWallet(abbreviateAddress)),
                                )
                            },
                        ),
                    ),
                )?.result?.messageId?.let { id -> scheduleMessageDeletion(id) }
            }

            is CallbackData.SwitchWallet -> {
                val wallet = transaction { botUser.wallets.toList() }
                    .find { it.address.abbreviated() == callbackData.to }
                    ?: throw RuntimeException("Invalid wallet")

                switchWallet(wallet)
                sendMainMenu()
            }

            CallbackData.DepositBase -> {
                val balances = currentWallet.getBalances()
                val walletBalance = currentWallet.getFormattedWalletBalance(balances.baseSymbol)
                sendMessageForReply(
                    "Enter amount ($walletBalance ${balances.baseSymbol.value} available)",
                    TelegramUserReplyType.DepositBaseAmount,
                )
            }

            CallbackData.DepositQuote -> {
                val balances = currentWallet.getBalances()
                val walletBalance = currentWallet.getFormattedWalletBalance(balances.quoteSymbol)
                sendMessageForReply(
                    "Enter amount ($walletBalance ${balances.quoteSymbol.value} available)",
                    TelegramUserReplyType.DepositQuoteAmount,
                )
            }

            CallbackData.WithdrawBase -> {
                val walletBalance = currentWallet.getBalances()
                sendMessageForReply(
                    "Enter amount (${walletBalance.availableBaseBalance} ${walletBalance.baseSymbol.value} available)",
                    TelegramUserReplyType.WithdrawBaseAmount,
                )
            }

            CallbackData.WithdrawQuote -> {
                val walletBalance = currentWallet.getBalances()
                sendMessageForReply(
                    "Enter amount (${walletBalance.availableQuoteBalance} ${walletBalance.quoteSymbol.value} available)",
                    TelegramUserReplyType.WithdrawQuoteAmount,
                )
            }
        }
    }

    suspend fun handleReplyMessage(originalMessageId: Int, replyMessageId: Int, text: String) {
        val messageToSend = transaction {
            if (originalMessageId != botUser.expectedReplyMessageId) {
                return@transaction "❌ Unexpected reply message"
            }
            when (botUser.expectedReplyType) {
                TelegramUserReplyType.ImportKey -> {
                    scheduleMessageDeletion(replyMessageId)
                    runUpdate {
                        val privateKey = text
                        switchWallet(
                            TelegramBotUserWalletEntity.create(
                                WalletEntity.getOrCreate(Address.fromPrivateKey(privateKey)),
                                botUser,
                                privateKey.encrypt(),
                                isCurrent = true,
                            ).also {
                                it.flush()
                            },
                        )
                        "✅ Your private key has been imported. Your wallet address is ${currentWallet.walletAddress.value}"
                    }
                }

                TelegramUserReplyType.DepositBaseAmount -> {
                    runUpdate {
                        currentWallet.depositBase(text)
                        "✅ Deposit in progress"
                    }
                }

                TelegramUserReplyType.DepositQuoteAmount -> {
                    runUpdate {
                        currentWallet.depositQuote(text)
                        "✅ Deposit in progress"
                    }
                }

                TelegramUserReplyType.WithdrawBaseAmount -> {
                    currentWallet
                        .withdrawBase(text)
                        .fold(
                            { "❌ Withdraw failed (${it.error?.displayMessage ?: "Unknown Error"})" },
                            { "✅ Withdraw succeeded" },
                        )
                }

                TelegramUserReplyType.WithdrawQuoteAmount -> {
                    currentWallet
                        .withdrawQuote(text)
                        .fold(
                            { "❌ Withdraw failed (${it.error?.displayMessage ?: "Unknown Error"})" },
                            { "✅ Withdraw succeeded" },
                        )
                }

                TelegramUserReplyType.BuyAmount -> {
                    currentWallet
                        .createOrder(text, OrderSide.Buy)
                        .fold(
                            { "❌ Order failed (${it.error?.displayMessage ?: "Unknown Error"})" },
                            { null },
                        )
                }

                TelegramUserReplyType.SellAmount -> {
                    currentWallet
                        .createOrder(text, OrderSide.Sell)
                        .fold(
                            { "❌ Order failed (${it.error?.displayMessage ?: "Unknown Error"})" },
                            { null },
                        )
                }

                else -> "❌ Unexpected reply"
            }
        }

        if (messageToSend != null) {
            sendMessage(messageToSend)
        }

        if (showMenuAfterReply()) {
            sendMainMenu()
        }
    }

    private fun switchWallet(walletEntity: TelegramBotUserWalletEntity) {
        if (!this::currentWallet.isInitialized || currentWallet.wallet.id != walletEntity.id) {
            if (this::currentWallet.isInitialized) {
                currentWallet.stop()
                currentWallet.wallet.isCurrent = false
            }

            transaction {
                walletEntity.isCurrent = true

                currentWallet = BotSessionCurrentWallet(walletEntity, this@BotSession)
                currentWallet.start()
                botUser.currentMarketId?.value?.also {
                    currentWallet.switchCurrentMarket(it)
                }
            }
        }
    }

    private fun switchMarket(marketId: MarketId) {
        currentWallet.switchCurrentMarket(marketId)
        transaction {
            botUser.updateMarket(marketId)
        }
    }

    private fun scheduleMessageDeletion(messageId: Int) {
        transaction {
            botUser.messageIdsForDeletion += messageId
        }
    }

    private fun deleteMessages() {
        transaction {
            runBlocking {
                botUser.messageIdsForDeletion.forEach {
                    bot.deleteMessage(chatId, it)
                }
            }
            botUser.messageIdsForDeletion = emptyList()
        }
    }

    private fun showMenuAfterReply(): Boolean {
        return transaction {
            when (botUser.expectedReplyType) {
                TelegramUserReplyType.ImportKey, TelegramUserReplyType.WithdrawBaseAmount, TelegramUserReplyType.WithdrawQuoteAmount -> true
                else -> false
            }
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

    suspend fun sendMessage(message: String) =
        sendMessage(
            TextMessage(
                text = message,
                chatId = chatId,
            ),
        )

    private suspend fun sendMessage(message: TextMessage) =
        bot.sendMessage(message)

    private suspend fun sendMessageForReply(
        text: String,
        replyType: TelegramUserReplyType,
        placeHolder: String = text,
    ) {
        bot.sendMessage(
            TextMessage(
                text = text,
                chatId = chatId,
                keyboard = ForceReply(forceReply = true, inputFieldPlaceholder = placeHolder),
            ),
        ).also {
            if (replyType == TelegramUserReplyType.ImportKey) {
                it?.result?.messageId?.let { id -> scheduleMessageDeletion(id) }
            }
        }?.let {
            transaction {
                botUser.apply {
                    this.expectedReplyMessageId = it.result!!.messageId
                    this.expectedReplyType = replyType
                }
            }
        }
    }
}

fun callbackButton(text: String, callbackData: CallbackData): InlineKeyboardItem =
    InlineKeyboardItem(text = text, callbackData = callbackData.serialize())
