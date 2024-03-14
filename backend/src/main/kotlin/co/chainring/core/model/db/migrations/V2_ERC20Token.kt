package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.ERC20TokenId
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.PGEnum
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V2_ERC20Token : Migration() {
    private object V2_ERC20TokenTable : GUIDTable<ERC20TokenId>("erc20_token", ::ERC20TokenId) {
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val name = varchar("name", 10485760)
        val symbol = varchar("symbol", 10485760)
        val chain = customEnumeration(
            "chain",
            "Chain",
            { value -> V1_DeployedSmartContract.V1_Chain.valueOf(value as String) },
            { PGEnum("Chain", it) },
        )
        val address = varchar("address", 10485760)

        init {
            uniqueIndex(
                customIndexName = "erc20_token_address_chain",
                columns = arrayOf(address, chain),
            )
        }
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V2_ERC20TokenTable)
        }
    }
}
