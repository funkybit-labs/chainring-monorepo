package co.chainring.core.model.telegrambot

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
sealed class SessionState {
    @Serializable
    @SerialName("Initial")
    data object Initial : SessionState()

    @Serializable
    @SerialName("MainMenu")
    data object MainMenu : SessionState()

    @Serializable
    @SerialName("AirdropSymbolSelection")
    data object AirdropSymbolSelection : SessionState()

    @Serializable
    @SerialName("AirdropPending")
    data class AirdropPending(val symbolId: SymbolId, val amount: BigDecimalJson, val txHash: TxHash) : SessionState() {
        constructor(symbol: SymbolEntity, amount: BigDecimalJson, txHash: TxHash) : this(symbol.id.value, amount, txHash)

        val symbol: SymbolEntity
            get() = SymbolEntity[symbolId]
    }

    @Serializable
    @SerialName("DepositSymbolSelection")
    data object DepositSymbolSelection : SessionState()

    @Serializable
    @SerialName("DepositAmountEntry")
    data class DepositAmountEntry(val symbolId: SymbolId) : SessionState() {
        constructor(symbol: SymbolEntity) : this(symbol.id.value)

        val symbol: SymbolEntity
            get() = SymbolEntity[symbolId]
    }

    @Serializable
    @SerialName("DepositConfirmation")
    data class DepositConfirmation(val symbolId: SymbolId, val amount: BigDecimalJson) : SessionState() {
        constructor(symbol: SymbolEntity, amount: BigDecimalJson) : this(symbol.id.value, amount)

        val symbol: SymbolEntity
            get() = SymbolEntity[symbolId]
    }

    @Serializable
    @SerialName("DepositPending")
    data class DepositPending(val depositId: DepositId) : SessionState()

    @Serializable
    @SerialName("WithdrawalSymbolSelection")
    data object WithdrawalSymbolSelection : SessionState()

    @Serializable
    @SerialName("WithdrawalAmountEntry")
    data class WithdrawalAmountEntry(val symbolId: SymbolId) : SessionState() {
        constructor(symbol: SymbolEntity) : this(symbol.id.value)

        val symbol: SymbolEntity
            get() = SymbolEntity[symbolId]
    }

    @Serializable
    @SerialName("WithdrawalConfirmation")
    data class WithdrawalConfirmation(val symbolId: SymbolId, val amount: BigDecimalJson) : SessionState() {
        constructor(symbol: SymbolEntity, amount: BigDecimalJson) : this(symbol.id.value, amount)

        val symbol: SymbolEntity
            get() = SymbolEntity[symbolId]
    }

    @Serializable
    @SerialName("WithdrawalPending")
    data class WithdrawalPending(val withdrawalId: WithdrawalId) : SessionState()

    @Serializable
    @SerialName("SwapFromSymbolSelection")
    data object SwapFromSymbolSelection : SessionState()

    @Serializable
    @SerialName("SwapToSymbolSelection")
    data class SwapToSymbolSelection(val fromSymbolId: SymbolId) : SessionState() {
        constructor(fromSymbol: SymbolEntity) : this(fromSymbol.id.value)

        val fromSymbol: SymbolEntity
            get() = SymbolEntity[fromSymbolId]
    }

    @Serializable
    @SerialName("SwapAmountEntry")
    data class SwapAmountEntry(val fromSymbolId: SymbolId, val toSymbolId: SymbolId) : SessionState() {
        constructor(fromSymbol: SymbolEntity, toSymbol: SymbolEntity) : this(fromSymbol.id.value, toSymbol.id.value)

        val fromSymbol: SymbolEntity
            get() = SymbolEntity[fromSymbolId]

        val toSymbol: SymbolEntity
            get() = SymbolEntity[toSymbolId]
    }

    @Serializable
    @SerialName("SwapConfirmation")
    data class SwapConfirmation(val fromSymbolId: SymbolId, val toSymbolId: SymbolId, val amount: BigDecimalJson) : SessionState() {
        constructor(fromSymbol: SymbolEntity, toSymbol: SymbolEntity, amount: BigDecimalJson) : this(fromSymbol.id.value, toSymbol.id.value, amount)

        val fromSymbol: SymbolEntity
            get() = SymbolEntity[fromSymbolId]

        val toSymbol: SymbolEntity
            get() = SymbolEntity[toSymbolId]
    }

    @Serializable
    @SerialName("SwapPending")
    data class SwapPending(val orderId: OrderId) : SessionState()

    @Serializable
    @SerialName("Settings")
    data object Settings : SessionState()

    @Serializable
    @SerialName("WalletToSwitchToSelection")
    data object WalletToSwitchToSelection : SessionState()

    @Serializable
    @SerialName("ImportWalletPrivateKeyEntry")
    data object ImportWalletPrivateKeyEntry : SessionState()

    @Serializable
    @SerialName("ShowingPrivateKey")
    data object ShowingPrivateKey : SessionState()
}
