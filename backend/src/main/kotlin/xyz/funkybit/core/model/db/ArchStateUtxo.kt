package xyz.funkybit.core.model.db

import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import xyz.funkybit.core.model.bitcoin.UtxoId

@Serializable
@JvmInline
value class ArchStateUtxoId(override val value: String) : EntityId {
    companion object {
        fun generate(): ArchStateUtxoId = ArchStateUtxoId(TypeId.generate("archstate").toString())
    }

    override fun toString(): String = value
}

enum class StateUtxoType {
    Exchange,
    Token,
}

enum class StateUtxoStatus {
    Onboarding,
    Onboarded,
    Initializing,
    Complete,
    Failed,
}

object ArchStateUtxoTable : GUIDTable<ArchStateUtxoId>("arch_state_utxo", ::ArchStateUtxoId) {
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
    val updatedAt = timestamp("updated_at").nullable()
    val updatedBy = varchar("updated_by", 10485760).nullable()
    val utxoId = varchar("utxo_id", 10485760).uniqueIndex()
    val creationTxId = varchar("creation_tx_id", 10485760)
    val type = customEnumeration(
        "type",
        "StateUtxoType",
        { value -> StateUtxoType.valueOf(value as String) },
        { PGEnum("StateUtxoType", it) },
    )
    val symbolGuid = reference("symbol_guid", SymbolTable).nullable().uniqueIndex()
    val initializationTxId = varchar("initialization_tx_id", 10485760).nullable()
    val status = customEnumeration(
        "status",
        "StateUtxoStatus",
        { value -> StateUtxoStatus.valueOf(value as String) },
        { PGEnum("StateUtxoStatus", it) },
    )
}

@JvmInline
value class ArchStateUtxoLogId(override val value: String) : EntityId {
    companion object {
        fun generate(): ArchStateUtxoLogId = ArchStateUtxoLogId(TypeId.generate("asulog").toString())
    }

    override fun toString(): String = value
}

object ArchStateUtxoLogTable : GUIDTable<ArchStateUtxoLogId>("arch_state_utxo_log", ::ArchStateUtxoLogId) {
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
    val beforeUtxoId = varchar("before_utxo_id", 10485760)
    val afterUtxoId = varchar("after_utxo_id", 10485760)
    val archTxId = varchar("arch_tx_id", 10485760)
    val archStateUtxoGuid = reference(
        "arch_state_utxo_guid",
        ArchStateUtxoTable,
    )
}

class ArchStateUtxoEntity(guid: EntityID<ArchStateUtxoId>) : GUIDEntity<ArchStateUtxoId>(guid) {
    companion object : EntityClass<ArchStateUtxoId, ArchStateUtxoEntity>(ArchStateUtxoTable) {
        fun create(utxoId: UtxoId, creationTxId: TxHash, symbolEntity: SymbolEntity? = null): ArchStateUtxoEntity {
            return ArchStateUtxoEntity.new(ArchStateUtxoId.generate()) {
                this.utxoId = utxoId
                this.creationTxId = creationTxId
                this.type = symbolEntity?.let { StateUtxoType.Token } ?: StateUtxoType.Exchange
                this.status = StateUtxoStatus.Onboarding
                this.createdAt = Clock.System.now()
                this.createdBy = "system"
                this.symbolGuid = symbolEntity?.guid
            }
        }

        fun findByUtxoId(utxoId: UtxoId): ArchStateUtxoEntity? {
            return ArchStateUtxoEntity.find {
                ArchStateUtxoTable.utxoId.eq(utxoId.value)
            }.firstOrNull()
        }

        fun findProgramStateUtxo(): ArchStateUtxoEntity? {
            return ArchStateUtxoEntity.find {
                ArchStateUtxoTable.type.eq(StateUtxoType.Exchange)
            }.firstOrNull()
        }

        fun getProgramStateUtxo(): ArchStateUtxoEntity {
            return ArchStateUtxoEntity.find {
                ArchStateUtxoTable.type.eq(StateUtxoType.Exchange)
            }.single()
        }

        fun findTokenStateUtxo(symbolEntity: SymbolEntity): ArchStateUtxoEntity? {
            return ArchStateUtxoEntity.find {
                ArchStateUtxoTable.type.eq(StateUtxoType.Token) and
                    ArchStateUtxoTable.symbolGuid.eq(symbolEntity.id)
            }.firstOrNull()
        }

        fun findAllTokenStateUtxo(): List<ArchStateUtxoEntity> {
            return ArchStateUtxoEntity.find {
                ArchStateUtxoTable.type.eq(StateUtxoType.Token)
            }.toList()
        }

        fun createLogEntry(stateUtxo: ArchStateUtxoEntity, utxoId: UtxoId) {
            ArchStateUtxoLogTable.insert {
                it[this.guid] = ArchStateUtxoLogId.generate()
                it[this.archTxId] = stateUtxo.initializationTxId!!.value
                it[this.archStateUtxoGuid] = stateUtxo.guid
                it[this.createdAt] = Clock.System.now()
                it[this.createdBy] = "system"
                it[this.afterUtxoId] = utxoId.value
                it[this.beforeUtxoId] = stateUtxo.utxoId.value
            }
        }
    }

    fun markAsOnboarded() {
        this.status = StateUtxoStatus.Onboarded
        this.updatedAt = Clock.System.now()
        this.updatedBy = "system"
    }

    fun markAsComplete() {
        this.status = StateUtxoStatus.Complete
        this.updatedAt = Clock.System.now()
        this.updatedBy = "system"
    }

    fun markAsFailed() {
        this.status = StateUtxoStatus.Failed
        this.updatedAt = Clock.System.now()
        this.updatedBy = "system"
    }

    fun markAsInitializing(archTxId: TxHash) {
        this.status = StateUtxoStatus.Initializing
        this.initializationTxId = archTxId
        this.updatedAt = Clock.System.now()
    }

    fun updateUtxoId(utxoId: UtxoId) {
        createLogEntry(this, utxoId)
        this.utxoId = utxoId
        this.updatedAt = Clock.System.now()
    }

    var utxoId by ArchStateUtxoTable.utxoId.transform(
        toReal = { UtxoId(it) },
        toColumn = { it.value },
    )
    var creationTxId by ArchStateUtxoTable.creationTxId.transform(
        toReal = { TxHash(it) },
        toColumn = { it.value },
    )
    var type by ArchStateUtxoTable.type
    var status by ArchStateUtxoTable.status

    var symbolGuid by ArchStateUtxoTable.symbolGuid
    var symbol by SymbolEntity optionalReferencedOn ArchStateUtxoTable.symbolGuid

    var createdAt by ArchStateUtxoTable.createdAt
    var createdBy by ArchStateUtxoTable.createdBy
    var updatedAt by ArchStateUtxoTable.updatedAt
    var updatedBy by ArchStateUtxoTable.updatedBy

    var initializationTxId by ArchStateUtxoTable.initializationTxId.transform(
        toReal = { it?.let { TxHash(it) } },
        toColumn = { it?.value },
    )
}
