package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.DepositId
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.PGEnum
import co.chainring.core.model.db.SymbolId
import co.chainring.core.model.db.WalletId
import co.chainring.core.model.db.enumDeclaration
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V17_DepositTable : Migration() {

    object V17_SymbolTable : GUIDTable<SymbolId>("symbol", ::SymbolId)
    object V17_WalletTable : GUIDTable<WalletId>("wallet", ::WalletId)

    enum class V17_DepositStatus {
        Pending,
        Complete,
        Failed,
    }

    object V17_DepositTable : GUIDTable<DepositId>("deposit", ::DepositId) {
        val walletGuid = reference("wallet_guid", V17_WalletTable).index()
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val status = customEnumeration(
            "status",
            "DepositStatus",
            { value -> V17_DepositStatus.valueOf(value as String) },
            { PGEnum("DepositStatus", it) },
        ).index()
        val symbolGuid = reference("symbol_guid", V17_SymbolTable).index()
        val amount = decimal("amount", 30, 0)
        val blockNumber = decimal("block_number", 30, 0).index()
        val transactionHash = varchar("transaction_hash", 10485760).uniqueIndex()
        val updatedAt = timestamp("updated_at").nullable()
        val updatedBy = varchar("updated_by", 10485760).nullable()
        val error = varchar("error", 10485760).nullable()
    }

    override fun run() {
        transaction {
            exec("CREATE TYPE DepositStatus AS ENUM (${enumDeclaration<V17_DepositStatus>()})")
            SchemaUtils.createMissingTablesAndColumns(V17_DepositTable)
        }
    }
}
