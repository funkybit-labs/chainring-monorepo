package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration

@Suppress("ClassName")
class V95_AddIndexToBroadcasterJobTable : Migration() {
    override fun run() {
        transaction {
            exec("CREATE INDEX broadcaster_job_created_at_index ON broadcaster_job (created_at)")
        }
    }
}
