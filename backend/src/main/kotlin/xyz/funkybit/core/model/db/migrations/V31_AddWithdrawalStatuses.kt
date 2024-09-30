package xyz.funkybit.core.model.db.migrations

import org.http4k.format.KotlinxSerialization
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.db.updateEnum
import xyz.funkybit.core.model.db.BlockchainTransactionId
import xyz.funkybit.core.model.db.BlockchainTransactionTable
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum
import xyz.funkybit.core.model.db.WithdrawalId
import xyz.funkybit.core.model.evm.EIP712Transaction

@Suppress("ClassName")
class V31_AddWithdrawalStatuses : Migration() {

    @Suppress("ClassName")
    enum class V31_WithdrawalStatus {
        Pending,
        Sequenced,
        Settling,
        Complete,
        Failed,
    }

    @Suppress("ClassName")
    object V31_BlockchainTransactionTable : GUIDTable<BlockchainTransactionId>("blockchain_transaction", ::BlockchainTransactionId)

    @Suppress("ClassName")
    object V31_WithdrawalTable : GUIDTable<WithdrawalId>("withdrawal", ::WithdrawalId) {
        val status = customEnumeration(
            "status",
            "WithdrawalStatus",
            { value -> V31_WithdrawalStatus.valueOf(value as String) },
            { PGEnum("WithdrawalStatus", it) },
        ).index()
        val blockchainTransactionGuid = reference(
            "tx_guid",
            BlockchainTransactionTable,
        ).nullable()
        val sequenceId = integer("sequence_id").autoIncrement()
        val transactionData = jsonb<EIP712Transaction>("transaction_data", KotlinxSerialization.json).nullable()

        init {
            check("tx_when_settling") {
                blockchainTransactionGuid.isNotNull().or(
                    status.neq(V31_WithdrawalStatus.Settling),
                )
            }
        }
    }

    override fun run() {
        transaction {
            updateEnum<V31_WithdrawalStatus>(listOf(V31_WithdrawalTable.status), "WithdrawalStatus")
            SchemaUtils.createMissingTablesAndColumns(V31_WithdrawalTable)
        }
    }
}
