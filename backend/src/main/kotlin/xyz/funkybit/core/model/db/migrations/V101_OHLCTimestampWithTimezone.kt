package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration

@Suppress("ClassName")
class V101_OHLCTimestampWithTimezone : Migration() {

    override fun run() {
        transaction {
            exec("ALTER TABLE ohlc ALTER COLUMN start TYPE timestamp with time zone using start::timestamp with time zone")
            exec("ALTER TABLE ohlc ALTER COLUMN first_trade TYPE timestamp with time zone using first_trade::timestamp with time zone")
            exec("ALTER TABLE ohlc ALTER COLUMN last_trade TYPE timestamp with time zone using last_trade::timestamp with time zone")
            exec("ALTER TABLE ohlc ALTER COLUMN created_at TYPE timestamp with time zone using created_at::timestamp with time zone")
            exec("ALTER TABLE ohlc ALTER COLUMN updated_at TYPE timestamp with time zone using updated_at::timestamp with time zone")
        }
    }
}
