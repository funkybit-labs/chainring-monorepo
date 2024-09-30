package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.db.updateEnum
import xyz.funkybit.core.model.db.DepositId
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum

@Suppress("ClassName")
class V97_AddSentToArchStatusToDeposit : Migration() {

    @Suppress("ClassName")
    enum class V97_DepositStatus {
        Pending,
        Confirmed,
        SentToSequencer,
        Complete,
        Failed,
        Settling,
        SentToArch,
    }

    @Suppress("ClassName")
    object V97_DepositTable : GUIDTable<DepositId>("deposit", ::DepositId) {
        val status = customEnumeration(
            "status",
            "DepositStatus",
            { value -> V97_DepositStatus.valueOf(value as String) },
            { PGEnum("DepositStatus", it) },
        ).index()
    }

    override fun run() {
        transaction {
            updateEnum<V97_DepositStatus>(listOf(V97_DepositTable.status), "DepositStatus")
        }
    }
}
