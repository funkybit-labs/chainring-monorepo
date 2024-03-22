package co.chainring.core.model.db

import co.chainring.core.model.Symbol
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID

@Serializable
@JvmInline
value class MarketId(override val value: String) : EntityId {
    constructor(baseSymbol: Symbol, quoteSymbol: Symbol) : this("$baseSymbol/$quoteSymbol")
    override fun toString(): String = value
}

object MarketTable : GUIDTable<MarketId>("market", ::MarketId) {
    val baseSymbol = varchar("base_symbol", 10485760)
    val quoteSymbol = varchar("quote_symbol", 10485760)
}

class MarketEntity(guid: EntityID<MarketId>) : GUIDEntity<MarketId>(guid) {
    var baseSymbol by MarketTable.baseSymbol
    var quoteSymbol by MarketTable.quoteSymbol

    companion object : EntityClass<MarketId, MarketEntity>(MarketTable)
}
