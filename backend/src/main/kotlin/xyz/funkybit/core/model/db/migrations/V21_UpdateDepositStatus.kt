package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.db.updateEnum
import xyz.funkybit.core.model.db.DepositId
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum

@Suppress("ClassName")
class V21_UpdateDepositStatus : Migration() {

    enum class V21_DepositStatus {
        Pending,
        Confirmed,
        Complete,
        Failed,
    }

    object V21_DepositTable : GUIDTable<DepositId>("deposit", ::DepositId) {
        val status = customEnumeration(
            "status",
            "DepositStatus",
            { value -> V21_DepositStatus.valueOf(value as String) },
            { PGEnum("DepositStatus", it) },
        ).index()
    }

    override fun run() {
        transaction {
            updateEnum<V21_DepositStatus>(listOf(V21_DepositTable.status), "DepositStatus")
        }
    }
}
