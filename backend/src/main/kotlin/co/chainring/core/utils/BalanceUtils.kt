package co.chainring.core.utils

import co.chainring.core.model.db.BalanceEntity
import co.chainring.core.model.db.BalanceId
import co.chainring.core.model.db.BalanceType
import co.chainring.core.model.db.BalanceUpdateAssignment
import co.chainring.core.model.db.SymbolId
import co.chainring.core.model.db.WalletId
import java.math.BigInteger

object BalanceUtils {

    data class BalanceChange(
        val walletId: WalletId,
        val symbolId: SymbolId,
        val delta: BigInteger? = null,
        val finalAmount: BigInteger? = null,
    )

    fun updateBalances(changes: List<BalanceChange>, balanceType: BalanceType) {
        val startingBalances = BalanceEntity.getBalances(
            changes.map { it.walletId }.toSet().toList(),
            changes.map { it.symbolId }.toSet().toList(),
            balanceType,
        )
        BalanceEntity.updateBalances(
            changes.map { change ->
                val startingBalanceEntity = startingBalances.firstOrNull { it.wallet.guid.value == change.walletId && it.symbol.guid.value == change.symbolId }
                val startingBalance = startingBalanceEntity?.balance
                BalanceUpdateAssignment(
                    walletId = change.walletId,
                    symbolId = change.symbolId,
                    delta = change.delta ?: (change.finalAmount!! - (startingBalance ?: BigInteger.ZERO)),
                    startingBalanceEntity?.guid?.value ?: BalanceId.generate(),
                    startingBalance,
                    change.finalAmount ?: BigInteger.ZERO.max((startingBalance ?: BigInteger.ZERO) + change.delta!!),
                    balanceType,
                )
            },
        )
    }
}
