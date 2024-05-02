package co.chainring.telegrambot.app

import co.chainring.core.model.abbreviated
import com.github.ehsannarmani.bot.Bot
import com.github.ehsannarmani.bot.model.message.TextMessage
import com.github.ehsannarmani.bot.model.message.keyboard.ForceReply
import com.github.ehsannarmani.bot.model.message.keyboard.InlineKeyboard
import com.github.ehsannarmani.bot.model.message.keyboard.inline.InlineKeyboardItem

suspend fun Bot.showSettings(session: BotSession) {
    val wallets = session.getWallets()
    session.deleteMessages()
    sendMessage(
        TextMessage(
            text = "Use this menu to change or view settings",
            chatId = session.id,
            keyboard = InlineKeyboard(
                listOf(
                    listOf(
                        // TODO - this should be done by standing up a web page. Then when they click on the button, telegram pops
                        // up a window and navigates to that web page. That page has an input box for the private key and would
                        // submit to our backend. This way the private key does not flow through telegram servers or appear in
                        // the chat window. Same applies for viewing the private key below. For now they would paste it into
                        // the reply box here.
                        InlineKeyboardItem("Import Wallet", callbackData = "Import Wallet").onCLick {
                            sendMessageForReply(
                                session,
                                "Import the private key of an existing wallet",
                                ReplyType.ImportKey,
                                "Paste key here",
                            )
                        },
                    ),
                    listOf(
                        InlineKeyboardItem("Show Addresses", callbackData = "Show Addresses").onCLick {
                            showAddresses(session)
                        },
                    ),
                ) + wallets.map { wallet ->
                    val abbreviateAddress = wallet.address.abbreviated()
                    listOf(
                        InlineKeyboardItem("Show Private Key for $abbreviateAddress", callbackData = "Show Private Key for $abbreviateAddress").onCLick {
                            showPrivateKey(session, wallet)
                        },
                    )
                } + listOf(
                    listOf(
                        InlineKeyboardItem("Main Menu", callbackData = "Main Menu").onCLick {
                            session.deleteMessages()
                            mainMenu(session)
                        },
                    ),
                ),
            ),
        ),
    ).also {
        it?.result?.messageId?.let { id -> session.messageIdsForDeletion.add(id) }
    }
}

suspend fun Bot.showSwitchWallets(session: BotSession) {
    val wallets = session.getWallets()
    sendMessage(
        TextMessage(
            text = "Press the appropriate button to switch wallets",
            chatId = session.id,
            keyboard = InlineKeyboard(
                wallets.map { wallet ->
                    val abbreviateAddress = wallet.address.abbreviated()
                    listOf(
                        InlineKeyboardItem("${if (wallet.isCurrent) "✔ " else ""}$abbreviateAddress", callbackData = "Select $abbreviateAddress").onCLick {
                            session.switchWallet(wallet.id)
                            mainMenu(session)
                        },
                    )
                },
            ),
        ),
    ).also {
        it?.result?.messageId?.let { id -> session.messageIdsForDeletion.add(id) }
    }
}

suspend fun Bot.showSwitchMarkets(session: BotSession) {
    val markets = session.getMarkets()
    sendMessage(
        TextMessage(
            text = "Press the appropriate button to switch markets",
            chatId = session.id,
            keyboard = InlineKeyboard(
                markets.map { market ->
                    listOf(
                        InlineKeyboardItem("${if (market.isCurrent) "✔ " else ""}${market.id.value}", callbackData = "Select ${market.id}").onCLick {
                            session.switchMarket(market.id)
                            mainMenu(session)
                        },
                    )
                },
            ),
        ),
    ).also {
        it?.result?.messageId?.let { id -> session.messageIdsForDeletion.add(id) }
    }
}

suspend fun Bot.showAddresses(session: BotSession) {
    val wallets = session.getWallets()
    sendMessage(
        TextMessage(
            text = wallets.joinToString("\n") {
                "<code>${it.address.value}</code>"
            },
            chatId = session.id,
            parseMode = "HTML",
        ),
    )
}

suspend fun Bot.showPrivateKey(session: BotSession, botWallet: BotWallet) {
    sendMessage(
        TextMessage(
            text = "<code>${session.getPrivateKey(botWallet.id)}</code>",
            chatId = session.id,
            parseMode = "HTML",
        ),
    ).also {
        it?.result?.messageId?.let { id -> session.messageIdsForDeletion.add(id) }
    }
}

suspend fun Bot.sendMessageForReply(
    session: BotSession,
    text: String,
    replyType: ReplyType,
    placeHolder: String = text,
) {
    sendMessage(
        TextMessage(
            text = text,
            chatId = session.id,
            keyboard = ForceReply(forceReply = true, inputFieldPlaceholder = placeHolder),
        ),
    ).also {
        if (replyType == ReplyType.ImportKey) {
            it?.result?.messageId?.let { id -> session.messageIdsForDeletion.add(id) }
        }
    }?.let {
        session.setExpectReplyMessage(it.result!!.messageId, replyType)
    }
}

suspend fun Bot.mainMenu(session: BotSession) {
    val balances = session.userWallet.getBalances()
    session.deleteMessages()
    sendMessage(
        TextMessage(
            text = "-- Current Wallet --" +
                "\n<code>${session.userWallet.walletAddress.value}</code>" +
                "\n\n-- Current Market --" +
                "\n<code>${balances.baseSymbol.value + "/" + balances.quoteSymbol.value}</code>" +
                "\nMarket Price: <b>${session.userWallet.getMarketPrice() + " " + balances.quoteSymbol.value}</b>" +
                "\n\n-- Available Balances --" +
                "\n${balances.baseSymbol.value}: <b>${balances.availableBaseBalance}</b>" +
                "\n${balances.quoteSymbol.value}: <b>${balances.availableQuoteBalance}</b>",
            chatId = session.id,
            parseMode = "HTML",
            keyboard = InlineKeyboard(
                listOf(
                    listOf(
                        InlineKeyboardItem("Buy ${balances.baseSymbol.value}", callbackData = "Buy ${balances.baseSymbol.value}").onCLick {
                            val availableBalance = session.userWallet.getBalances().availableQuoteBalance
                            sendMessageForReply(
                                session,
                                "Enter amount ($availableBalance ${balances.quoteSymbol.value} available)",
                                ReplyType.BuyAmount,
                            )
                        },
                        InlineKeyboardItem("Sell ${balances.baseSymbol.value}", callbackData = "Sell ${balances.baseSymbol.value}").onCLick {
                            val availableBalance = session.userWallet.getBalances().availableBaseBalance
                            sendMessageForReply(
                                session,
                                "Enter amount ($availableBalance ${balances.baseSymbol.value} available)",
                                ReplyType.SellAmount,
                            )
                        },
                    ),
                    listOf(
                        InlineKeyboardItem("Switch Markets", callbackData = "Switch Market").onCLick {
                            showSwitchMarkets(session)
                        },
                        InlineKeyboardItem("Switch Wallets", callbackData = "Switch Wallets").onCLick {
                            showSwitchWallets(session)
                        },
                    ),
                    listOf(
                        InlineKeyboardItem("Deposit ${balances.baseSymbol.value}", callbackData = "Deposit ${balances.baseSymbol.value}").onCLick {
                            val walletBalance = session.userWallet.getFormattedWalletBalance(balances.baseSymbol)
                            sendMessageForReply(
                                session,
                                "Enter amount ($walletBalance ${balances.baseSymbol.value} available)",
                                ReplyType.DepositBaseAmount,
                            )
                        },
                        InlineKeyboardItem("Deposit ${balances.quoteSymbol.value}", callbackData = "Deposit ${balances.quoteSymbol.value}").onCLick {
                            val walletBalance = session.userWallet.getFormattedWalletBalance(balances.quoteSymbol)
                            sendMessageForReply(
                                session,
                                "Enter amount ($walletBalance ${balances.quoteSymbol.value} available)",
                                ReplyType.DepositQuoteAmount,
                            )
                        },
                    ),
                    listOf(
                        InlineKeyboardItem("Withdraw ${balances.baseSymbol.value}", callbackData = "Withdraw ${balances.baseSymbol.value}").onCLick {
                            val walletBalance = session.userWallet.getBalances()
                            sendMessageForReply(
                                session,
                                "Enter amount (${walletBalance.availableBaseBalance} ${balances.baseSymbol.value} available)",
                                ReplyType.WithdrawBaseAmount,
                            )
                        },
                        InlineKeyboardItem("Withdraw ${balances.quoteSymbol.value}", callbackData = "Withdraw ${balances.quoteSymbol.value}").onCLick {
                            val walletBalance = session.userWallet.getBalances()
                            sendMessageForReply(
                                session,
                                "Enter amount (${walletBalance.availableQuoteBalance} ${balances.quoteSymbol.value} available)",
                                ReplyType.WithdrawQuoteAmount,
                            )
                        },
                    ),
                    listOf(
                        InlineKeyboardItem("Settings", callbackData = "Settings").onCLick {
                            showSettings(session)
                        },
                    ),
                ),
            ),
        ),
    )
}
