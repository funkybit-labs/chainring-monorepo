package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.db.updateEnum
import co.chainring.core.model.db.DepositId
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.PGEnum
import org.jetbrains.exposed.sql.transactions.transaction

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
