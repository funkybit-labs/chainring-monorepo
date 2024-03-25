package co.chainring.core.model.db

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.with
import org.jetbrains.exposed.sql.SizedIterable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll

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
    companion object : EntityClass<MarketId, MarketEntity>(MarketTable) {
        fun create(
            baseSymbol: SymbolEntity,
            quoteSymbol: SymbolEntity,
        ) = MarketEntity.new(MarketId(baseSymbol, quoteSymbol)) {
            this.baseSymbolGuid = baseSymbol.guid
            this.quoteSymbolGuid = quoteSymbol.guid
        }

        override fun all(): SizedIterable<MarketEntity> =
            table
                .selectAll()
                .orderBy(table.id, SortOrder.ASC)
                .notForUpdate()
                .let { wrapRows(it) }
                .with(MarketEntity::baseSymbol, MarketEntity::quoteSymbol)
    }

    var baseSymbolGuid by MarketTable.baseSymbolGuid
    var quoteSymbolGuid by MarketTable.quoteSymbolGuid

    var baseSymbol by SymbolEntity referencedOn MarketTable.baseSymbolGuid
    var quoteSymbol by SymbolEntity referencedOn MarketTable.quoteSymbolGuid
}
