package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration

@Suppress("ClassName")
class V89_RewardTimestampWithTimezone : Migration() {

    override fun run() {
        transaction {
            exec(
                """
                ALTER TABLE testnet_challenge_user_reward 
                ALTER COLUMN created_at TYPE timestamp WITH TIME ZONE using created_at::timestamp WITH TIME ZONE
                """.trimIndent(),
            )
        }
    }
}
