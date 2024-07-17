package co.chainring.core.model.db

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.with
import org.jetbrains.exposed.sql.SizedIterable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import java.math.BigDecimal
import java.math.BigInteger

@Serializable
@JvmInline
value class MarketId(override val value: String) : EntityId {
    constructor(baseSymbol: SymbolEntity, quoteSymbol: SymbolEntity) : this("${baseSymbol.name}/${quoteSymbol.name}")
    override fun toString(): String = value

    fun baseAndQuoteSymbols(): Pair<String, String> =
        this.value.split('/', limit = 2).let { Pair(it[0], it[1]) }

    fun baseSymbol() = baseAndQuoteSymbols().first
    fun quoteSymbol() = baseAndQuoteSymbols().second
}

object MarketTable : GUIDTable<MarketId>("market", ::MarketId) {
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
    val updatedAt = timestamp("updated_at").nullable()
    val updatedBy = varchar("updated_by", 10485760).nullable()
    val baseSymbolGuid = reference("base_symbol_guid", SymbolTable)
    val quoteSymbolGuid = reference("quote_symbol_guid", SymbolTable)
    val tickSize = decimal("tick_size", 30, 18)
    val lastPrice = decimal("last_price", 30, 18)
    val minAllowedBidPrice = decimal("min_allowed_bid_price", 30, 18)
    val maxAllowedOfferPrice = decimal("max_allowed_offer_price", 30, 18)
    val minFee = decimal("min_fee", 30, 0).default(BigDecimal.ZERO)
}

class MarketEntity(guid: EntityID<MarketId>) : GUIDEntity<MarketId>(guid) {
    companion object : EntityClass<MarketId, MarketEntity>(MarketTable) {
        fun create(
            baseSymbol: SymbolEntity,
            quoteSymbol: SymbolEntity,
            tickSize: BigDecimal,
            lastPrice: BigDecimal,
            createdBy: String,
            minFee: BigInteger = BigInteger.ZERO,
        ) = MarketEntity.new(MarketId(baseSymbol, quoteSymbol)) {
            this.createdAt = Clock.System.now()
            this.createdBy = createdBy
            this.baseSymbolGuid = baseSymbol.guid
            this.quoteSymbolGuid = quoteSymbol.guid
            this.tickSize = tickSize
            this.lastPrice = lastPrice
            this.minAllowedBidPrice = lastPrice
            this.maxAllowedOfferPrice = lastPrice
            this.minFee = minFee
        }

        override fun all(): SizedIterable<MarketEntity> =
            table
                .selectAll()
                .orderBy(table.id, SortOrder.ASC)
                .notForUpdate()
                .let { wrapRows(it) }
                .with(MarketEntity::baseSymbol, MarketEntity::quoteSymbol)

        fun findBySymbols(symbol1: SymbolEntity, symbol2: SymbolEntity): MarketEntity? =
            MarketEntity.find {
                (MarketTable.baseSymbolGuid.eq(symbol1.guid).and(MarketTable.quoteSymbolGuid.eq(symbol2.guid)))
                    .or(MarketTable.baseSymbolGuid.eq(symbol2.guid).and(MarketTable.quoteSymbolGuid.eq(symbol1.guid)))
            }.singleOrNull()
    }

    var createdAt by MarketTable.createdAt
    var createdBy by MarketTable.createdBy
    var updatedAt by MarketTable.updatedAt
    var updatedBy by MarketTable.updatedBy
    var baseSymbolGuid by MarketTable.baseSymbolGuid
    var quoteSymbolGuid by MarketTable.quoteSymbolGuid
    var tickSize by MarketTable.tickSize
    var lastPrice by MarketTable.lastPrice
    var minAllowedBidPrice by MarketTable.minAllowedBidPrice
    var maxAllowedOfferPrice by MarketTable.maxAllowedOfferPrice

    var baseSymbol by SymbolEntity referencedOn MarketTable.baseSymbolGuid
    var quoteSymbol by SymbolEntity referencedOn MarketTable.quoteSymbolGuid

    var minFee by MarketTable.minFee.transform(
        toReal = { it.toBigInteger() },
        toColumn = { it.toBigDecimal() },
    )
}
