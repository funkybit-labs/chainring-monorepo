package co.chainring.core.model.db

import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.upsert

object KeyValueStore : Table("key_value_store") {

    var key = varchar("key", length = 10485760).uniqueIndex()
    var value = varchar("value", length = 10485760).nullable()

    fun getValue(key: String): String? {
        return KeyValueStore
            .slice(value)
            .select(
                KeyValueStore.key.eq(key),
            ).singleOrNull()
            ?.let { it[value] }
    }

    fun setValue(key: String, value: String?) {
        KeyValueStore.upsert {
            it[KeyValueStore.key] = key
            it[KeyValueStore.value] = value
        }
    }

    fun getInstant(key: String): Instant? {
        return getValue(key)?.let { Instant.fromEpochMilliseconds(it.toLong()) }
    }

    fun setInstant(key: String, value: Instant?) {
        setValue(key, value?.toEpochMilliseconds().toString())
    }

    fun remove(key: String) {
        KeyValueStore.deleteWhere {
            KeyValueStore.key.eq(key)
        }
    }
}
