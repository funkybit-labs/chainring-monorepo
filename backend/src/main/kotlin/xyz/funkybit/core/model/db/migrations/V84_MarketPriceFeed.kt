package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.MarketId
import java.math.BigDecimal

@Suppress("ClassName")
class V84_MarketPriceFeed : Migration() {

    object V84_MarketTable : GUIDTable<MarketId>("market", ::MarketId) {
        val feedPrice = decimal("feed_price", 30, 18).default(BigDecimal.ZERO)
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V84_MarketTable)
        }
    }
}
