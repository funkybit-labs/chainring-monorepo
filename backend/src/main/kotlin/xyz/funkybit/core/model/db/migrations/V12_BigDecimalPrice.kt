package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration

@Suppress("ClassName")
class V12_BigDecimalPrice : Migration() {
    override fun run() {
        transaction {
            exec(
                """
                    ALTER TABLE "order" ALTER COLUMN price TYPE numeric(30, 18) USING (price / 10 ^ 18)::numeric(30, 18);
                    ALTER TABLE trade ALTER COLUMN price TYPE numeric(30, 18) USING (price / 10 ^ 18)::numeric(30, 18);
                """.trimIndent(),
            )
        }
    }
}
