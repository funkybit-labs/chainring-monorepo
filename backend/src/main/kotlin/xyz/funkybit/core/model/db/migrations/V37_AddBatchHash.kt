package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.BlockchainTransactionId
import xyz.funkybit.core.model.db.GUIDTable

@Suppress("ClassName")
class V37_AddBatchHash : Migration() {

    object V37_BlockchainTransactionTable : GUIDTable<BlockchainTransactionId>(
        "blockchain_transaction",
        ::BlockchainTransactionId,
    ) {
        val batchHash = varchar("batch_hash", 10485760).nullable()
        val lastSeenBlock = decimal("last_seen_block", 30, 0).nullable()
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V37_BlockchainTransactionTable)
        }
    }
}
