package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V68_RemoveMinMaxOfferPriceFromMarket : Migration() {
    override fun run() {
        transaction {
            exec("ALTER TABLE market DROP COLUMN min_allowed_bid_price")
            exec("ALTER TABLE market DROP COLUMN max_allowed_offer_price")
        }
    }
}
