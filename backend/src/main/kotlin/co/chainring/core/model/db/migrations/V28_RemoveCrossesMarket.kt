package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.db.updateEnum
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.PGEnum
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V28_RemoveCrossesMarket : Migration() {

    @Suppress("ClassName")
    enum class V28_OrderStatus {
        Open,
        Partial,
        Filled,
        Cancelled,
        Expired,
        Rejected,
        Failed,
    }

    object V28_OrderTable : GUIDTable<OrderId>("order", ::OrderId) {
        val status = customEnumeration(
            "status",
            "OrderStatus",
            { value -> V28_OrderStatus.valueOf(value as String) },
            { PGEnum("OrderStatus", it) },
        ).index()
    }

    override fun run() {
        transaction {
            updateEnum<V28_OrderStatus>(listOf(V28_OrderTable.status), "OrderStatus")
        }
    }
}
