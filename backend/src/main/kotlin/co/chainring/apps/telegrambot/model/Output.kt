package co.chainring.apps.telegrambot.model

import co.chainring.apps.api.model.ApiError
import co.chainring.core.model.Address
import co.chainring.core.model.Symbol
import co.chainring.core.model.abbreviated
import co.chainring.core.model.db.BalanceEntity
import co.chainring.core.model.db.BalanceType
import co.chainring.core.model.db.DepositEntity
import co.chainring.core.model.db.SymbolEntity
import co.chainring.core.model.db.TelegramBotUserEntity
import co.chainring.core.model.telegrambot.TelegramMessageId
import co.chainring.core.model.telegrambot.TelegramUserId
import co.chainring.core.utils.fromFundamentalUnits
import co.chanring.core.model.EncryptedString
import org.web3j.protocol.core.methods.response.TransactionReceipt
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.math.max

sealed class Output {
    data class SendMessage(
        val recipient: TelegramUserId,
        val text: String,
        val parseMode: String = "markdown",
        val keyboard: Keyboard? = null,
    ) : Output() {
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
    ) : Output()
}

object OutputMessage {
    fun mainMenu(
        recipient: TelegramBotUserEntity,
        currentWallet: Address,
        walletOnChainBalances: List<Pair<SymbolEntity, BigDecimal>>,
        exchangeBalances: List<BalanceEntity>,
        airdropSupported: Boolean,
    ): Output.SendMessage {
        val nonZeroWalletBalances = walletOnChainBalances.filter { it.second > BigDecimal.ZERO }
        val nonZeroExchangeAvailableBalances = exchangeBalances.filter { it.type == BalanceType.Available && it.balance > BigInteger.ZERO }
        return Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "-- Current Wallet --" +
                "\n<code>${currentWallet.value}</code>" +
                if (nonZeroWalletBalances.isNotEmpty()) {
                    "\n\n-- Wallet Balances --" +
                        nonZeroWalletBalances.joinToString { wb ->
                            "\n${wb.first.name}: <b>${formatAmount(wb.second)}</b>"
                        }
                } else {
                    ""
                } +
                if (nonZeroExchangeAvailableBalances.isNotEmpty()) {
                    "\n\n-- Available Balances --" +
                        nonZeroExchangeAvailableBalances.joinToString { eb ->
                            "\n${eb.symbol.name}: <b>${
                                formatAmount(
                                    eb.balance,
                                    eb.symbol,
                                )
                            }</b>"
                        }
                } else {
                    ""
                },
            parseMode = "HTML",
            keyboard = Output.SendMessage.Keyboard.Inline(
                listOfNotNull(
                    if (airdropSupported) {
                        listOf(
                            callbackButton("Airdrop", CallbackData.Airdrop),
                        )
                    } else {
                        null
                    },
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

    private sealed class SelectSymbolPurpose {
        data object Airdrop : SelectSymbolPurpose()
        data object Deposit : SelectSymbolPurpose()
        data object Withdraw : SelectSymbolPurpose()
        data object SwapFrom : SelectSymbolPurpose()
        data class SwapTo(val from: SymbolEntity) : SelectSymbolPurpose()
    }

    private fun selectSymbol(recipient: TelegramBotUserEntity, symbols: List<SymbolEntity>, purpose: SelectSymbolPurpose): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "Please select a token to ${
                when (purpose) {
                    SelectSymbolPurpose.Airdrop -> "airdrop"
                    SelectSymbolPurpose.Deposit -> "deposit"
                    SelectSymbolPurpose.Withdraw -> "withdraw"
                    SelectSymbolPurpose.SwapFrom -> "swap"
                    is SelectSymbolPurpose.SwapTo -> "swap your ${purpose.from.name} to"
                }
            }:",
            keyboard = Output.SendMessage.Keyboard.Inline(
                symbols.chunked(2).map { chunk ->
                    chunk.map { symbol ->
                        callbackButton(
                            symbol.name,
                            CallbackData.SymbolSelected(Symbol(symbol.name)),
                        )
                    }
                } + listOf(
                    listOf(
                        callbackButton("Cancel", CallbackData.Cancel),
                    ),
                ),
            ),
        )

    fun selectSymbolToAirdrop(recipient: TelegramBotUserEntity, symbols: List<SymbolEntity>): Output.SendMessage =
        selectSymbol(recipient, symbols, SelectSymbolPurpose.Airdrop)

    fun selectSymbolToDeposit(recipient: TelegramBotUserEntity, symbols: List<SymbolEntity>): Output.SendMessage =
        selectSymbol(recipient, symbols, SelectSymbolPurpose.Deposit)

    fun selectSymbolToWithdraw(recipient: TelegramBotUserEntity, symbols: List<SymbolEntity>): Output.SendMessage =
        selectSymbol(recipient, symbols, SelectSymbolPurpose.Withdraw)

    fun selectSymbolToSwapFrom(recipient: TelegramBotUserEntity, symbols: List<SymbolEntity>): Output.SendMessage =
        selectSymbol(recipient, symbols, SelectSymbolPurpose.SwapFrom)

    fun selectSymbolToSwapTo(recipient: TelegramBotUserEntity, symbols: List<SymbolEntity>, from: SymbolEntity): Output.SendMessage =
        selectSymbol(recipient, symbols, SelectSymbolPurpose.SwapTo(from))

    fun airdropRequested(recipient: TelegramBotUserEntity, symbol: SymbolEntity, amount: BigDecimal): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "✅ Airdropping ${ formatAmountWithSymbol(amount, symbol) } to your wallet",
        )

    fun airdropSucceeded(recipient: TelegramBotUserEntity, symbol: SymbolEntity, amount: BigDecimal): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "✅ ${ formatAmountWithSymbol(amount, symbol) } was airdropped to your wallet",
        )

    fun airdropTxFailed(recipient: TelegramBotUserEntity, receipt: TransactionReceipt): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "❌ Airdrop transaction ${receipt.transactionHash} has failed",
        )

    fun noBalanceInWallet(recipient: TelegramBotUserEntity, symbol: Symbol): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "You dont have any ${symbol.value} in your wallet",
        )

    fun enterDepositAmount(recipient: TelegramBotUserEntity, symbol: SymbolEntity, available: BigDecimal): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "You have ${ formatAmountWithSymbol(available, symbol) } in your wallet. How much would you like to deposit?",
        )

    fun depositAmountTooLarge(recipient: TelegramBotUserEntity): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "❌ The amount you've entered exceeds your wallet balance",
        )

    fun depositConfirmation(recipient: TelegramBotUserEntity, symbol: SymbolEntity, amount: BigDecimal): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "You are about to deposit ${ formatAmountWithSymbol(amount, symbol) }. Would you like to proceed?",
            keyboard = Output.SendMessage.Keyboard.Inline(
                listOf(
                    listOf(
                        callbackButton("Confirm", CallbackData.Confirm),
                        callbackButton("Cancel", CallbackData.Cancel),
                    ),
                    listOf(
                        callbackButton(
                            "Change amount",
                            CallbackData.ChangeAmount,
                        ),
                    ),
                ),
            ),
        )

    fun invalidNumber(recipient: TelegramBotUserEntity, text: String): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "❌ $text is not a valid number, please try again",
        )

    fun amountHasToBePositive(recipient: TelegramBotUserEntity): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "❌ Amount has to be greater than zero, please try again",
        )

    fun depositInProgress(recipient: TelegramBotUserEntity): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "✅ Deposit in progress",
        )

    fun depositFailed(recipient: TelegramBotUserEntity, symbol: SymbolEntity): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "❌ Deposit of ${symbol.name} has failed",
        )

    fun depositSucceeded(recipient: TelegramBotUserEntity, deposit: DepositEntity, availableBalance: BigDecimal): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "✅ Deposit of ${ formatAmountWithSymbol(deposit.amount.fromFundamentalUnits(deposit.symbol.decimals), deposit.symbol) } has completed. Your available balance is: ${ formatAmountWithSymbol(availableBalance, deposit.symbol) }",
        )

    fun noBalanceAvailable(recipient: TelegramBotUserEntity, symbol: SymbolEntity): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "You dont have any ${symbol.name} available",
        )

    fun enterWithdrawalAmount(recipient: TelegramBotUserEntity, symbol: SymbolEntity, available: BigDecimal): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "You have ${ formatAmountWithSymbol(available, symbol) } available. How much would you like to withdraw?",
        )

    fun withdrawalAmountTooLarge(recipient: TelegramBotUserEntity): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "❌ The amount you've entered exceeds your available balance",
        )

    fun withdrawalConfirmation(recipient: TelegramBotUserEntity, symbol: SymbolEntity, amount: BigDecimal): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "You are about to withdraw ${ formatAmountWithSymbol(amount, symbol) }. Would you like to proceed?",
            keyboard = Output.SendMessage.Keyboard.Inline(
                listOf(
                    listOf(
                        callbackButton("Confirm", CallbackData.Confirm),
                        callbackButton("Cancel", CallbackData.Cancel),
                    ),
                    listOf(
                        callbackButton(
                            "Change amount",
                            CallbackData.ChangeAmount,
                        ),
                    ),
                ),
            ),
        )

    fun withdrawalFailed(recipient: TelegramBotUserEntity, apiError: ApiError?): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "❌ Withdrawal failed (${apiError?.displayMessage ?: "Unknown Error"})",
        )

    fun withdrawalInProgress(recipient: TelegramBotUserEntity): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "✅ Withdrawal in progress",
        )

    fun withdrawalSucceeded(recipient: TelegramBotUserEntity): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "✅ Withdrawal succeeded",
        )

    fun enterSwapAmount(recipient: TelegramBotUserEntity, from: SymbolEntity, available: BigDecimal, to: SymbolEntity): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "You have ${ formatAmountWithSymbol(available, from) } available. How much are you willing to swap to ${to.name}?",
        )

    fun swapAmountTooLarge(recipient: TelegramBotUserEntity): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "❌ The amount you've entered exceeds your available balance",
        )

    fun swapEstimateError(recipient: TelegramBotUserEntity, error: SwapEstimateError): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "❌ ${error.message ?: "Something went wrong"}",
        )

    fun swapConfirmation(recipient: TelegramBotUserEntity, estimation: SwapEstimation): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "By swapping ${ formatAmountWithSymbol(estimation.fromAmount, estimation.from) } " +
                "you will get ${ formatAmountWithSymbol(estimation.toAmount, estimation.to) } at the price of " +
                "1 ${estimation.from.name} ≈ ${ formatAmountWithSymbol(estimation.price, estimation.to) }. " +
                "Would you like to proceed?",
            keyboard = Output.SendMessage.Keyboard.Inline(
                listOf(
                    listOf(
                        callbackButton("Confirm", CallbackData.Confirm),
                        callbackButton("Cancel", CallbackData.Cancel),
                    ),
                    listOf(
                        callbackButton(
                            "Change amount",
                            CallbackData.ChangeAmount,
                        ),
                    ),
                ),
            ),
        )

    fun submittingSwap(recipient: TelegramBotUserEntity, estimation: SwapEstimation): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "Swapping ${ formatAmountWithSymbol(estimation.fromAmount, estimation.from) } " +
                "to ${ formatAmountWithSymbol(estimation.toAmount, estimation.to) } at the price of " +
                "1 ${estimation.from.name} ≈ ${ formatAmountWithSymbol(estimation.price, estimation.to) }",
        )

    fun swapFailed(recipient: TelegramBotUserEntity, apiError: ApiError?): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "❌ Swap failed (${apiError?.displayMessage ?: "Unknown Error"})",
        )

    fun swapSucceeded(recipient: TelegramBotUserEntity, full: Boolean): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "✅ Swap succeeded${if (!full) " (partial fill)" else ""}",
        )

    fun swapRejected(recipient: TelegramBotUserEntity): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "❌ Swap rejected",
        )

    fun invalidCommand(recipient: TelegramBotUserEntity): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "❌ Invalid command",
        )

    fun settings(recipient: TelegramBotUserEntity, currentWallet: Address): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "-- Current Wallet --" +
                "\n<code>${currentWallet.value}</code>" +
                "\n\nUse this menu to change or view settings",
            parseMode = "HTML",
            keyboard = Output.SendMessage.Keyboard.Inline(
                listOf(
                    listOf(
                        callbackButton(
                            "Switch wallet",
                            CallbackData.SwitchWallet,
                        ),
                    ),
                    listOf(
                        // TODO: CHAIN-170
                        // this should be done by standing up a web page. Then when they click on the button, telegram pops
                        // up a window and navigates to that web page. That page has an input box for the private key and would
                        // submit to our backend. This way the private key does not flow through telegram servers or appear in
                        // the chat window. Same applies for viewing the private key below. For now they would paste it into
                        // the reply box here.
                        callbackButton(
                            "Import wallet",
                            CallbackData.ImportWallet,
                        ),
                    ),
                    listOf(
                        callbackButton(
                            "Export private key",
                            CallbackData.ExportPrivateKey,
                        ),
                    ),
                    listOf(
                        callbackButton(
                            "Back to main menu",
                            CallbackData.Cancel,
                        ),
                    ),
                ),
            ),
        )

    fun selectWalletToSwitchTo(recipient: TelegramBotUserEntity, wallets: List<Address>, currentWallet: Address): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "Please select a wallet to switch to:",
            keyboard = Output.SendMessage.Keyboard.Inline(
                wallets.map { address ->
                    val abbreviateAddress = address.abbreviated()
                    listOf(
                        callbackButton(
                            "${if (address == currentWallet) "✔ " else ""}$abbreviateAddress",
                            CallbackData.WalletSelected(abbreviateAddress),
                        ),
                    )
                } + listOf(
                    listOf(
                        callbackButton("Cancel", CallbackData.Cancel),
                    ),
                ),
            ),
        )

    fun switchedToWallet(recipient: TelegramBotUserEntity, wallet: Address): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "Switched to wallet ${wallet.value}",
        )

    fun importWalletPrivateKeyEntryPrompt(recipient: TelegramBotUserEntity): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "To import your existing wallet please send your private key in the next message or /cancel to abort",
        )

    fun importWalletSuccess(recipient: TelegramBotUserEntity, newWallet: Address): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "✅ Your private key has been imported. Your wallet address is ${newWallet.value}",
        )

    fun importWalletFailure(recipient: TelegramBotUserEntity): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "❌ Failed to import key",
        )

    fun showPrivateKey(recipient: TelegramBotUserEntity, privateKey: EncryptedString): Output.SendMessage =
        Output.SendMessage(
            recipient = recipient.telegramUserId,
            text = "<code>${privateKey.decrypt()}</code>",
            parseMode = "HTML",
            keyboard = Output.SendMessage.Keyboard.Inline(
                listOf(
                    listOf(
                        callbackButton("Back to settings", CallbackData.Cancel),
                    ),
                ),
            ),
        )

    private fun formatAmountWithSymbol(amount: BigDecimal, symbol: SymbolEntity): String =
        "${formatAmount(amount)} ${symbol.name}"

    private fun formatAmount(amount: BigInteger, symbol: SymbolEntity): String =
        formatAmount(amount.fromFundamentalUnits(symbol.decimals))

    private fun formatAmount(amount: BigDecimal): String =
        amount.setScale(max(4, amount.scale() - amount.precision() + 4), RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    private fun callbackButton(text: String, callbackData: CallbackData): Output.SendMessage.Keyboard.Inline.CallbackButton =
        Output.SendMessage.Keyboard.Inline.CallbackButton(
            text = text,
            data = callbackData,
        )
}
