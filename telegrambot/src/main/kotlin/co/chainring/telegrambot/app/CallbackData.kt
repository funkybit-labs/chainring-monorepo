package co.chainring.telegrambot.app

import co.chainring.core.model.Symbol

sealed class CallbackData {
    data object MainMenu : CallbackData()
    data object Settings : CallbackData()
    data object ImportWallet : CallbackData()
    data object ShowAddresses : CallbackData()
    data class ShowPrivateKey(val forWallet: String) : CallbackData()
    data object ListWallets : CallbackData()
    data class SwitchWallet(val to: String) : CallbackData()
    data object ListMarkets : CallbackData()
    data class SwitchMarket(val to: String) : CallbackData()
    data object Buy : CallbackData()
    data object Sell : CallbackData()
    data object DepositBase : CallbackData()
    data object DepositQuote : CallbackData()
    data object WithdrawBase : CallbackData()
    data object WithdrawQuote : CallbackData()
    data class Airdrop(val symbol: Symbol) : CallbackData()

    fun serialize(): String =
        when (this) {
            MainMenu -> "MainMenu"
            Settings -> "Settings"
            ImportWallet -> "ImportWallet"
            ShowAddresses -> "ShowAddresses"
            is ShowPrivateKey -> "ShowPrivateKey $forWallet"
            is ListWallets -> "ListWallets"
            is SwitchWallet -> "SwitchWallet $to"
            is ListMarkets -> "ListMarkets"
            is SwitchMarket -> "SwitchMarket $to"
            is Buy -> "Buy"
            is Sell -> "Sell"
            DepositBase -> "DepositBase"
            DepositQuote -> "DepositQuote"
            WithdrawBase -> "WithdrawBase"
            WithdrawQuote -> "WithdrawQuote"
            is Airdrop -> "Airdrop ${symbol.value}"
        }

    companion object {
        fun deserialize(s: String): CallbackData? {
            val words = s.split(" ")
            if (words.isEmpty()) return null

            return when (words[0]) {
                "MainMenu" -> MainMenu
                "Settings" -> Settings
                "ImportWallet" -> ImportWallet
                "ShowAddresses" -> ShowAddresses
                "ShowPrivateKey" -> ShowPrivateKey(words[1])
                "ListWallets" -> ListWallets
                "SwitchWallet" -> SwitchWallet(words[1])
                "ListMarkets" -> ListMarkets
                "SwitchMarket" -> SwitchMarket(words[1])
                "Buy" -> Buy
                "Sell" -> Sell
                "DepositBase" -> DepositBase
                "DepositQuote" -> DepositQuote
                "WithdrawBase" -> WithdrawBase
                "WithdrawQuote" -> WithdrawQuote
                "Airdrop" -> Airdrop(Symbol(words[1]))
                else -> null
            }
        }
    }
}
