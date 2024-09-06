package xyz.funkybit.core.model.db.migrations

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum
import xyz.funkybit.core.model.db.SymbolId
import xyz.funkybit.core.model.db.UserId
import xyz.funkybit.core.sequencer.toSequencerId

@Suppress("ClassName")
class V78_CrossChainSequencerOrders : Migration() {

    object V78_UserTable : GUIDTable<UserId>("user", ::UserId) {
        val sequencerId = long("sequencer_id").uniqueIndex().nullable()
    }

    @Serializable
    enum class V78_WalletFamily {
        Bitcoin,
        Evm,
    }

    object V78_SymbolTable : GUIDTable<SymbolId>("symbol", ::SymbolId) {
        val walletFamily = customEnumeration(
            "wallet_family",
            "WalletFamily",
            { value -> V78_WalletFamily.valueOf(value as String) },
            { PGEnum("WalletFamily", it) },
        ).nullable()
    }

    override fun run() {
        transaction {
            // better naming
            exec("ALTER TABLE wallet RENAME COLUMN wallet_family TO family")

            // add sequencerId to User
            SchemaUtils.createMissingTablesAndColumns(V78_UserTable)
            V78_UserTable.selectAll().forEach { resultRow ->
                val guid = resultRow[V78_UserTable.guid]
                V78_UserTable.update({ V78_UserTable.guid.eq(guid) }) {
                    it[this.sequencerId] = guid.value.toSequencerId().value
                }
            }
            exec("ALTER TABLE \"user\" ALTER COLUMN sequencer_id SET NOT NULL")

            // add wallet family to Symbol
            SchemaUtils.createMissingTablesAndColumns(V78_SymbolTable)
            V78_SymbolTable.selectAll().forEach { resultRow ->
                val guid = resultRow[V78_SymbolTable.guid]
                V78_SymbolTable.update({ V78_SymbolTable.guid.eq(guid) }) {
                    it[this.walletFamily] = if (guid.value.value.endsWith("_0")) V78_WalletFamily.Bitcoin else V78_WalletFamily.Evm
                }
            }
            exec("ALTER TABLE symbol ALTER COLUMN wallet_family SET NOT NULL")
        }
    }
}
