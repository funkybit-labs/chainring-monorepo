package xyz.funkybit.core.model.db

import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.SequencerUserId
import xyz.funkybit.core.sequencer.toSequencerId

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
    val sequencerId = long("sequencer_id").uniqueIndex()

    // is admin
    // nickname
    // avatar
}

class UserEntity(guid: EntityID<UserId>) : GUIDEntity<UserId>(guid) {

    companion object : EntityClass<UserId, UserEntity>(UserTable) {
        fun create(createdBy: Address): UserEntity {
            val userId = UserId.generate()
            return UserEntity.new(userId) {
                this.createdAt = Clock.System.now()
                this.createdBy = createdBy.canonicalize().toString()
                this.sequencerId = userId.toSequencerId()
            }
        }

        fun getBySequencerIds(sequencerIds: Set<SequencerUserId>): List<UserEntity> {
            return UserEntity.find {
                UserTable.sequencerId.inList(sequencerIds.map { it.value })
            }.toList()
        }

        fun getBySequencerId(sequencerId: SequencerUserId): UserEntity? {
            return UserEntity.find {
                UserTable.sequencerId.eq(sequencerId.value)
            }.firstOrNull()
        }
    }

    var sequencerId by UserTable.sequencerId.transform(
        toReal = { SequencerUserId(it) },
        toColumn = { it.value },
    )

    var createdAt by UserTable.createdAt
    var createdBy by UserTable.createdBy

    var updatedAt by UserTable.updatedAt
    var updatedBy by UserTable.updatedBy
}
