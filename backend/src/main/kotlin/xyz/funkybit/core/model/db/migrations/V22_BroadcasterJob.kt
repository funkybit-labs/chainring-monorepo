package xyz.funkybit.core.model.db.migrations

import org.http4k.format.KotlinxSerialization
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.BroadcasterJobId
import xyz.funkybit.core.model.db.BroadcasterNotification
import xyz.funkybit.core.model.db.GUIDTable

@Suppress("ClassName")
class V22_BroadcasterJob : Migration() {

    object V22_BroadcasterJobTable : GUIDTable<BroadcasterJobId>(
        "broadcaster_job",
        ::BroadcasterJobId,
    ) {
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val notificationData = jsonb<List<BroadcasterNotification>>("notification_data", KotlinxSerialization.json)
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V22_BroadcasterJobTable)
        }
    }
}
