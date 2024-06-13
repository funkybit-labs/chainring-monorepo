package co.chainring.telegrambot.app

import co.chainring.core.model.Symbol
import co.chainring.core.model.db.DepositEntity
import co.chainring.core.model.db.OrderEntity
import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.model.tgbot.TelegramMessageId
import co.chainring.core.model.tgbot.TelegramUserId
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
}
