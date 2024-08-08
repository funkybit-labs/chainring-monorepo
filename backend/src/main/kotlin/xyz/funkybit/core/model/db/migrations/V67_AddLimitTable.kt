package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.WalletId

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
