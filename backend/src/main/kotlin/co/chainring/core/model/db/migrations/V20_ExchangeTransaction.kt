package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.evm.EIP712Transaction
import co.chainring.core.model.db.BlockchainTransactionId
import co.chainring.core.model.db.ChainTable
import co.chainring.core.model.db.EntityId
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.PGEnum
import co.chainring.core.model.db.enumDeclaration
import org.http4k.format.KotlinxSerialization
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V20_ExchangeTransaction : Migration() {

    @JvmInline
    value class V20_ExchangeTransactionId(override val value: String) : EntityId

    object V20_BlockchainTransactionTable : GUIDTable<BlockchainTransactionId>(
        "blockchain_transaction",
        ::BlockchainTransactionId,
    )

    enum class V20_ExchangeTransactionStatus {
        Pending,
        Assigned,
    }

    object V20_ExchangeTransactionTable : GUIDTable<V20_ExchangeTransactionId>(
        "exchange_transaction",
        ::V20_ExchangeTransactionId,
    ) {
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val updatedAt = timestamp("updated_at").nullable()
        val updatedBy = varchar("updated_by", 10485760).nullable()
        val sequenceId = integer("sequence_id").autoIncrement()
        val chainId = reference("chain_id", ChainTable)
        val transactionData = jsonb<EIP712Transaction>("transaction_data", KotlinxSerialization.json)
        val status = customEnumeration(
            "status",
            "ExchangeTransactionStatus",
            { value -> V20_ExchangeTransactionStatus.valueOf(value as String) },
            { PGEnum("ExchangeTransactionStatus", it) },
        )
        val blockchainTransactionGuid = reference(
            "blockchain_transaction_guid",
            V20_BlockchainTransactionTable,
        ).index().nullable()

        init {
            V20_ExchangeTransactionTable.index(
                customIndexName = "exchange_transaction_pending_status",
                columns = arrayOf(status),
                filterCondition = {
                    status.eq(V20_ExchangeTransactionStatus.Pending)
                },
            )
        }
    }

    override fun run() {
        transaction {
            exec("CREATE TYPE ExchangeTransactionStatus AS ENUM (${enumDeclaration<V20_ExchangeTransactionStatus>()})")
            SchemaUtils.createMissingTablesAndColumns(V20_ExchangeTransactionTable)
        }
    }
}
