package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.OrderId

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
