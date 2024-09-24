package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.db.updateEnum
import xyz.funkybit.core.model.db.ArchAccountId
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum

@Suppress("ClassName")
class V94_UpdateArchAccountTable : Migration() {

    enum class V94_ArchAccountStatus {
        Funded,
        Creating,
        Created,
        Initializing,
        Complete,
        Full,
        Failed,
    }

    object V94_ArchAccountTable : GUIDTable<ArchAccountId>("arch_account", ::ArchAccountId) {
        val status = customEnumeration(
            "status",
            "ArchAccountStatus",
            { value -> V94_ArchAccountStatus.valueOf(value as String) },
            { PGEnum("ArchAccountStatus", it) },
        )
        val sequenceId = integer("sequence_id").autoIncrement()
    }

    override fun run() {
        transaction {
            exec("ALTER TABLE \"arch_account\" DROP CONSTRAINT arch_account_symbol_guid_unique")
            exec("CREATE INDEX arch_account_symbol_guid_index ON arch_account (symbol_guid)")

            updateEnum<V94_ArchAccountStatus>(listOf(V94_ArchAccountTable.status), "ArchAccountStatus")
            SchemaUtils.createMissingTablesAndColumns(V94_ArchAccountTable)
        }
    }
}
