package co.chainring.telegrambot.app

import co.chainring.core.model.Symbol
import co.chainring.core.model.db.DepositEntity
import co.chainring.core.model.db.OrderEntity
import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.model.tgbot.TelegramMessageId
import co.chainring.core.model.tgbot.TelegramUserId
import org.telegram.telegrambots.meta.api.objects.Update
import org.web3j.protocol.core.methods.response.TransactionReceipt

sealed class BotInput {
    abstract val from: TelegramUserId

    data class Start(override val from: TelegramUserId) : BotInput()
    data class Airdrop(override val from: TelegramUserId) : BotInput()
    data class SymbolSelected(override val from: TelegramUserId, val symbol: Symbol) : BotInput()
    data class Text(override val from: TelegramUserId, val text: String, val messageId: TelegramMessageId) : BotInput()
    data class Confirm(override val from: TelegramUserId) : BotInput()
    data class Cancel(override val from: TelegramUserId) : BotInput()
    data class ChangeAmount(override val from: TelegramUserId) : BotInput()
    data class AirdropTxReceipt(override val from: TelegramUserId, val receipt: TransactionReceipt) : BotInput()
    data class Deposit(override val from: TelegramUserId) : BotInput()
    data class DepositCompleted(override val from: TelegramUserId, val deposit: DepositEntity) : BotInput()
    data class Withdraw(override val from: TelegramUserId) : BotInput()
    data class WithdrawalCompleted(override val from: TelegramUserId, val withdrawal: WithdrawalEntity) : BotInput()
    data class Swap(override val from: TelegramUserId) : BotInput()
    data class SwapCompleted(override val from: TelegramUserId, val order: OrderEntity) : BotInput()
    data class Settings(override val from: TelegramUserId) : BotInput()
    data class ImportWallet(override val from: TelegramUserId) : BotInput()
    data class SwitchWallet(override val from: TelegramUserId) : BotInput()
    data class WalletSelected(override val from: TelegramUserId, val abbreviatedAddress: String) : BotInput()
    data class ExportPrivateKey(override val from: TelegramUserId) : BotInput()

    companion object {
        fun fromUpdate(update: Update): BotInput? {
            val message = update.message
            val callbackQuery = update.callbackQuery

            return if (message != null && message.hasText()) {
                if (message.text == "/start") {
                    Start(TelegramUserId(message.from.id))
                } else {
                    Text(
                        TelegramUserId(message.from.id),
                        message.text,
                        TelegramMessageId(message.messageId),
                    )
                }
            } else if (callbackQuery != null) {
                CallbackData
                    .deserialize(callbackQuery.data)
                    ?.let { callbackData ->
                        val from = TelegramUserId(callbackQuery.from.id)
                        when (callbackData) {
                            is CallbackData.Airdrop -> Airdrop(from)
                            is CallbackData.SymbolSelected -> SymbolSelected(from, callbackData.symbol)
                            is CallbackData.Confirm -> Confirm(from)
                            is CallbackData.Cancel -> Cancel(from)
                            is CallbackData.Deposit -> Deposit(from)
                            is CallbackData.ChangeAmount -> ChangeAmount(from)
                            is CallbackData.Withdraw -> Withdraw(from)
                            is CallbackData.Swap -> Swap(from)
                            is CallbackData.Settings -> Settings(from)
                            is CallbackData.ImportWallet -> ImportWallet(from)
                            is CallbackData.SwitchWallet -> SwitchWallet(from)
                            is CallbackData.WalletSelected -> WalletSelected(from, callbackData.abbreviatedAddress)
                            is CallbackData.ExportPrivateKey -> ExportPrivateKey(from)
                        }
                    }
            } else {
                null
            }
        }
    }
}
