package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.SymbolId
import co.chainring.core.model.db.WalletId
import co.chainring.core.model.db.migrations.V48_AddingSymbolsToWallets.V48_SymbolTable.array
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.transactions.transaction

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
