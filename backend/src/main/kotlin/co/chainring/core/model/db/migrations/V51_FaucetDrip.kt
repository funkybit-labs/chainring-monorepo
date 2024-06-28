package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.FaucetDripId
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.SymbolId
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V51_FaucetDrip : Migration() {

    object V51_SymbolTable : GUIDTable<SymbolId>("symbol", ::SymbolId)

    object V51_FaucetDripTable : GUIDTable<FaucetDripId>("faucet_drip", ::FaucetDripId) {
        val createdAt = timestamp("created_at")
        val symbolGuid = reference("symbol_guid", V51_SymbolTable).index()
        val walletAddress = varchar("address", 10485760).index()
        val ipAddress = varchar("ip", 10485760).index()
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V51_FaucetDripTable)
        }
    }
}
