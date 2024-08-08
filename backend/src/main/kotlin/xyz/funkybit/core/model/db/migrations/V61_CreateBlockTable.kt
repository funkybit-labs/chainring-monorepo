package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.BlockHash
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.ChainIdColumnType
import xyz.funkybit.core.model.db.DepositId
import xyz.funkybit.core.model.db.GUIDTable

@Suppress("ClassName")
class V61_CreateBlockTable : Migration() {
    object V61_ChainTable : IdTable<ChainId>("chain") {
        override val id: Column<EntityID<ChainId>> = registerColumn<ChainId>("id", ChainIdColumnType()).entityId()
        override val primaryKey = PrimaryKey(id)
    }

    object V61_BlockTable : GUIDTable<BlockHash>("block", ::BlockHash) {
        val chainId = reference("chain_id", V61_ChainTable)
        val number = decimal("number", 30, 0)
        val parentGuid = varchar("parent_guid", 10485760).index()

        init {
            index(true, number, chainId)
        }
    }

    object V61_DepositTable : GUIDTable<DepositId>("deposit", ::DepositId) {
        val canBeResubmitted = bool("can_be_resubmitted").default(false)
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V61_BlockTable, V61_DepositTable)
        }
    }
}
