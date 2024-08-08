package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.db.updateEnum
import xyz.funkybit.core.model.db.DepositId
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum
import xyz.funkybit.core.model.db.SymbolId
import xyz.funkybit.core.model.db.WalletId

@Suppress("ClassName")
class V44_AddSentToSequencerStatusToDeposit : Migration() {
    @Suppress("ClassName")
    enum class V44_DepositStatus {
        Pending,
        Confirmed,
        SentToSequencer,
        Complete,
        Failed,
    }

    object V44_WalletTable : GUIDTable<WalletId>("wallet", ::WalletId)
    object V44_SymbolTable : GUIDTable<SymbolId>("symbol", ::SymbolId)

    object V44_DepositTable : GUIDTable<DepositId>("deposit", ::DepositId) {
        val walletGuid = reference("wallet_guid", V44_WalletTable).index()
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val status = customEnumeration(
            "status",
            "DepositStatus",
            { value -> V44_DepositStatus.valueOf(value as String) },
            { PGEnum("DepositStatus", it) },
        ).index()
        val symbolGuid = reference("symbol_guid", V44_SymbolTable).index()
        val amount = decimal("amount", 30, 0)
        val blockNumber = decimal("block_number", 30, 0).index()
        val transactionHash = varchar("transaction_hash", 10485760).uniqueIndex()
        val updatedAt = timestamp("updated_at").nullable()
        val updatedBy = varchar("updated_by", 10485760).nullable()
        val error = varchar("error", 10485760).nullable()
    }

    override fun run() {
        transaction {
            updateEnum<V44_DepositStatus>(listOf(V44_DepositTable.status), "DepositStatus")
        }
    }
}
