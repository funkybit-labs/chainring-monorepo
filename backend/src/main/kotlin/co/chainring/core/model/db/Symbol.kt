package co.chainring.core.model.db

import co.chainring.core.model.Address
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

@Serializable
@JvmInline
value class SymbolId(override val value: String) : EntityId {
    constructor(chainId: ChainId, name: String) : this("s_${name.lowercase()}_$chainId")

    override fun toString(): String = value
}

object SymbolTable : GUIDTable<SymbolId>("symbol", ::SymbolId) {
    val name = varchar("name", 10485760)
    val chainId = reference("chain_id", ChainTable)
    val contractAddress = varchar("contract_address", 10485760).nullable()
    val decimals = ubyte("decimals")
    val description = varchar("description", 10485760)
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)

    init {
        uniqueIndex(
            customIndexName = "uix_symbol_chain_id_name",
            columns = arrayOf(chainId, name),
        )
        uniqueIndex(
            customIndexName = "uix_symbol_chain_id_contract_address",
            columns = arrayOf(chainId, contractAddress),
        )
    }
}

class SymbolEntity(guid: EntityID<SymbolId>) : GUIDEntity<SymbolId>(guid) {
    companion object : EntityClass<SymbolId, SymbolEntity>(SymbolTable) {
        fun create(
            name: String,
            chainId: ChainId,
            contractAddress: Address?,
            decimals: UByte,
            description: String,
        ) = SymbolEntity.new(SymbolId(chainId, name)) {
            this.name = name
            this.chainId = EntityID(chainId, ChainTable)
            this.contractAddress = contractAddress
            this.decimals = decimals
            this.description = description
            this.createdAt = Clock.System.now()
            this.createdBy = "system"
        }

        fun forChain(chainId: ChainId): List<SymbolEntity> =
            SymbolEntity
                .find { SymbolTable.chainId.eq(chainId) }
                .orderBy(Pair(SymbolTable.name, SortOrder.ASC))
                .toList()

        fun forChainAndContractAddress(chainId: ChainId, contractAddress: Address?) =
            SymbolEntity
                .find { SymbolTable.chainId.eq(chainId).and(contractAddress?.let { SymbolTable.contractAddress.eq(it.value) } ?: SymbolTable.contractAddress.isNull()) }
                .single()
    }

    var name by SymbolTable.name
    var chainId by SymbolTable.chainId
    var contractAddress by SymbolTable.contractAddress.transform(
        toColumn = { it?.value },
        toReal = { it?.let { Address(it) } },
    )
    var decimals by SymbolTable.decimals
    var description by SymbolTable.description
    var createdAt by SymbolTable.createdAt
    var createdBy by SymbolTable.createdBy
}
