package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.BlockchainTransactionId
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.WithdrawalId

@Suppress("ClassName")
class V86_AddArchTransactionToWithdrawal : Migration() {

    @Suppress("ClassName")
    object V86_BlockchainTransactionTable : GUIDTable<BlockchainTransactionId>("blockchain_transaction", ::BlockchainTransactionId)

    @Suppress("ClassName")
    object V86_WithdrawalTable : GUIDTable<WithdrawalId>("withdrawal", ::WithdrawalId) {
        val archTransactionGuid = reference(
            "arch_tx_guid",
            V86_BlockchainTransactionTable,
        ).nullable()
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V86_WithdrawalTable)
        }
    }
}
