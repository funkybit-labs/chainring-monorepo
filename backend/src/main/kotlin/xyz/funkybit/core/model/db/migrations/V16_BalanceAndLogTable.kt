package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.BalanceId
import xyz.funkybit.core.model.db.BalanceLogId
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum
import xyz.funkybit.core.model.db.SymbolId
import xyz.funkybit.core.model.db.WalletId
import xyz.funkybit.core.model.db.enumDeclaration

@Suppress("ClassName")
class V16_BalanceAndLogTable : Migration() {

    object V16_SymbolTable : GUIDTable<SymbolId>("symbol", ::SymbolId)

    object V16_WalletTable : GUIDTable<WalletId>("wallet", ::WalletId)

    enum class V16_BalanceType {
        Exchange,
        Available,
    }

    object V16_BalanceTable : GUIDTable<BalanceId>("balance", ::BalanceId) {
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val updatedAt = timestamp("updated_at").nullable()
        val updatedBy = varchar("updated_by", 10485760).nullable()
        val symbolGuid = reference("symbol_guid", V16_SymbolTable).index()
        val walletGuid = reference("wallet_guid", V16_WalletTable).index()
        val balance = decimal("balance", 30, 0)
        val type = customEnumeration(
            "type",
            "BalanceType",
            { value -> V16_BalanceType.valueOf(value as String) },
            { PGEnum("BalanceType", it) },
        )
    }

    object V16_BalanceLogTable : GUIDTable<BalanceLogId>(
        "balance_log",
        ::BalanceLogId,
    ) {
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val balanceBefore = decimal("balance_before", 30, 0)
        val balanceAfter = decimal("balance_after", 30, 0)
        val delta = decimal("delta", 30, 0)
        val balanceGuid = reference(
            "balance_guid",
            V16_BalanceTable,
        )
    }

    override fun run() {
        transaction {
            exec("CREATE TYPE BalanceType AS ENUM (${enumDeclaration<V16_BalanceType>()})")
            SchemaUtils.createMissingTablesAndColumns(V16_BalanceTable, V16_BalanceLogTable)
        }
    }
}
