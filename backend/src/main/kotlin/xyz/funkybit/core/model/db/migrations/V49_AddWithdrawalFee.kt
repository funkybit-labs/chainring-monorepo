package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.SymbolId
import xyz.funkybit.core.model.db.WithdrawalId
import java.math.BigDecimal

@Suppress("ClassName")
class V49_AddWithdrawalFee : Migration() {

    object V49_WithdrawalTable : GUIDTable<WithdrawalId>("withdrawal", ::WithdrawalId) {
        val fee = decimal("fee", 30, 0).default(BigDecimal.ZERO)
    }

    object V49_SymbolTable : GUIDTable<SymbolId>("symbol", ::SymbolId) {
        val withdrawalFee = decimal("withdrawal_fee", 30, 0).default(BigDecimal.ZERO)
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V49_WithdrawalTable, V49_SymbolTable)
        }
    }
}
