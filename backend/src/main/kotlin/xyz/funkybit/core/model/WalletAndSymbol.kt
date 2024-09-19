package xyz.funkybit.core.model

import xyz.funkybit.core.model.db.SymbolId
import xyz.funkybit.core.model.db.WalletId

data class WalletAndSymbol(
    val walletId: WalletId,
    val symbolId: SymbolId,
)
