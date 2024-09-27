package xyz.funkybit.core.model

import kotlinx.serialization.Serializable
import xyz.funkybit.apps.api.model.BigIntegerJson
import xyz.funkybit.core.model.db.SymbolId
import xyz.funkybit.core.model.db.WalletId
import xyz.funkybit.core.model.evm.EvmSettlement
import xyz.funkybit.core.utils.toHexBytes

sealed class Settlement {
    @Serializable
    data class Adjustment(
        val walletId: WalletId,
        val walletIndex: Int,
        val amount: BigIntegerJson,
    )

    @Serializable
    data class TokenAdjustmentList(
        val symbolId: SymbolId,
        val token: String,
        var increments: List<Adjustment>,
        var decrements: List<Adjustment>,
        var feeAmount: BigIntegerJson,
    )

    data class Batch(
        val walletAddresses: List<Address>,
        val walletTradesList: List<List<TxHash>>,
        val tokenAdjustmentLists: List<TokenAdjustmentList>,
    ) {
        fun toEvm(): EvmSettlement.Batch {
            return EvmSettlement.Batch(
                walletAddresses = walletAddresses.map { (it as EvmAddress).value },
                walletTradeLists = walletTradesList.map { txHashes -> EvmSettlement.WalletTradeList(txHashes.map { it.value.toHexBytes() }) },
                tokenAdjustmentLists = tokenAdjustmentLists.map { tal ->
                    EvmSettlement.TokenAdjustmentList(
                        token = tal.token,
                        increments = tal.increments.map { EvmSettlement.Adjustment(it.walletIndex.toBigInteger(), it.amount) },
                        decrements = tal.decrements.map { EvmSettlement.Adjustment(it.walletIndex.toBigInteger(), it.amount) },
                        feeAmount = tal.feeAmount,
                    )
                },
            )
        }
    }
}
