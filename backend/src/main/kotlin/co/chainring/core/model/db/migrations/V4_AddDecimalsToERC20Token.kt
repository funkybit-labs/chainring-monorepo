package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.EntityId
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.PGEnum
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V4_AddDecimalsToERC20Token : Migration() {
    @Serializable
    @JvmInline
    value class ERC20TokenId(override val value: String) : EntityId {
        override fun toString(): String = value
    }

    private object V4_ERC20TokenTable : GUIDTable<ERC20TokenId>("erc20_token", ::ERC20TokenId) {
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
        val decimals = ubyte("decimals").default(18u)

        init {
            uniqueIndex(
                customIndexName = "erc20_token_address_chain",
                columns = arrayOf(address, chain),
            )
        }
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V4_ERC20TokenTable)
        }
    }
}
