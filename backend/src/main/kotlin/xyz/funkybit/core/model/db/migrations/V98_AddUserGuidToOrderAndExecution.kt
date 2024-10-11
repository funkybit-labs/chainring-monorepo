package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration

@Suppress("ClassName")
class V98_AddUserGuidToOrderAndExecution : Migration() {
    override fun run() {
        transaction {
            exec(
                """
                ALTER TABLE "order" ADD COLUMN user_guid CHARACTER VARYING(10485760);
                UPDATE "order" SET user_guid = (
                    SELECT user_guid FROM wallet WHERE wallet.guid = "order".wallet_guid
                );
                ALTER TABLE "order" ADD CONSTRAINT fk_order_user_guid__guid FOREIGN KEY (user_guid) REFERENCES "user"(guid);
                ALTER TABLE "order" ALTER COLUMN user_guid SET NOT NULL;
                CREATE INDEX order_user_guid_created_at_index ON "order" (user_guid, created_at);
                """.trimIndent(),
            )

            exec(
                """
                ALTER TABLE order_execution ADD COLUMN user_guid CHARACTER VARYING(10485760);
                UPDATE order_execution SET user_guid = (
                    SELECT user_guid FROM "order" WHERE "order".guid = order_execution.order_guid
                );
                ALTER TABLE order_execution ADD CONSTRAINT fk_order_execution_user_guid__guid FOREIGN KEY (user_guid) REFERENCES "user"(guid);
                ALTER TABLE order_execution ALTER COLUMN user_guid SET NOT NULL;
                """.trimIndent(),
            )
        }
    }
}
