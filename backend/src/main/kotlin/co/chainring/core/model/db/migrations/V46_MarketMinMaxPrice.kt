package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.MarketEntity
import co.chainring.core.model.db.MarketId
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import kotlin.math.min

@Suppress("ClassName")
class V46_MarketMinMaxPrice : Migration() {

    object V46_MarketTable : GUIDTable<MarketId>("market", ::MarketId) {
        val minAllowedBidPrice = decimal("min_allowed_bid_price", 30, 18).nullable()
        val maxAllowedOfferPrice = decimal("max_allowed_offer_price", 30, 18).nullable()
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V46_MarketTable)

            MarketEntity.all().forEach { marketEntity ->
                val levels = 1000
                val lastPrice = marketEntity.lastPrice
                val tickSize = marketEntity.tickSize
                val halfTick = tickSize.setScale(tickSize.scale() + 1) / BigDecimal.valueOf(2)

                val marketIx = min(levels / 2, (lastPrice - halfTick).divideToIntegralValue(tickSize).toInt())

                val minAllowedBidPrice = lastPrice.minus(tickSize.multiply((marketIx - 0.5).toBigDecimal()))
                val maxAllowedOfferPrice = lastPrice.plus(tickSize.multiply((levels - marketIx + 0.5).toBigDecimal()))

                // min/max prices are calculated based on last price (not initial price)
                // need manual adjustment after deployment is required
                marketEntity.minAllowedBidPrice = minAllowedBidPrice
                marketEntity.maxAllowedOfferPrice = maxAllowedOfferPrice
            }

            exec("""ALTER TABLE market ALTER COLUMN min_allowed_bid_price SET NOT NULL""")
            exec("""ALTER TABLE market ALTER COLUMN max_allowed_offer_price SET NOT NULL""")
        }
    }
}
