package co.chainring.telegrambot.app

import co.chainring.apps.api.model.ApiError
import co.chainring.core.model.Address
import co.chainring.core.model.Symbol
import co.chainring.core.model.abbreviated
import co.chainring.core.model.tgbot.TelegramMessageId
import co.chanring.core.model.EncryptedString
import org.web3j.protocol.core.methods.response.TransactionReceipt
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.max

sealed class BotOutput {
    data class SendMessage(
        val chatId: String,
        val text: String,
        val parseMode: String = "markdown",
        val keyboard: Keyboard? = null,
    ) : BotOutput() {
        sealed class Keyboard {
            data class Inline(
                val items: List<List<CallbackButton>>,
            ) : Keyboard() {
                data class CallbackButton(
                    val text: String,
                    val data: CallbackData,
                )
            }
        }
    }

    data class DeleteMessage(
        val chatId: String,
        val messageId: TelegramMessageId,
    ) : BotOutput()
}

sealed class BotMessage {
    abstract fun render(chatId: String): BotOutput.SendMessage

    data class MainMenu(
        val currentWallet: Address,
        val walletBalances: List<WalletBalance>,
        val exchangeBalances: List<ExchangeBalance>,
    ) : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage {
            val nonZeroWalletBalances = walletBalances.filter { it.balance > BigDecimal.ZERO }
            val nonZeroExchangeBalances = exchangeBalances.filter { it.available > BigDecimal.ZERO }
            return BotOutput.SendMessage(
                chatId = chatId,
                text = "-- Current Wallet --" +
                    "\n<code>${currentWallet.value}</code>" +
                    if (nonZeroWalletBalances.isNotEmpty()) {
                        "\n\n-- Wallet Balances --" +
                            nonZeroWalletBalances.joinToString { wb ->
                                "\n${wb.symbol.value}: <b>${formatAmount(wb.balance)}</b>"
                            }
                    } else {
                        ""
                    } +
                    if (nonZeroExchangeBalances.isNotEmpty()) {
                        "\n\n-- Available Balances --" +
                            nonZeroExchangeBalances.joinToString { eb ->
                                "\n${eb.symbol.value}: <b>${formatAmount(eb.available)}</b>"
                            }
                    } else {
                        ""
                    },
                parseMode = "HTML",
                keyboard = BotOutput.SendMessage.Keyboard.Inline(
                    listOf(
                        listOf(
                            callbackButton("Airdrop", CallbackData.Airdrop),
                        ),
                        listOf(
                            callbackButton("Deposit", CallbackData.Deposit),
                        ),
                        listOf(
                            callbackButton("Withdraw", CallbackData.Withdraw),
                        ),
                        listOf(
                            callbackButton("Swap", CallbackData.Swap),
                        ),
                        listOf(
                            callbackButton("Settings", CallbackData.Settings),
                        ),
                    ),
                ),
            )
        }
    }

    data class SelectSymbol(val symbols: List<Symbol>, val purpose: Purpose) : BotMessage() {
        sealed class Purpose {
            data object Airdrop : Purpose()
            data object Deposit : Purpose()
            data object Withdraw : Purpose()
            data object SwapFrom : Purpose()
            data class SwapTo(val from: Symbol) : Purpose()
        }

        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "Please select a token to ${
                    when (purpose) {
                        Purpose.Airdrop -> "airdrop"
                        Purpose.Deposit -> "deposit"
                        Purpose.Withdraw -> "withdraw"
                        Purpose.SwapFrom -> "swap"
                        is Purpose.SwapTo -> "swap your ${purpose.from.value} to"
                    }
                }:",
                keyboard = BotOutput.SendMessage.Keyboard.Inline(
                    symbols.chunked(2).map { chunk ->
                        chunk.map { symbol ->
                            callbackButton(symbol.value, CallbackData.SymbolSelected(symbol))
                        }
                    } + listOf(
                        listOf(
                            callbackButton("Cancel", CallbackData.Cancel),
                        ),
                    ),
                ),
            )

        companion object {
            fun toAirdrop(symbols: List<Symbol>) =
                SelectSymbol(symbols, Purpose.Airdrop)

            fun toDeposit(symbols: List<Symbol>) =
                SelectSymbol(symbols, Purpose.Deposit)

            fun toWithdraw(symbols: List<Symbol>) =
                SelectSymbol(symbols, Purpose.Withdraw)

            fun toSwapFrom(symbols: List<Symbol>) =
                SelectSymbol(symbols, Purpose.SwapFrom)

            fun toSwapTo(symbols: List<Symbol>, from: Symbol) =
                SelectSymbol(symbols, Purpose.SwapTo(from))
        }
    }

    data class AirdropRequested(val symbol: Symbol, val amount: BigDecimal) : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "✅ Airdropping ${formatAmountWithSymbol(amount, symbol)} to your wallet",
            )
    }

    data class AirdropSucceeded(val symbol: Symbol, val amount: BigDecimal) : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "✅ ${formatAmountWithSymbol(amount, symbol)} was airdropped to your wallet",
            )
    }

    data class AirdropFailed(val error: ApiError?) : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "❌ Airdrop failed with error: (${error?.displayMessage ?: "Unknown Error"})",
            )
    }

    data class AirdropTxFailed(val receipt: TransactionReceipt) : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "❌ Airdrop transaction ${receipt.transactionHash} has failed",
            )
    }

    data object AirdropTimedOut : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "❌ Airdrop has timed out, please try again later",
            )
    }

    data class NoBalanceInWallet(val symbol: Symbol) : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "You dont have any ${symbol.value} in your wallet",
            )
    }

    data class EnterDepositAmount(val symbol: Symbol, val available: BigDecimal) : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "You have ${formatAmountWithSymbol(available, symbol)} in your wallet. How much would you like to deposit?",
            )
    }

    data object DepositAmountTooLarge : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "❌ The amount you've entered exceeds your wallet balance",
            )
    }

    data class DepositConfirmation(val symbol: Symbol, val amount: BigDecimal) : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "You are about to deposit ${formatAmountWithSymbol(amount, symbol)}. Would you like to proceed?",
                keyboard = BotOutput.SendMessage.Keyboard.Inline(
                    listOf(
                        listOf(
                            callbackButton("Confirm", CallbackData.Confirm),
                            callbackButton("Cancel", CallbackData.Cancel),
                        ),
                        listOf(
                            callbackButton("Change amount", CallbackData.ChangeAmount),
                        ),
                    ),
                ),
            )
    }

    data class InvalidNumber(val text: String) : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "❌ $text is not a valid number, please try again",
            )
    }

    data object DepositInProgress : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "✅ Deposit in progress",
            )
    }

    data class DepositFailed(val symbol: Symbol) : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "❌ Deposit of ${symbol.value} has failed",
            )
    }

    data class DepositSucceeded(val symbol: Symbol, val amount: BigDecimal, val availableBalance: BigDecimal) : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "✅ Deposit of ${formatAmountWithSymbol(amount, symbol)} has completed. Your available balance is: ${formatAmountWithSymbol(availableBalance, symbol)}",
            )
    }

    data class NoBalanceAvailable(val symbol: Symbol) : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "You dont have any ${symbol.value} available",
            )
    }

    data class EnterWithdrawalAmount(val symbol: Symbol, val available: BigDecimal) : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "You have ${formatAmountWithSymbol(available, symbol)} available. How much would you like to withdraw?",
            )
    }

    data object WithdrawalAmountTooLarge : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "❌ The amount you've entered exceeds your available balance",
            )
    }

    data class WithdrawalConfirmation(val symbol: Symbol, val amount: BigDecimal) : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "You are about to withdraw ${formatAmountWithSymbol(amount, symbol)}. Would you like to proceed?",
                keyboard = BotOutput.SendMessage.Keyboard.Inline(
                    listOf(
                        listOf(
                            callbackButton("Confirm", CallbackData.Confirm),
                            callbackButton("Cancel", CallbackData.Cancel),
                        ),
                        listOf(
                            callbackButton("Change amount", CallbackData.ChangeAmount),
                        ),
                    ),
                ),
            )
    }

    data class WithdrawalFailed(val apiError: ApiError?) : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "❌ Withdrawal failed (${apiError?.displayMessage ?: "Unknown Error"})",
            )
    }

    data object WithdrawalInProgress : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "✅ Withdrawal in progress",
            )
    }

    data object WithdrawalSucceeded : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "✅ Withdrawal succeeded",
            )
    }

    data class EnterSwapAmount(val from: Symbol, val available: BigDecimal, val to: Symbol) : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "You have ${formatAmountWithSymbol(available, from)} available. How much are you willing to swap to ${to.value}?",
            )
    }

    data object SwapAmountTooLarge : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "❌ The amount you've entered exceeds your available balance",
            )
    }

    data class SwapConfirmation(val estimation: SwapEstimation) : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "By swapping ${formatAmountWithSymbol(estimation.fromAmount, estimation.from)} " +
                    "you will get ${formatAmountWithSymbol(estimation.toAmount, estimation.to)} at the price of " +
                    "1 ${estimation.from.value} ≈ ${formatAmountWithSymbol(estimation.price, estimation.to)}. " +
                    "Would you like to proceed?",
                keyboard = BotOutput.SendMessage.Keyboard.Inline(
                    listOf(
                        listOf(
                            callbackButton("Confirm", CallbackData.Confirm),
                            callbackButton("Cancel", CallbackData.Cancel),
                        ),
                        listOf(
                            callbackButton("Change amount", CallbackData.ChangeAmount),
                        ),
                    ),
                ),
            )
    }

    data class SubmittingSwap(val estimation: SwapEstimation) : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "Swapping ${formatAmountWithSymbol(estimation.fromAmount, estimation.from)} " +
                    "to ${formatAmountWithSymbol(estimation.toAmount, estimation.to)} at the price of " +
                    "1 ${estimation.from.value} ≈ ${formatAmountWithSymbol(estimation.price, estimation.to)}",
            )
    }

    data class SwapFailed(val apiError: ApiError?) : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "❌ Swap failed (${apiError?.displayMessage ?: "Unknown Error"})",
            )
    }

    data class SwapSucceeded(val full: Boolean) : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "✅ Swap succeeded${if (!full) " (partial fill)" else ""}",
            )
    }

    data object SwapTimedOut : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "❌ Swap timed out",
            )
    }

    data object SwapRejected : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "❌ Swap rejected",
            )
    }

    data object InvalidCommand : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "❌ Invalid command",
            )
    }

    data object UnsupportedCommand : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "❌ This command is not supported",
            )
    }

    data class Settings(val currentWallet: Address) : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "-- Current Wallet --" +
                    "\n<code>${currentWallet.value}</code>" +
                    "\n\nUse this menu to change or view settings",
                parseMode = "HTML",
                keyboard = BotOutput.SendMessage.Keyboard.Inline(
                    listOf(
                        listOf(
                            callbackButton("Switch wallet", CallbackData.SwitchWallet),
                        ),
                        listOf(
                            // TODO: CHAIN-170
                            // this should be done by standing up a web page. Then when they click on the button, telegram pops
                            // up a window and navigates to that web page. That page has an input box for the private key and would
                            // submit to our backend. This way the private key does not flow through telegram servers or appear in
                            // the chat window. Same applies for viewing the private key below. For now they would paste it into
                            // the reply box here.
                            callbackButton("Import wallet", CallbackData.ImportWallet),
                        ),
                        listOf(
                            callbackButton("Export private key", CallbackData.ExportPrivateKey),
                        ),
                        listOf(
                            callbackButton("Back to main menu", CallbackData.Cancel),
                        ),
                    ),
                ),
            )
    }

    data class SelectWalletToSwitchTo(val wallets: List<Address>, val currentWallet: Address) : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "Please select a wallet to switch to:",
                keyboard = BotOutput.SendMessage.Keyboard.Inline(
                    wallets.map { address ->
                        val abbreviateAddress = address.abbreviated()
                        listOf(
                            callbackButton("${if (address == currentWallet) "✔ " else ""}$abbreviateAddress", CallbackData.WalletSelected(abbreviateAddress)),
                        )
                    } + listOf(
                        listOf(
                            callbackButton("Cancel", CallbackData.Cancel),
                        ),
                    ),
                ),
            )
    }

    data class SwitchedToWallet(val wallet: Address) : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "Switched to wallet ${wallet.value}",
            )
    }

    data object ImportWalletPrivateKeyEntryPrompt : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "To import your existing wallet please send your private key in the next message or /cancel to abort",
            )
    }

    data class ImportWalletSuccess(val newWallet: Address) : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "✅ Your private key has been imported. Your wallet address is ${newWallet.value}",
            )
    }

    data object ImportWalletFailure : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "❌ Failed to import key",
            )
    }

    data class ShowPrivateKey(val privateKey: EncryptedString) : BotMessage() {
        override fun render(chatId: String): BotOutput.SendMessage =
            BotOutput.SendMessage(
                chatId = chatId,
                text = "<code>${privateKey.decrypt()}</code>",
                parseMode = "HTML",
                keyboard = BotOutput.SendMessage.Keyboard.Inline(
                    listOf(
                        listOf(
                            callbackButton("Back to settings", CallbackData.Cancel),
                        ),
                    ),
                ),
            )
    }

    protected fun formatAmountWithSymbol(amount: BigDecimal, symbol: Symbol): String =
        "${formatAmount(amount)} ${symbol.value}"

    protected fun formatAmount(amount: BigDecimal): String =
        amount.setScale(max(4, amount.scale() - amount.precision() + 4), RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    protected fun callbackButton(text: String, callbackData: CallbackData): BotOutput.SendMessage.Keyboard.Inline.CallbackButton =
        BotOutput.SendMessage.Keyboard.Inline.CallbackButton(text = text, data = callbackData)
}
