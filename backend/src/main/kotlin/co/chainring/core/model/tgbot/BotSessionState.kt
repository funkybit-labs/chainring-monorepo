package co.chainring.core.model.tgbot

import co.chainring.apps.api.model.BigDecimalJson
import co.chainring.core.model.TxHash
import co.chainring.core.model.db.DepositId
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.SymbolEntity
import co.chainring.core.model.db.SymbolId
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
    data class AirdropPending(val symbolId: SymbolId, val amount: BigDecimalJson, val txHash: TxHash) : BotSessionState() {
        constructor(symbol: SymbolEntity, amount: BigDecimalJson, txHash: TxHash) : this(symbol.id.value, amount, txHash)

        val symbol: SymbolEntity
            get() = SymbolEntity[symbolId]
    }

    @Serializable
    @SerialName("DepositSymbolSelection")
    data object DepositSymbolSelection : BotSessionState()

    @Serializable
    @SerialName("DepositAmountEntry")
    data class DepositAmountEntry(val symbolId: SymbolId) : BotSessionState() {
        constructor(symbol: SymbolEntity) : this(symbol.id.value)

        val symbol: SymbolEntity
            get() = SymbolEntity[symbolId]
    }

    @Serializable
    @SerialName("DepositConfirmation")
    data class DepositConfirmation(val symbolId: SymbolId, val amount: BigDecimalJson) : BotSessionState() {
        constructor(symbol: SymbolEntity, amount: BigDecimalJson) : this(symbol.id.value, amount)

        val symbol: SymbolEntity
            get() = SymbolEntity[symbolId]
    }

    @Serializable
    @SerialName("DepositPending")
    data class DepositPending(val depositId: DepositId) : BotSessionState()

    @Serializable
    @SerialName("WithdrawalSymbolSelection")
    data object WithdrawalSymbolSelection : BotSessionState()

    @Serializable
    @SerialName("WithdrawalAmountEntry")
    data class WithdrawalAmountEntry(val symbolId: SymbolId) : BotSessionState() {
        constructor(symbol: SymbolEntity) : this(symbol.id.value)

        val symbol: SymbolEntity
            get() = SymbolEntity[symbolId]
    }

    @Serializable
    @SerialName("WithdrawalConfirmation")
    data class WithdrawalConfirmation(val symbolId: SymbolId, val amount: BigDecimalJson) : BotSessionState() {
        constructor(symbol: SymbolEntity, amount: BigDecimalJson) : this(symbol.id.value, amount)

        val symbol: SymbolEntity
            get() = SymbolEntity[symbolId]
    }

    @Serializable
    @SerialName("WithdrawalPending")
    data class WithdrawalPending(val withdrawalId: WithdrawalId) : BotSessionState()

    @Serializable
    @SerialName("SwapFromSymbolSelection")
    data object SwapFromSymbolSelection : BotSessionState()

    @Serializable
    @SerialName("SwapToSymbolSelection")
    data class SwapToSymbolSelection(val fromSymbolId: SymbolId) : BotSessionState() {
        constructor(fromSymbol: SymbolEntity) : this(fromSymbol.id.value)

        val fromSymbol: SymbolEntity
            get() = SymbolEntity[fromSymbolId]
    }

    @Serializable
    @SerialName("SwapAmountEntry")
    data class SwapAmountEntry(val fromSymbolId: SymbolId, val toSymbolId: SymbolId) : BotSessionState() {
        constructor(fromSymbol: SymbolEntity, toSymbol: SymbolEntity) : this(fromSymbol.id.value, toSymbol.id.value)

        val fromSymbol: SymbolEntity
            get() = SymbolEntity[fromSymbolId]

        val toSymbol: SymbolEntity
            get() = SymbolEntity[toSymbolId]
    }

    @Serializable
    @SerialName("SwapConfirmation")
    data class SwapConfirmation(val fromSymbolId: SymbolId, val toSymbolId: SymbolId, val amount: BigDecimalJson) : BotSessionState() {
        constructor(fromSymbol: SymbolEntity, toSymbol: SymbolEntity, amount: BigDecimalJson) : this(fromSymbol.id.value, toSymbol.id.value, amount)

        val fromSymbol: SymbolEntity
            get() = SymbolEntity[fromSymbolId]

        val toSymbol: SymbolEntity
            get() = SymbolEntity[toSymbolId]
    }

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
