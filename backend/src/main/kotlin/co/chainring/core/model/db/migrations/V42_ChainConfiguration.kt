package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.ChainIdColumnType
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V40_ChainConfiguration : Migration() {

    private object V40_ChainTable : IdTable<ChainId>("chain") {
        override val id: Column<EntityID<ChainId>> = registerColumn<ChainId>("id", ChainIdColumnType()).entityId()
        override val primaryKey = PrimaryKey(id)

        val name = varchar("name", 10485760)
        val jsonRpcUrl = varchar("json_rpc_url", 10485760).default("")
        val blockExplorerNetName = varchar("block_explorer_net_name", 10485760).default("")
        val blockExplorerUrl = varchar("block_explorer_url", 10485760).default("")
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V40_ChainTable)
        }
    }
}
