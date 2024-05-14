package co.chainring.core.model.db

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.upsert

object KeyValueStore : Table("key_value_store") {

    var key = varchar("key", length = 10485760).uniqueIndex()
    var value = varchar("value", length = 10485760).nullable()

    fun getValue(key: String): String? {
        return select(value)
            .where(KeyValueStore.key.eq(key)).singleOrNull()
            ?.let { it[value] }
    }

    fun setValue(key: String, value: String?) {
        KeyValueStore.upsert {
            it[KeyValueStore.key] = key
            it[KeyValueStore.value] = value
        }
    }

    fun getInt(key: String): Int? {
        return getValue(key)?.toInt()
    }

    fun setInt(key: String, value: Int) {
        setValue(key, value.toString())
    }

    fun getLong(key: String): Long? {
        return getValue(key)?.toLong()
    }

    fun setLong(key: String, value: Long) {
        setValue(key, value.toString())
    }

    fun remove(key: String) {
        KeyValueStore.deleteWhere {
            KeyValueStore.key.eq(key)
        }
    }
}
