package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.MarketId

@Suppress("ClassName")
class V6_MarketTable : Migration() {

    object V6_MarketTable : GUIDTable<MarketId>("market", ::MarketId) {
        val baseSymbol = varchar("base_symbol", 10485760)
        val quoteSymbol = varchar("quote_symbol", 10485760)
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V6_MarketTable)

            exec("INSERT INTO market(guid, base_symbol, quote_symbol) VALUES ('USDC/DAI', 'USDC', 'DAI')")
            exec("INSERT INTO market(guid, base_symbol, quote_symbol) VALUES ('ETH/USDC', 'ETH', 'USDC')")
        }
    }
}
