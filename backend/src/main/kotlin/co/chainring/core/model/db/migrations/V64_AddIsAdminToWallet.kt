package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.SymbolId
import co.chainring.core.model.db.WalletId
import co.chainring.core.model.db.migrations.V64_AddIsAdminToWallet.V64_WalletTable.nullable
import co.chainring.core.model.db.migrations.V64_AddIsAdminToWallet.V64_WalletTable.varchar
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V64_AddIsAdminToWallet : Migration() {

    object V64_WalletTable : GUIDTable<WalletId>("wallet", ::WalletId) {
        val isAdmin = bool("is_admin").default(false)
    }

    object V64_MarketTable : GUIDTable<MarketId>("market", ::MarketId) {
        val createdAt = timestamp("created_at").nullable()
        val createdBy = varchar("created_by", 10485760).nullable()
        val updatedAt = timestamp("updated_at").nullable()
        val updatedBy = varchar("updated_by", 10485760).nullable()
    }

    object V64_SymbolTable : GUIDTable<SymbolId>("symbol", ::SymbolId) {
        val updatedAt = timestamp("updated_at").nullable()
        val updatedBy = varchar("updated_by", 10485760).nullable()
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V64_WalletTable, V64_MarketTable, V64_SymbolTable)
            exec(
                """
                UPDATE market SET created_at = symbol.created_at, created_by = symbol.created_by FROM symbol WHERE market.base_symbol_guid = symbol.guid;
                ALTER TABLE market ALTER COLUMN created_at SET NOT NULL;
                ALTER TABLE market ALTER COLUMN created_by SET NOT NULL;
                """.trimIndent(),
            )
        }
    }
}
