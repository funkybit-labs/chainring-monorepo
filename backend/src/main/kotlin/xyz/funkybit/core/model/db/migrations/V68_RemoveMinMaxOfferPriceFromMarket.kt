package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration

@Suppress("ClassName")
class V68_RemoveMinMaxOfferPriceFromMarket : Migration() {
    override fun run() {
        transaction {
            exec("ALTER TABLE market DROP COLUMN min_allowed_bid_price")
            exec("ALTER TABLE market DROP COLUMN max_allowed_offer_price")
        }
    }
}
