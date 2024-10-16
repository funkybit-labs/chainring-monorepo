package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.db.updateEnum
import xyz.funkybit.core.model.db.BlockchainTransactionId
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum
import xyz.funkybit.core.model.db.WithdrawalId

@Suppress("ClassName")
class V99_AddArchRollbackTransactionToWithdrawal : Migration() {

    @Suppress("ClassName")
    enum class V99_WithdrawalStatus {
        Pending,
        Sequenced,
        Settling,
        Complete,
        RollingBack,
        Failed,
    }

    @Suppress("ClassName")
    object V99_BlockchainTransactionTable : GUIDTable<BlockchainTransactionId>("blockchain_transaction", ::BlockchainTransactionId)

    @Suppress("ClassName")
    object V99_WithdrawalTable : GUIDTable<WithdrawalId>("withdrawal", ::WithdrawalId) {
        val status = customEnumeration(
            "status",
            "WithdrawalStatus",
            { value -> V99_WithdrawalStatus.valueOf(value as String) },
            { PGEnum("WithdrawalStatus", it) },
        ).index()
        val archRollbackTransactionGuid = reference(
            "arch_rollback_tx_guid",
            V99_BlockchainTransactionTable,
        ).nullable()
    }

    override fun run() {
        transaction {
            updateEnum<V99_WithdrawalStatus>(listOf(V99_WithdrawalTable.status), "WithdrawalStatus")
            SchemaUtils.createMissingTablesAndColumns(V99_WithdrawalTable)
        }
    }
}
