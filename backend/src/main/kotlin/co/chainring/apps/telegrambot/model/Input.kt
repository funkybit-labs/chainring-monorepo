package co.chainring.apps.telegrambot.model

import co.chainring.core.model.Symbol
import co.chainring.core.model.db.DepositEntity
import co.chainring.core.model.db.OrderEntity
import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.model.telegram.TelegramUserId
import co.chainring.core.model.telegram.bot.TelegramMessageId
import org.web3j.protocol.core.methods.response.TransactionReceipt

sealed class Input {
    abstract val from: TelegramUserId

    data class Start(override val from: TelegramUserId) : Input()
    data class Airdrop(override val from: TelegramUserId) : Input()
    data class SymbolSelected(override val from: TelegramUserId, val symbol: Symbol) : Input()
    data class Text(override val from: TelegramUserId, val text: String, val messageId: TelegramMessageId) : Input()
    data class Confirm(override val from: TelegramUserId) : Input()
    data class Cancel(override val from: TelegramUserId) : Input()
    data class ChangeAmount(override val from: TelegramUserId) : Input()
    data class AirdropTxReceipt(override val from: TelegramUserId, val receipt: TransactionReceipt) : Input()
    data class Deposit(override val from: TelegramUserId) : Input()
    data class DepositCompleted(override val from: TelegramUserId, val deposit: DepositEntity) : Input()
    data class Withdraw(override val from: TelegramUserId) : Input()
    data class WithdrawalCompleted(override val from: TelegramUserId, val withdrawal: WithdrawalEntity) : Input()
    data class Swap(override val from: TelegramUserId) : Input()
    data class SwapCompleted(override val from: TelegramUserId, val order: OrderEntity) : Input()
    data class Settings(override val from: TelegramUserId) : Input()
    data class ImportWallet(override val from: TelegramUserId) : Input()
    data class SwitchWallet(override val from: TelegramUserId) : Input()
    data class WalletSelected(override val from: TelegramUserId, val abbreviatedAddress: String) : Input()
    data class ExportPrivateKey(override val from: TelegramUserId) : Input()
}
