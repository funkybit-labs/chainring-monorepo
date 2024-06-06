package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.MarketId
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V38_AddLastPriceToMarket : Migration() {

    object V38_Market : GUIDTable<MarketId>(
        "market",
        ::MarketId,
    ) {
        val lastPrice = decimal("last_price", 30, 18).nullable()
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V38_Market)
            exec("""UPDATE market SET last_price = 0.0""")
            exec("""ALTER TABLE market ALTER COLUMN last_price SET NOT NULL""")
        }
    }
}
