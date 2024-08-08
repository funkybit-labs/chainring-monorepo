package xyz.funkybit.core.model.db

import org.jetbrains.exposed.sql.VarCharColumnType

interface EntityId : Comparable<EntityId> {
    val value: String

    override fun compareTo(other: EntityId): Int = compareValuesBy(this, other) { it.value }
}

class EntityIdColumnType<T : EntityId>(val deserialize: (String) -> T) : VarCharColumnType(colLength = 10485760) {
    override fun valueFromDB(value: Any): Any {
        return when (val v = super.valueFromDB(value)) {
            is String -> deserialize(v)
            else -> v
        }
    }

    override fun notNullValueToDB(value: Any): Any = value.toString()
}
