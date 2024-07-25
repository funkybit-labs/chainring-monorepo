package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.WalletId
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V67_AddLimitTable : Migration() {
    object V67_MarketTable : GUIDTable<MarketId>("market", ::MarketId)
    object V67_WalletTable : GUIDTable<WalletId>("wallet", ::WalletId)

    object V67_LimitTable : IntIdTable("limit") {
        val marketGuid = reference("market_guid", V67_MarketTable).index()
        val walletGuid = reference("wallet_guid", V67_WalletTable).index()
        val base = decimal("base", 30, 0)
        val quote = decimal("quote", 30, 0)
        val updatedAt = timestamp("updated_at")

        init {
            uniqueIndex(
                customIndexName = "unique_limit",
                columns = arrayOf(marketGuid, walletGuid),
            )
        }
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V67_LimitTable)
        }
    }
}
