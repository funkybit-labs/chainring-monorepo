package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.MarketId
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.math.BigDecimal
import kotlin.math.min

@Suppress("ClassName")
class V46_MarketMinMaxPrice : Migration() {

    object V46_MarketTable : GUIDTable<MarketId>("market", ::MarketId) {

        val tickSize = decimal("tick_size", 30, 18)
        val lastPrice = decimal("last_price", 30, 18)
        val minAllowedBidPrice = decimal("min_allowed_bid_price", 30, 18).nullable()
        val maxAllowedOfferPrice = decimal("max_allowed_offer_price", 30, 18).nullable()
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V46_MarketTable)

            V46_MarketTable.selectAll().forEach { marketRow ->
                val levels = 1000
                val lastPrice = marketRow[V46_MarketTable.lastPrice]
                val tickSize = marketRow[V46_MarketTable.tickSize]
                val halfTick = tickSize.setScale(tickSize.scale() + 1) / BigDecimal.valueOf(2)

                val marketIx = min(levels / 2, (lastPrice - halfTick).divideToIntegralValue(tickSize).toInt())

                val minAllowedBidPrice = lastPrice.minus(tickSize.multiply((marketIx - 0.5).toBigDecimal()))
                val maxAllowedOfferPrice = lastPrice.plus(tickSize.multiply((levels - marketIx + 0.5).toBigDecimal()))

                // min/max prices are calculated based on last price (not initial price)
                // need manual adjustment after deployment is required
                V46_MarketTable.update({ V46_MarketTable.guid.eq(marketRow[V46_MarketTable.guid]) }) {
                    it[this.minAllowedBidPrice] = minAllowedBidPrice
                    it[this.maxAllowedOfferPrice] = maxAllowedOfferPrice
                }
            }

            exec("""ALTER TABLE market ALTER COLUMN min_allowed_bid_price SET NOT NULL""")
            exec("""ALTER TABLE market ALTER COLUMN max_allowed_offer_price SET NOT NULL""")
        }
    }
}
