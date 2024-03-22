package co.chainring.core.model.db

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID

@Serializable
@JvmInline
value class MarketId(override val value: String) : EntityId {
    constructor(baseSymbol: SymbolEntity, quoteSymbol: SymbolEntity) : this("${baseSymbol.name}/${quoteSymbol.name}")
    override fun toString(): String = value
}

object MarketTable : GUIDTable<MarketId>("market", ::MarketId) {
    val baseSymbolGuid = reference("base_symbol_guid", SymbolTable)
    val quoteSymbolGuid = reference("quote_symbol_guid", SymbolTable)
}

class MarketEntity(guid: EntityID<MarketId>) : GUIDEntity<MarketId>(guid) {
    var baseSymbolGuid by MarketTable.baseSymbolGuid
    var quoteSymbolGuid by MarketTable.quoteSymbolGuid

    companion object : EntityClass<MarketId, MarketEntity>(MarketTable)
}
