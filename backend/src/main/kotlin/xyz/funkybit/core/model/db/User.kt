package xyz.funkybit.core.model.db

import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import xyz.funkybit.core.model.Address

@Serializable
@JvmInline
value class UserId(override val value: String) : EntityId {
    companion object {
        fun generate(): UserId = UserId(TypeId.generate("user").toString())
    }

    override fun toString(): String = value
}

object UserTable : GUIDTable<UserId>("user", ::UserId) {
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
    val updatedAt = timestamp("updated_at").nullable()
    val updatedBy = varchar("updated_by", 10485760).nullable()

    // is admin
    // nickname
    // avatar
}

class UserEntity(guid: EntityID<UserId>) : GUIDEntity<UserId>(guid) {

    companion object : EntityClass<UserId, UserEntity>(UserTable) {
        fun create(createdBy: Address): UserEntity {
            return UserEntity.new(UserId.generate()) {
                this.createdAt = Clock.System.now()
                this.createdBy = createdBy.canonicalize().toString()
            }
        }
    }

    var createdAt by UserTable.createdAt
    var createdBy by UserTable.createdBy

    var updatedAt by UserTable.updatedAt
    var updatedBy by UserTable.updatedBy
}
