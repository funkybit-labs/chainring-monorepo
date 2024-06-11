package co.chainring.telegrambot.app

import co.chainring.core.model.Symbol

sealed class CallbackData {
    data object Settings : CallbackData()
    data object ImportWallet : CallbackData()
    data object ExportPrivateKey : CallbackData()
    data object SwitchWallet : CallbackData()
    data class WalletSelected(val abbreviatedAddress: String) : CallbackData()
    data object Deposit : CallbackData()
    data object Withdraw : CallbackData()
    data object Swap : CallbackData()
    data object Airdrop : CallbackData()
    data class SymbolSelected(val symbol: Symbol) : CallbackData()
    data object ChangeAmount : CallbackData()
    data object Confirm : CallbackData()
    data object Cancel : CallbackData()

    fun serialize(): String =
        when (this) {
            Settings -> "Settings"
            ImportWallet -> "ImportWallet"
            is ExportPrivateKey -> "ExportPrivateKey"
            is SwitchWallet -> "SwitchWallet"
            is WalletSelected -> "WalletSelected $abbreviatedAddress"
            Deposit -> "Deposit"
            Withdraw -> "Withdraw"
            Swap -> "Swap"
            is Airdrop -> "Airdrop"
            is SymbolSelected -> "SymbolSelected ${symbol.value}"
            is ChangeAmount -> "ChangeAmount"
            is Confirm -> "Confirm"
            is Cancel -> "Cancel"
        }

    companion object {
        fun deserialize(s: String): CallbackData? {
            val words = s.split(" ")
            if (words.isEmpty()) return null

            return when (words[0]) {
                "Settings" -> Settings
                "ImportWallet" -> ImportWallet
                "ExportPrivateKey" -> ExportPrivateKey
                "SwitchWallet" -> SwitchWallet
                "WalletSelected" -> WalletSelected(words[1])
                "Deposit" -> Deposit
                "Withdraw" -> Withdraw
                "Swap" -> Swap
                "Airdrop" -> Airdrop
                "SymbolSelected" -> SymbolSelected(Symbol(words[1]))
                "ChangeAmount" -> ChangeAmount
                "Confirm" -> Confirm
                "Cancel" -> Cancel
                else -> null
            }
        }
    }
}
