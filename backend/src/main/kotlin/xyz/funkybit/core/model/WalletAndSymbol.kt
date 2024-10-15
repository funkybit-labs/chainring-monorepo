package xyz.funkybit.core.model

import xyz.funkybit.core.model.db.DepositEntity
import xyz.funkybit.core.model.db.SymbolId
import xyz.funkybit.core.model.db.WalletId
import xyz.funkybit.core.model.rpc.ArchNetworkRpc

data class WalletAndSymbol(
    val walletId: WalletId,
    val symbolId: SymbolId,
)

data class PubkeyAndIndex(
    val pubkey: ArchNetworkRpc.Pubkey,
    val addressIndex: Int,
    val address: BitcoinAddress,
)

data class ConfirmedBitcoinDeposit(
    val depositEntity: DepositEntity,
    val pubkeyAndIndex: PubkeyAndIndex,
)
