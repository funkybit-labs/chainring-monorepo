package xyz.funkybit.core.repeater.tasks

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.statements.BatchUpdateStatement
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.MarketTable
import xyz.funkybit.core.utils.PriceFeed
import kotlin.time.Duration.Companion.seconds

data class PriceFeedManagerConfig(
    val priceUpdateIntervalMs: Long = System.getenv("PRICE_FEED_INTERVAL_MS")?.toLong() ?: 600000L,
)

class PriceFeedManagerTask(val config: PriceFeedManagerConfig = PriceFeedManagerConfig()) : RepeaterBaseTask(
    invokePeriod = 10.seconds,
) {
    override val name: String = "price_feed_manager"

    private var priceFeed: PriceFeed? = null
    private var marketIds = setOf<MarketId>()
    private fun getMarketIds() = transaction { MarketTable.select(MarketTable.id).map { it[MarketTable.id].value }.toSet() }

    override fun runWithLock() {
        val currentMarketIds = getMarketIds()
        if (currentMarketIds != marketIds) {
            priceFeed?.stop()
            priceFeed = PriceFeed(currentMarketIds.toList(), config.priceUpdateIntervalMs) { prices ->
                transaction {
                    BatchUpdateStatement(MarketTable).apply {
                        prices.forEach { (marketId, price) ->
                            addBatch(EntityID(marketId, MarketTable))
                            this[MarketTable.feedPrice] = price.toBigDecimal()
                        }
                        execute(TransactionManager.current())
                    }
                }
            }.also { it.start() }
        }
    }
}
