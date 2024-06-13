package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.db.updateEnum
import co.chainring.core.model.db.DepositId
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.PGEnum
import co.chainring.core.model.db.SymbolTable
import co.chainring.core.model.db.WalletTable
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction

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

    object V44_DepositTable : GUIDTable<DepositId>("deposit", ::DepositId) {
        val walletGuid = reference("wallet_guid", WalletTable).index()
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val status = customEnumeration(
            "status",
            "DepositStatus",
            { value -> V44_DepositStatus.valueOf(value as String) },
            { PGEnum("DepositStatus", it) },
        ).index()
        val symbolGuid = reference("symbol_guid", SymbolTable).index()
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
