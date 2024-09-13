package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.EntityId
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum
import xyz.funkybit.core.model.db.SymbolId
import xyz.funkybit.core.model.db.enumDeclaration

@Suppress("ClassName")
class V74_ArchStateUtxoTables : Migration() {

    object V74_SymbolTable : GUIDTable<SymbolId>("symbol", ::SymbolId)

    enum class V74_StateUtxoType {
        Exchange,
        Token,
    }

    enum class V74_StateUtxoStatus {
        Onboarding,
        Onboarded,
        Initializing,
        Complete,
        Failed,
    }

    @JvmInline
    value class V74_ArchStateUtxoId(override val value: String) : EntityId

    object V74_ArchStateUtxoTable : GUIDTable<V74_ArchStateUtxoId>(
        "arch_state_utxo",
        ::V74_ArchStateUtxoId,
    ) {
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val updatedAt = timestamp("updated_at").nullable()
        val updatedBy = varchar("updated_by", 10485760).nullable()
        val utxoId = varchar("utxo_id", 10485760).uniqueIndex()
        val creationTxId = varchar("creation_tx_id", 10485760)
        val type = customEnumeration(
            "type",
            "StateUtxoType",
            { value -> V74_StateUtxoType.valueOf(value as String) },
            { PGEnum("StateUtxoType", it) },
        )
        val symbolGuid = reference("symbol_guid", V74_SymbolTable).nullable().uniqueIndex()
        val initializationTxId = varchar("initialization_tx_id", 10485760).nullable()
        val status = customEnumeration(
            "status",
            "StateUtxoStatus",
            { value -> V74_StateUtxoStatus.valueOf(value as String) },
            { PGEnum("StateUtxoStatus", it) },
        )
    }

    @JvmInline
    value class V74_ArchStateUtxoLogId(override val value: String) : EntityId

    object V74_ArchStateUtxoLogTable : GUIDTable<V74_ArchStateUtxoLogId>(
        "arch_state_utxo_log",
        ::V74_ArchStateUtxoLogId,
    ) {
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val beforeUtxoId = varchar("before_utxo_id", 10485760)
        val afterUtxoId = varchar("after_utxo_id", 10485760)
        val archTxId = varchar("arch_tx_id", 10485760)
        val archStateUtxoGuid = reference(
            "arch_state_utxo_guid",
            V74_ArchStateUtxoTable,
        )
    }

    override fun run() {
        transaction {
            exec("CREATE TYPE StateUtxoType AS ENUM (${enumDeclaration<V74_StateUtxoType>()})")
            exec("CREATE TYPE StateUtxoStatus AS ENUM (${enumDeclaration<V74_StateUtxoStatus>()})")
            SchemaUtils.createMissingTablesAndColumns(V74_ArchStateUtxoTable, V74_ArchStateUtxoLogTable)
        }
    }
}
