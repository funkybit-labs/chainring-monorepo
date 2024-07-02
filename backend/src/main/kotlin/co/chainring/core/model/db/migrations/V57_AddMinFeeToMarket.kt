package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.MarketId
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal

@Suppress("ClassName")
class V57_AddMinFeeToMarket : Migration() {

    object V57_MarketTable : GUIDTable<MarketId>("market", ::MarketId) {
        val minFee = decimal("min_fee", 30, 0).default(BigDecimal.ZERO)
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V57_MarketTable)
        }
    }
}
