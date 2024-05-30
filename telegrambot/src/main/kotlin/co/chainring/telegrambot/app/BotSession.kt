package co.chainring.telegrambot.app

import co.chainring.apps.api.model.Balance
import co.chainring.apps.api.model.Deposit
import co.chainring.apps.api.model.Order
import co.chainring.apps.api.model.Trade
import co.chainring.core.model.Address
import co.chainring.core.model.Symbol
import co.chainring.core.model.abbreviated
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.OrderStatus
import co.chainring.core.model.db.SettlementStatus
import co.chainring.core.model.db.TelegramBotUserEntity
import co.chainring.core.model.db.TelegramBotUserWalletEntity
import co.chainring.core.model.db.TelegramUserReplyType
import co.chainring.core.model.db.WalletEntity
import co.chainring.core.utils.fromFundamentalUnits
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
import java.math.BigInteger
import java.math.RoundingMode

class BotSession(private val telegramUserId: Long, private val bot: Bot) {
    private val logger = KotlinLogging.logger { }

    private val chatId = telegramUserId.toString()
    private lateinit var currentWallet: BotSessionCurrentWallet

    init {
        transaction {
            val botUser = TelegramBotUserEntity.getOrCreate(telegramUserId)
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
                    "\n${balances.baseSymbol.value}: <b>${formatAmount(balances.baseSymbol, balances.availableBaseBalance)}</b>" +
                    "\n${balances.quoteSymbol.value}: <b>${formatAmount(balances.quoteSymbol, balances.availableQuoteBalance)}</b>",
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
                val wallets = transaction {
                    TelegramBotUserEntity.getOrCreate(telegramUserId).wallets.toList()
                }
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
                val wallets = transaction {
                    TelegramBotUserEntity.getOrCreate(telegramUserId).wallets.toList()
                }
                sendMessage(
                    TextMessage(
                        text = wallets.joinToString("\n") {
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
                    "Enter amount (${formatAmountWithSymbol(balances.quoteSymbol, balances.availableQuoteBalance)} available)",
                    TelegramUserReplyType.BuyAmount,
                )
            }

            CallbackData.Sell -> {
                val balances = currentWallet.getBalances()
                sendMessageForReply(
                    "Enter amount (${formatAmountWithSymbol(balances.baseSymbol, balances.availableBaseBalance)} available)",
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
                val wallets = transaction { TelegramBotUserEntity.getOrCreate(telegramUserId).wallets.toList() }
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
                val wallet = transaction { TelegramBotUserEntity.getOrCreate(telegramUserId).wallets.toList() }
                    .find { it.address.abbreviated() == callbackData.to }
                    ?: throw RuntimeException("Invalid wallet")

                switchWallet(wallet)
                sendMainMenu()
            }

            CallbackData.DepositBase -> {
                val symbol = currentWallet.currentMarket.baseSymbol
                val walletBalance = currentWallet.getWalletBalance(symbol)
                sendMessageForReply(
                    "Enter amount (${formatAmountWithSymbol(symbol, walletBalance)} available)",
                    TelegramUserReplyType.DepositBaseAmount,
                )
            }

            CallbackData.DepositQuote -> {
                val symbol = currentWallet.currentMarket.quoteSymbol
                val walletBalance = currentWallet.getWalletBalance(symbol)
                sendMessageForReply(
                    "Enter amount (${formatAmountWithSymbol(symbol, walletBalance)} available)",
                    TelegramUserReplyType.DepositQuoteAmount,
                )
            }

            CallbackData.WithdrawBase -> {
                val walletBalance = currentWallet.getBalances()
                sendMessageForReply(
                    "Enter amount (${formatAmountWithSymbol(walletBalance.baseSymbol, walletBalance.availableBaseBalance)} available)",
                    TelegramUserReplyType.WithdrawBaseAmount,
                )
            }

            CallbackData.WithdrawQuote -> {
                val walletBalance = currentWallet.getBalances()
                sendMessageForReply(
                    "Enter amount (${formatAmountWithSymbol(walletBalance.quoteSymbol, walletBalance.availableQuoteBalance)} available)",
                    TelegramUserReplyType.WithdrawQuoteAmount,
                )
            }
        }
    }

    suspend fun handleReplyMessage(originalMessageId: Int, replyMessageId: Int, text: String) {
        val botUser = transaction {
            TelegramBotUserEntity.getOrCreate(telegramUserId)
        }

        if (originalMessageId != botUser.expectedReplyMessageId) {
            sendMessage("❌ Unexpected reply message")
        } else {
            when (val expectedReplyType = botUser.expectedReplyType) {
                TelegramUserReplyType.ImportKey -> {
                    scheduleMessageDeletion(replyMessageId)
                    try {
                        transaction {
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
                        }
                        sendMessage("✅ Your private key has been imported. Your wallet address is ${currentWallet.walletAddress.value}")
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to import key" }
                        sendMessage("❌ ${e.message}")
                    }
                }

                TelegramUserReplyType.DepositBaseAmount, TelegramUserReplyType.DepositQuoteAmount -> {
                    try {
                        val symbol = if (expectedReplyType == TelegramUserReplyType.DepositBaseAmount) {
                            currentWallet.currentMarket.baseSymbol
                        } else {
                            currentWallet.currentMarket.quoteSymbol
                        }

                        val txHash = currentWallet.deposit(text, symbol)
                        if (txHash == null) {
                            sendMessage("❌ Deposit of ${symbol.value} approval failed")
                        } else {
                            transaction {
                                botUser.addPendingDeposit(txHash)
                            }
                            sendMessage("✅ Deposit of ${symbol.value} initiated")
                            sendMessage("✅ Deposit in progress")
                        }
                    } catch (e: Exception) {
                        logger.error(e) { "Deposit failed" }
                        sendMessage("❌ ${e.message}")
                    }
                }

                TelegramUserReplyType.WithdrawBaseAmount, TelegramUserReplyType.WithdrawQuoteAmount -> {
                    val symbol = if (expectedReplyType == TelegramUserReplyType.WithdrawBaseAmount) {
                        currentWallet.currentMarket.baseSymbol
                    } else {
                        currentWallet.currentMarket.quoteSymbol
                    }

                    sendMessage(
                        currentWallet
                            .withdraw(text, symbol)
                            .fold(
                                { "❌ Withdraw failed (${it.error?.displayMessage ?: "Unknown Error"})" },
                                { "✅ Withdraw succeeded" },
                            ),
                    )
                }

                TelegramUserReplyType.BuyAmount, TelegramUserReplyType.SellAmount -> {
                    val side = if (expectedReplyType == TelegramUserReplyType.BuyAmount) {
                        OrderSide.Buy
                    } else {
                        OrderSide.Sell
                    }

                    currentWallet
                        .createOrder(text, side)
                        .onLeft {
                            sendMessage("❌ Order failed (${it.error?.displayMessage ?: "Unknown Error"})")
                        }
                }

                else -> listOf("❌ Unexpected reply")
            }
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

                currentWallet = BotSessionCurrentWallet(
                    walletEntity,
                    onBalanceUpdated = ::onBalanceUpdated,
                    onOrderCreated = ::onOderCreated,
                    onOrderUpdated = ::onOrderUpdated,
                    onTradeUpdated = ::onTradeUpdated,
                )
                currentWallet.start()
                TelegramBotUserEntity.getOrCreate(telegramUserId).currentMarketId?.value?.also {
                    currentWallet.switchCurrentMarket(it)
                }
            }
        }
    }

    private fun onBalanceUpdated(balances: List<Balance>) {
        transaction {
            TelegramBotUserEntity.getOrCreate(telegramUserId).apply {
                if (pendingDeposits.isNotEmpty()) {
                    val finalizedDeposits = currentWallet
                        .listDeposits()
                        .filter {
                            (it.status == Deposit.Status.Complete || it.status == Deposit.Status.Failed) &&
                                pendingDeposits.contains(it.txHash)
                        }

                    finalizedDeposits.forEach { deposit ->
                        removePendingDeposit(deposit.txHash)
                        when (deposit.status) {
                            Deposit.Status.Failed -> {
                                runBlocking {
                                    sendMessage("❌ Deposit of ${deposit.symbol.value} failed")
                                    sendMainMenu()
                                }
                            }
                            Deposit.Status.Complete -> {
                                runBlocking {
                                    sendMessage("✅ Deposit of ${deposit.symbol.value} completed")
                                    sendMainMenu()
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    }

    private fun onOderCreated(order: Order) {
        runBlocking {
            sendMessage(formatOrder(order, isCreated = true))
        }
    }

    private fun onOrderUpdated(order: Order) {
        runBlocking {
            sendMessage(formatOrder(order, isCreated = false))
        }
    }

    private fun formatOrder(order: Order, isCreated: Boolean = false): String {
        val market = currentWallet.config.markets.first { it.id == order.marketId }
        val status = when (order.status) {
            OrderStatus.Filled -> "filled"
            OrderStatus.Partial -> "partially filled (${formatAmountWithSymbol(market.baseSymbol, order.executions.first().amount)})"
            OrderStatus.Failed, OrderStatus.Rejected -> "rejected"
            OrderStatus.Cancelled -> "cancelled"
            OrderStatus.Open -> if (isCreated) "opened" else "updated"
            OrderStatus.Expired -> "expired"
        }
        val executionPrice = when (order.status) {
            OrderStatus.Partial, OrderStatus.Filled -> "at price of ${order.executions.first().price.setScale(6, RoundingMode.FLOOR).toPlainString()}"
            else -> ""
        }
        val emoji = when (order.status) {
            OrderStatus.Partial, OrderStatus.Filled, OrderStatus.Open -> "✅"
            else -> "❌"
        }
        return "$emoji Order to ${order.side} ${formatAmountWithSymbol(market.baseSymbol, order.amount)} $status $executionPrice"
    }

    private fun onTradeUpdated(trade: Trade) {
        if (trade.settlementStatus == SettlementStatus.Completed) {
            val market = currentWallet.config.markets.first { it.id == trade.marketId }
            runBlocking {
                sendMessage("✅ Trade to ${trade.side} ${formatAmountWithSymbol(market.baseSymbol, trade.amount)} has settled")
                sendMainMenu()
            }
        }
    }

    private fun switchMarket(marketId: MarketId) {
        currentWallet.switchCurrentMarket(marketId)
        transaction {
            TelegramBotUserEntity.getOrCreate(telegramUserId).updateMarket(marketId)
        }
    }

    private fun scheduleMessageDeletion(messageId: Int) {
        transaction {
            TelegramBotUserEntity.getOrCreate(telegramUserId).messageIdsForDeletion += messageId
        }
    }

    private fun deleteMessages() {
        transaction {
            runBlocking {
                TelegramBotUserEntity.getOrCreate(telegramUserId).messageIdsForDeletion.forEach {
                    bot.deleteMessage(chatId, it)
                }
            }
            TelegramBotUserEntity.getOrCreate(telegramUserId).messageIdsForDeletion = emptyList()
        }
    }

    private fun showMenuAfterReply(): Boolean {
        return transaction {
            when (TelegramBotUserEntity.getOrCreate(telegramUserId).expectedReplyType) {
                TelegramUserReplyType.ImportKey, TelegramUserReplyType.WithdrawBaseAmount, TelegramUserReplyType.WithdrawQuoteAmount -> true
                else -> false
            }
        }
    }

    private suspend fun sendMessage(message: String) =
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
                TelegramBotUserEntity.getOrCreate(telegramUserId).apply {
                    this.expectedReplyMessageId = it.result!!.messageId
                    this.expectedReplyType = replyType
                }
            }
        }
    }

    private fun formatAmountWithSymbol(symbol: Symbol, amount: BigInteger): String {
        return "${formatAmount(symbol, amount)} ${symbol.value}"
    }

    private fun formatAmount(symbol: Symbol, amount: BigInteger): String {
        return amount.fromFundamentalUnits(decimals(symbol)).setScale(minOf(decimals(symbol), 8), RoundingMode.FLOOR).toPlainString()
    }

    private fun decimals(symbol: Symbol) =
        currentWallet.symbolInfoBySymbol.getValue(symbol).decimals.toInt()
}

fun callbackButton(text: String, callbackData: CallbackData): InlineKeyboardItem =
    InlineKeyboardItem(text = text, callbackData = callbackData.serialize())
