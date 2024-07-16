package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.WalletId
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V64_AddIsAdminToWallet : Migration() {

    object V64_WalletTable : GUIDTable<WalletId>("wallet", ::WalletId) {
        val isAdmin = bool("is_admin").default(false)
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V64_WalletTable)
        }
    }
}
