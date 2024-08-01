package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.SymbolId
import xyz.funkybit.core.model.db.WalletId
import xyz.funkybit.core.model.db.migrations.V48_AddingSymbolsToWallets.V48_SymbolTable.array

@Suppress("ClassName")
class V48_AddingSymbolsToWallets : Migration() {

    object V48_SymbolTable : GUIDTable<SymbolId>("symbol", ::SymbolId) {
        val iconUrl = varchar("icon_url", 10485760).nullable()
        val addToWallets = bool("add_to_wallets").default(false)
    }

    object V48_WalletTable : GUIDTable<WalletId>("wallet", ::WalletId) {
        val addedSymbols = array<String>("added_symbols", VarCharColumnType(10485760)).default(emptyList())
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V48_SymbolTable, V48_WalletTable)
        }
    }
}
