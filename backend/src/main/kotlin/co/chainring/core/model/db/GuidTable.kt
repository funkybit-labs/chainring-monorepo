package co.chainring.core.model.db

import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table

open class GUIDTable<T : EntityId>(name: String, deserialize: (String) -> T) : IdTable<T>(name) {
    val guid = guid("guid", deserialize).entityId()

    override val primaryKey: PrimaryKey? = PrimaryKey(guid)

    override val id: Column<EntityID<T>> = guid
}

abstract class GUIDEntity<T : EntityId>(val guid: EntityID<T>) : Entity<T>(guid)

fun <T : EntityId> Table.guid(
    name: String,
    deserialize: (String) -> T,
): Column<T> = registerColumn(name, EntityIdColumnType(deserialize))
