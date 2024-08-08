package xyz.funkybit.core.model.db.migrations

import org.http4k.format.KotlinxSerialization
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.BlockchainTransactionData
import xyz.funkybit.core.model.db.BlockchainTransactionId
import xyz.funkybit.core.model.db.ChainTable
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum
import xyz.funkybit.core.model.db.enumDeclaration

@Suppress("ClassName")
class V19_BlockchainTransaction : Migration() {

    enum class V19_BlockchainTransactionStatus {
        Pending,
        Submitted,
        Confirmed,
        Completed,
        Failed,
    }

    object V19_BlockchainTransactionTable : GUIDTable<BlockchainTransactionId>(
        "blockchain_transaction",
        ::BlockchainTransactionId,
    ) {
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val updatedAt = timestamp("updated_at").nullable()
        val updatedBy = varchar("updated_by", 10485760).nullable()
        val status = customEnumeration(
            "status",
            "BlockchainTransactionStatus",
            { value -> V19_BlockchainTransactionStatus.valueOf(value as String) },
            { PGEnum("BlockchainTransactionStatus", it) },
        ).index()
        val error = varchar("error", 10485760).nullable()
        val sequenceId = integer("sequence_id").autoIncrement()
        val txHash = varchar("tx_hash", 10485760).nullable()
        val transactionData = jsonb<BlockchainTransactionData>("transaction_data", KotlinxSerialization.json)
        val chainId = reference("chain_id", ChainTable)
        val blockNumber = decimal("block_number", 30, 0).nullable()
        val gasAccountFee = decimal("gas_account_fee", 30, 0).nullable()
        val actualGas = decimal("actual_gas", 30, 0).nullable()
    }

    override fun run() {
        transaction {
            exec("CREATE TYPE BlockchainTransactionStatus AS ENUM (${enumDeclaration<V19_BlockchainTransactionStatus>()})")
            SchemaUtils.createMissingTablesAndColumns(V19_BlockchainTransactionTable)
        }
    }
}
