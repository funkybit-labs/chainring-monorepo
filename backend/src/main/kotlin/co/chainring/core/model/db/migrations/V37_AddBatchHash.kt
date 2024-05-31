package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.BlockchainTransactionId
import co.chainring.core.model.db.GUIDTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

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
