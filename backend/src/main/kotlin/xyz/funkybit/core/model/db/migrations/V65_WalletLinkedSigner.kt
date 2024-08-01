package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.ChainIdColumnType
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.WalletId
import xyz.funkybit.core.model.db.WalletLinkedSignerId

@Suppress("ClassName")
class V65_WalletLinkedSigner : Migration() {

    private object V65_ChainTable : IdTable<ChainId>("chain") {
        override val id: Column<EntityID<ChainId>> = registerColumn<ChainId>(
            "id",
            ChainIdColumnType(),
        ).entityId()
        override val primaryKey = PrimaryKey(id)
    }
    object V65_WalletTable : GUIDTable<WalletId>("wallet", ::WalletId)

    object V65_WalletLinkedSignerTable : GUIDTable<WalletLinkedSignerId>("wallet_linked_signer", ::WalletLinkedSignerId) {
        val walletGuid = reference("wallet_guid", V65_WalletTable).index()
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val updatedAt = timestamp("updated_at").nullable()
        val updatedBy = varchar("updated_by", 10485760).nullable()
        val chainId = reference("chain_id", V65_ChainTable)
        val signerAddress = varchar("signer_address", 10485760)

        init {
            uniqueIndex(
                customIndexName = "uix_wallet_linked_signer_wallet_guid_chain_id",
                columns = arrayOf(walletGuid, chainId),
            )
        }
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V65_WalletLinkedSignerTable)
        }
    }
}
