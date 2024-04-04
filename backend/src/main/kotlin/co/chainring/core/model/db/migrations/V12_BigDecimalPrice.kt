package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V12_BigDecimalPrice : Migration() {
    override fun run() {
        transaction {
            exec(
                """
                    ALTER TABLE "order" ALTER COLUMN price TYPE numeric(30, 18) USING price::numeric(30, 18);
                    ALTER TABLE trade ALTER COLUMN price TYPE numeric(30, 18) USING price::numeric(30, 18);
                """.trimIndent(),
            )
        }
    }
}
