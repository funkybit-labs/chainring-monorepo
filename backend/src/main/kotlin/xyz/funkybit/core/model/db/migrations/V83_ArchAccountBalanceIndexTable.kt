package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.ArchAccountBalanceIndexId
import xyz.funkybit.core.model.db.ArchAccountId
import xyz.funkybit.core.model.db.BlockchainTransactionId
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum
import xyz.funkybit.core.model.db.WalletId
import xyz.funkybit.core.model.db.enumDeclaration

@Suppress("ClassName")
class V83_ArchAccountBalanceIndexTable : Migration() {

    object V83_WalletTable : GUIDTable<WalletId>("wallet", ::WalletId)

    object V83_ArchAccountTable : GUIDTable<ArchAccountId>("arch_account", ::ArchAccountId)

    object V83_BlockchainTransactionTable : GUIDTable<BlockchainTransactionId>("blockchain_transaction", ::BlockchainTransactionId)

    enum class V83_ArchAccountBalanceIndexStatus {
        Pending,
        Assigning,
        Assigned,
        Failed,
    }

    object V83_ArchAccountBalanceIndexTable : GUIDTable<ArchAccountBalanceIndexId>(
        "arch_account_balance_index",
        ::ArchAccountBalanceIndexId,
    ) {
        val walletGuid = reference("wallet_guid", V83_WalletTable).index()
        val archAccountGuid = reference("arch_account_guid", V83_ArchAccountTable)
        val addressIndex = integer("address_index")
        val createdAt = timestamp("created_at")
        val updatedAt = timestamp("updated_at").nullable()
        val status = customEnumeration(
            "status",
            "ArchAccountBalanceIndexStatus",
            { value -> V83_ArchAccountBalanceIndexStatus.valueOf(value as String) },
            { PGEnum("ArchAccountBalanceIndexStatus", it) },
        ).index()
        val archTransactionGuid = reference(
            "arch_tx_guid",
            V83_BlockchainTransactionTable,
        ).nullable()

        init {
            uniqueIndex(
                customIndexName = "uix_bal_idx_arch_account_wallet_guid",
                columns = arrayOf(walletGuid, archAccountGuid),
            )
        }
    }

    override fun run() {
        transaction {
            exec("CREATE TYPE ArchAccountBalanceIndexStatus AS ENUM (${enumDeclaration<V83_ArchAccountBalanceIndexStatus>()})")
            SchemaUtils.createMissingTablesAndColumns(V83_ArchAccountBalanceIndexTable)
        }
    }
}
