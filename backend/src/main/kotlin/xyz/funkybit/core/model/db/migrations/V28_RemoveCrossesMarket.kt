package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.db.updateEnum
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.OrderId
import xyz.funkybit.core.model.db.PGEnum

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
