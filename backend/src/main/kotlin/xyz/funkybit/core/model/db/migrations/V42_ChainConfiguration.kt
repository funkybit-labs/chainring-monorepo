package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.ChainIdColumnType

@Suppress("ClassName")
class V42_ChainConfiguration : Migration() {

    private object V42_ChainTable : IdTable<ChainId>("chain") {
        override val id: Column<EntityID<ChainId>> = registerColumn<ChainId>("id", ChainIdColumnType()).entityId()
        override val primaryKey = PrimaryKey(id)

        val name = varchar("name", 10485760)
        val jsonRpcUrl = varchar("json_rpc_url", 10485760).default("")
        val blockExplorerNetName = varchar("block_explorer_net_name", 10485760).default("")
        val blockExplorerUrl = varchar("block_explorer_url", 10485760).default("")
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V42_ChainTable)
        }
    }
}
