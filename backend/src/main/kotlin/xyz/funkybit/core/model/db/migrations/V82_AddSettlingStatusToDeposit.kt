package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.db.updateEnum
import xyz.funkybit.core.model.db.BlockchainTransactionTable
import xyz.funkybit.core.model.db.DepositId
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum

@Suppress("ClassName")
class V82_AddSettlingStatusToDeposit : Migration() {
    @Suppress("ClassName")
    enum class V82_DepositStatus {
        Pending,
        Confirmed,
        SentToSequencer,
        Complete,
        Failed,
        Settling,
    }

    object V82_DepositTable : GUIDTable<DepositId>("deposit", ::DepositId) {
        val status = customEnumeration(
            "status",
            "DepositStatus",
            { value -> V82_DepositStatus.valueOf(value as String) },
            { PGEnum("DepositStatus", it) },
        ).index()
        val archTransactionGuid = reference(
            "arch_tx_guid",
            BlockchainTransactionTable,
        ).nullable()
    }

    override fun run() {
        transaction {
            updateEnum<V82_DepositStatus>(listOf(V82_DepositTable.status), "DepositStatus")
            SchemaUtils.createMissingTablesAndColumns(V82_DepositTable)
        }
    }
}
