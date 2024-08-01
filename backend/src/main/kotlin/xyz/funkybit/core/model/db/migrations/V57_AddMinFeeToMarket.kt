package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.MarketId
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
