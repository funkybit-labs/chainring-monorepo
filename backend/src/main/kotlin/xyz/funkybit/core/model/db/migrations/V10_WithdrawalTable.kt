package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum
import xyz.funkybit.core.model.db.SymbolId
import xyz.funkybit.core.model.db.WithdrawalId
import xyz.funkybit.core.model.db.enumDeclaration

@Suppress("ClassName")
class V10_WithdrawalTable : Migration() {

    object V10_SymbolTable : GUIDTable<SymbolId>("symbol", ::SymbolId)

    enum class V10_WithdrawalStatus {
        Pending,
        Complete,
        Failed,
    }

    object V10_WithdrawalTable : GUIDTable<WithdrawalId>("withdrawal", ::WithdrawalId) {
        val walletAddress = varchar("wallet_address", 10485760).index()
        val nonce = long("nonce")
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val symbolGuid = reference("symbol_guid", V10_SymbolTable).index()
        val signature = varchar("signature", 10485760)
        val status = customEnumeration(
            "status",
            "WithdrawalStatus",
            { value -> V10_WithdrawalStatus.valueOf(value as String) },
            { PGEnum("WithdrawalStatus", it) },
        ).index()
        val amount = decimal("amount", 30, 0)
        val updatedAt = timestamp("updated_at").nullable()
        val updatedBy = varchar("updated_by", 10485760).nullable()
        val error = varchar("error", 10485760).nullable()
    }

    override fun run() {
        transaction {
            exec("CREATE TYPE WithdrawalStatus AS ENUM (${enumDeclaration<V10_WithdrawalStatus>()})")
            SchemaUtils.createMissingTablesAndColumns(V10_WithdrawalTable)

            // smart contract changes not backward compatible in this version
            exec("DELETE from deployed_smart_contract where name = 'Exchange'")
        }
    }
}
