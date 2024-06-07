package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.OrderId
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V39_AddSequencerTimeToOrder : Migration() {

    object V39_Order : GUIDTable<OrderId>(
        "order",
        ::OrderId,
    ) {
        val sequencerTimeNs = decimal("sequencer_time_ns", 30, 0).nullable()
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V39_Order)
            exec("""UPDATE "order" SET sequencer_time_ns = 0""")
            exec("""ALTER TABLE "order" ALTER COLUMN sequencer_time_ns SET NOT NULL""")
        }
    }
}
