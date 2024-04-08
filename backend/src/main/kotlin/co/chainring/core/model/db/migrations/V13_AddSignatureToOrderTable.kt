package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.db.updateEnum
import co.chainring.core.model.db.EntityId
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.PGEnum
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V13_AddSignatureToOrderTable : Migration() {

    @Suppress("ClassName")
    enum class V13_OrderStatus {
        Open,
        Partial,
        Filled,
        Cancelled,
        Expired,
        Rejected,
        Failed,
        CrossesMarket,
    }

    @Serializable
    @JvmInline
    value class OrderId(override val value: String) : EntityId {
        override fun toString(): String = value
    }

    object V13_OrderTable : GUIDTable<OrderId>("order", ::OrderId) {

        val signature = varchar("signature", 10485760).nullable()
        val sequencerOrderId = long("sequencer_order_id").uniqueIndex().nullable()
        val status = customEnumeration(
            "status",
            "OrderStatus",
            { value -> V13_OrderStatus.valueOf(value as String) },
            { PGEnum("OrderStatus", it) },
        ).index()
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V13_OrderTable)
            exec("""UPDATE "order" SET signature = ''""")
            exec("""ALTER TABLE "order" ALTER COLUMN signature SET NOT NULL""")
            updateEnum<V13_OrderStatus>(listOf(V13_OrderTable.status), "OrderStatus")

            // smart contract changes not backward compatible in this version
            exec("DELETE from deployed_smart_contract where name = 'Exchange'")
        }
    }
}
