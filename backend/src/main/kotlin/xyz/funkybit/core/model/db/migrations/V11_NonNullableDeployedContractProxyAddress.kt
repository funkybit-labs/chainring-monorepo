package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration

@Suppress("ClassName")
class V11_NonNullableDeployedContractProxyAddress : Migration() {
    override fun run() {
        transaction {
            exec(
                """
                DELETE FROM deployed_smart_contract WHERE proxy_address IS NULL;
                ALTER TABLE deployed_smart_contract ALTER COLUMN proxy_address SET NOT NULL;
                """.trimIndent(),
            )
        }
    }
}
