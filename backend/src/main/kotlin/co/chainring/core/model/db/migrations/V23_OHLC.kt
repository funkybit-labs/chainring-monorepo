package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OHLCId
import co.chainring.core.model.db.PGEnum
import co.chainring.core.model.db.enumDeclaration
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V23_OHLC : Migration() {

    object V23_MarketTable : GUIDTable<MarketId>("market", ::MarketId)

    enum class V23_OHLCDuration {
        P1M,
        P5M,
        P15M,
        P1H,
        P4H,
        P1D,
    }

    object V23_OHLCTable : GUIDTable<OHLCId>("ohlc", ::OHLCId) {
        val marketGuid = reference("market_guid", V23_MarketTable).index()
        val start = timestamp("start")
        val duration = customEnumeration(
            "duration",
            "OHLCDuration",
            { value -> V23_OHLCDuration.valueOf(value as String) },
            { PGEnum("OHLCDuration", it) },
        )
        val open = decimal("open", 30, 18)
        val high = decimal("high", 30, 18)
        val low = decimal("low", 30, 18)
        val close = decimal("close", 30, 18)
        val volume = decimal("amount", 30, 0)

        val firstTrade = timestamp("first_trade")
        val lastTrade = timestamp("last_trade")
        val createdAt = timestamp("created_at")
        val updatedAt = timestamp("updated_at").nullable()

        init {
            uniqueIndex(
                customIndexName = "Unique_OHLC",
                columns = arrayOf(marketGuid, duration, start),
            )
        }
    }

    override fun run() {
        transaction {
            exec("CREATE TYPE OHLCDuration AS ENUM (${enumDeclaration<V23_OHLCDuration>()})")
            SchemaUtils.createMissingTablesAndColumns(V23_OHLCTable)
        }
    }
}
