package co.chainring.core.model.tgbot

import co.chainring.apps.api.model.BigDecimalJson
import co.chainring.core.model.Symbol
import co.chainring.core.model.TxHash
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.DepositId
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.WithdrawalId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
sealed class BotSessionState {
    @Serializable
    @SerialName("Initial")
    data object Initial : BotSessionState()

    @Serializable
    @SerialName("MainMenu")
    data object MainMenu : BotSessionState()

    @Serializable
    @SerialName("AirdropSymbolSelection")
    data object AirdropSymbolSelection : BotSessionState()

    @Serializable
    @SerialName("AirdropPending")
    data class AirdropPending(val symbol: Symbol, val amount: BigDecimalJson, val chainId: ChainId, val txHash: TxHash) : BotSessionState()

    @Serializable
    @SerialName("DepositSymbolSelection")
    data object DepositSymbolSelection : BotSessionState()

    @Serializable
    @SerialName("DepositAmountEntry")
    data class DepositAmountEntry(val symbol: Symbol) : BotSessionState()

    @Serializable
    @SerialName("DepositConfirmation")
    data class DepositConfirmation(val symbol: Symbol, val amount: BigDecimalJson) : BotSessionState()

    @Serializable
    @SerialName("DepositPending")
    data class DepositPending(val depositId: DepositId) : BotSessionState()

    @Serializable
    @SerialName("WithdrawalSymbolSelection")
    data object WithdrawalSymbolSelection : BotSessionState()

    @Serializable
    @SerialName("WithdrawalAmountEntry")
    data class WithdrawalAmountEntry(val symbol: Symbol) : BotSessionState()

    @Serializable
    @SerialName("WithdrawalConfirmation")
    data class WithdrawalConfirmation(val symbol: Symbol, val amount: BigDecimalJson) : BotSessionState()

    @Serializable
    @SerialName("WithdrawalPending")
    data class WithdrawalPending(val withdrawalId: WithdrawalId) : BotSessionState()

    @Serializable
    @SerialName("SwapFromSymbolSelection")
    data object SwapFromSymbolSelection : BotSessionState()

    @Serializable
    @SerialName("SwapToSymbolSelection")
    data class SwapToSymbolSelection(val from: Symbol) : BotSessionState()

    @Serializable
    @SerialName("SwapAmountEntry")
    data class SwapAmountEntry(val from: Symbol, val to: Symbol) : BotSessionState()

    @Serializable
    @SerialName("SwapConfirmation")
    data class SwapConfirmation(val from: Symbol, val to: Symbol, val amount: BigDecimalJson) : BotSessionState()

    @Serializable
    @SerialName("SwapPending")
    data class SwapPending(val orderId: OrderId) : BotSessionState()

    @Serializable
    @SerialName("Settings")
    data object Settings : BotSessionState()

    @Serializable
    @SerialName("WalletToSwitchToSelection")
    data object WalletToSwitchToSelection : BotSessionState()

    @Serializable
    @SerialName("ImportWalletPrivateKeyEntry")
    data object ImportWalletPrivateKeyEntry : BotSessionState()

    @Serializable
    @SerialName("ShowingPrivateKey")
    data object ShowingPrivateKey : BotSessionState()
}
