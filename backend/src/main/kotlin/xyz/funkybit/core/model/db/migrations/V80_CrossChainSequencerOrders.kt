package xyz.funkybit.core.model.db.migrations

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum
import xyz.funkybit.core.model.db.UserId
import xyz.funkybit.core.model.db.WalletId
import xyz.funkybit.core.sequencer.toSequencerId

@Suppress("ClassName")
class V80_CrossChainSequencerOrders : Migration() {

    object V80_UserTable : GUIDTable<UserId>("user", ::UserId) {
        val sequencerId = long("sequencer_id").uniqueIndex().nullable()
    }

    @Serializable
    enum class V80_NetworkType {
        Evm,
        Bitcoin,
    }

    object V80_WalletTable : GUIDTable<WalletId>("wallet", ::WalletId) {
        val networkType = customEnumeration(
            "network_type",
            "NetworkType",
            { value -> V80_NetworkType.valueOf(value as String) },
            { PGEnum("NetworkType", it) },
        ).nullable()
    }
    override fun run() {
        transaction {
            // migrate to network type
            SchemaUtils.createMissingTablesAndColumns(V80_WalletTable)
            exec("UPDATE wallet SET network_type = wallet_family::TEXT::NetworkType")
            exec("ALTER TABLE wallet ALTER COLUMN network_type SET NOT NULL")
            exec("CREATE UNIQUE INDEX wallet_user_network_type ON wallet (user_guid, network_type)")

            exec("ALTER TABLE wallet DROP COLUMN wallet_family")
            exec("DROP TYPE walletfamily")

            // add sequencerId to User
            SchemaUtils.createMissingTablesAndColumns(V80_UserTable)
            V80_UserTable.selectAll().forEach { resultRow ->
                val guid = resultRow[V80_UserTable.guid]
                V80_UserTable.update({ V80_UserTable.guid.eq(guid) }) {
                    it[this.sequencerId] = guid.value.toSequencerId().value
                }
            }
            exec("ALTER TABLE \"user\" ALTER COLUMN sequencer_id SET NOT NULL")
        }
    }
}
