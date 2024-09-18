package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration

@Suppress("ClassName")
class V90_DepositWithdrawalTimestamps : Migration() {

    override fun run() {
        transaction {
            exec("""ALTER TABLE withdrawal ALTER COLUMN created_at TYPE timestamp WITH TIME ZONE USING created_at::timestamp WITH TIME ZONE""".trimIndent())
            exec("""ALTER TABLE withdrawal ALTER COLUMN updated_at TYPE timestamp WITH TIME ZONE USING updated_at::timestamp WITH TIME ZONE""".trimIndent())
            exec("""ALTER TABLE deposit ALTER COLUMN created_at TYPE timestamp WITH TIME ZONE USING created_at::timestamp WITH TIME ZONE""".trimIndent())
            exec("""ALTER TABLE deposit ALTER COLUMN updated_at TYPE timestamp WITH TIME ZONE USING updated_at::timestamp WITH TIME ZONE""".trimIndent())
            exec("""ALTER TABLE testnet_challenge_pnl ALTER COLUMN as_of TYPE timestamp WITH TIME ZONE USING as_of::timestamp WITH TIME ZONE""".trimIndent())
        }
    }
}
