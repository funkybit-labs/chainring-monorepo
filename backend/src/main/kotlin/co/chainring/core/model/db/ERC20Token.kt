package co.chainring.core.model.db

import co.chainring.core.model.Address
import co.chainring.core.model.Symbol
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.selectAll

@Serializable
@JvmInline
value class ERC20TokenId(override val value: String) : EntityId {
    constructor(chainId: ChainId, address: Address) : this("erc20_${chainId}_${address.value}")

    override fun toString(): String = value
}

object ERC20TokenTable : GUIDTable<ERC20TokenId>("erc20_token", ::ERC20TokenId) {
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
    val name = varchar("name", 10485760)
    val symbol = varchar("symbol", 10485760)
    val chainId = reference("chain_id", ChainTable)
    val address = varchar("address", 10485760)
    val decimals = ubyte("decimals")

    init {
        uniqueIndex(
            customIndexName = "erc20_token_address_chain",
            columns = arrayOf(address, chainId),
        )
    }
}

class ERC20TokenEntity(guid: EntityID<ERC20TokenId>) : GUIDEntity<ERC20TokenId>(guid) {
    companion object : EntityClass<ERC20TokenId, ERC20TokenEntity>(ERC20TokenTable) {
        fun create(
            symbol: Symbol,
            name: String,
            chainId: ChainId,
            address: Address,
            decimals: UByte,
        ) = ERC20TokenEntity.new(ERC20TokenId(chainId, address)) {
            this.createdAt = Clock.System.now()
            this.createdBy = "system"
            this.symbol = symbol
            this.name = name
            this.chainId = EntityID(chainId, ChainTable)
            this.address = address
            this.decimals = decimals
        }

        fun forChain(chainId: ChainId): List<ERC20TokenEntity> =
            wrapRows(
                ERC20TokenTable.selectAll()
                    .where { ERC20TokenTable.chainId.eq(chainId) }
                    .orderBy(table.id, SortOrder.ASC)
                    .notForUpdate(),
            ).toList()
    }

    var createdAt by ERC20TokenTable.createdAt
    var createdBy by ERC20TokenTable.createdBy
    var name by ERC20TokenTable.name
    var symbol by ERC20TokenTable.symbol.transform(
        toColumn = { it.value },
        toReal = { Symbol(it) },
    )
    var chainId by ERC20TokenTable.chainId
    var address by ERC20TokenTable.address.transform(
        toColumn = { it.value },
        toReal = { Address(it) },
    )
    var decimals by ERC20TokenTable.decimals
}
