package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.ExecutionId
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum

@Suppress("ClassName")
class V90_OrderExecutionSide : Migration() {

    @Suppress("ClassName")
    enum class V90_OrderSide {
        Buy,
        Sell,
    }

    @Suppress("ClassName")
    object V90_OrderExecutionTable : GUIDTable<ExecutionId>("order_execution", ::ExecutionId) {
        val side = customEnumeration(
            "side",
            "OrderSide",
            { value -> V90_OrderSide.valueOf(value as String) },
            { PGEnum("OrderSide", it) },
        ).nullable()
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V90_OrderExecutionTable)
            exec(
                """
                UPDATE order_execution
                   SET side = "order".side
                  FROM "order"
                 WHERE order_execution.order_guid = "order".guid;
                 
                 ALTER TABLE order_execution ALTER COLUMN side SET NOT NULL;
                """.trimIndent(),
            )
        }
    }
}
