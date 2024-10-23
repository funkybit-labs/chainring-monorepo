package xyz.funkybit.core.model.db

import de.fxlae.typeid.TypeId
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.TransactionManager
import xyz.funkybit.apps.api.model.websocket.OHLC
import xyz.funkybit.core.model.db.OHLCTable.close
import xyz.funkybit.core.model.db.OHLCTable.firstTrade
import xyz.funkybit.core.model.db.OHLCTable.high
import xyz.funkybit.core.model.db.OHLCTable.lastTrade
import xyz.funkybit.core.model.db.OHLCTable.low
import xyz.funkybit.core.model.db.OHLCTable.open
import xyz.funkybit.core.model.db.OHLCTable.volume
import xyz.funkybit.core.utils.truncateTo
import java.math.BigDecimal
import java.math.BigInteger
import java.sql.ResultSet
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@Serializable
@JvmInline
value class OHLCId(override val value: String) : EntityId {
    companion object {
        fun generate(): OHLCId = OHLCId(TypeId.generate("ohlc").toString())
    }

    override fun toString(): String = value
}

enum class OHLCDuration {
    P1M,
    P5M,
    P15M,
    P1H,
    P4H,
    P1D,
    ;

    fun durationStart(instant: Instant): Instant {
        return when (this) {
            P1M -> instant.truncateTo(DateTimeUnit.MINUTE)
            P5M -> instant.truncateTo(DateTimeUnit.MINUTE * 5)
            P15M -> instant.truncateTo(DateTimeUnit.MINUTE * 15)
            P1H -> instant.truncateTo(DateTimeUnit.HOUR)
            P4H -> instant.truncateTo(DateTimeUnit.HOUR * 4)
            P1D -> instant.truncateTo(DateTimeUnit.HOUR * 24)
        }
    }

    fun interval(): Duration {
        return when (this) {
            P1M -> 1.minutes
            P5M -> 5.minutes
            P15M -> 15.minutes
            P1H -> 1.hours
            P4H -> 4.hours
            P1D -> 1.days
        }
    }
}

object OHLCTable : GUIDTable<OHLCId>("ohlc", ::OHLCId) {
    val marketGuid = reference("market_guid", MarketTable).index()
    val start = timestamp("start")
    val duration = customEnumeration(
        "duration",
        "OHLCDuration",
        { value -> OHLCDuration.valueOf(value as String) },
        { PGEnum("OHLCDuration", it) },
    )
    val firstTrade = timestamp("first_trade")
    val lastTrade = timestamp("last_trade")
    val open = decimal("open", 30, 18)
    val high = decimal("high", 30, 18)
    val low = decimal("low", 30, 18)
    val close = decimal("close", 30, 18)
    val volume = decimal("amount", 30, 0)

    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at").nullable()

    init {
        uniqueIndex(
            customIndexName = "Unique_OHLC",
            columns = arrayOf(marketGuid, duration, start),
        )
    }
}

class OHLCEntity(guid: EntityID<OHLCId>) : GUIDEntity<OHLCId>(guid) {
    fun toWSResponse(): OHLC {
        return OHLC(
            start = this.start,
            open = this.open.stripTrailingZeros(),
            high = this.high.stripTrailingZeros(),
            low = this.low.stripTrailingZeros(),
            close = this.close.stripTrailingZeros(),
            duration = this.duration,
        )
    }

    companion object : EntityClass<OHLCId, OHLCEntity>(OHLCTable) {

        fun updateWith(market: MarketId, tradeTimestamp: Instant, tradePrice: BigDecimal, tradeAmount: BigInteger): List<OHLCEntity> {
            return TransactionManager.current().exec(
                """
                    INSERT INTO ${OHLCTable.tableName} (guid, market_guid, start, duration, open, high, low, close, amount, first_trade, last_trade, created_at)
                    VALUES ${
                    OHLCDuration.entries.joinToString(",") { ohlcDuration ->
                        "('${OHLCId.generate()}', '$market', '${ohlcDuration.durationStart(tradeTimestamp)}', '$ohlcDuration'::ohlcduration, $tradePrice, $tradePrice, $tradePrice, $tradePrice, ${tradeAmount.toBigDecimal()}, '$tradeTimestamp', '$tradeTimestamp', now())"
                    }
                }
                    ON CONFLICT (market_guid, start, duration) DO UPDATE
                    SET
                        open = CASE WHEN ${OHLCTable.tableName}.${firstTrade.name} > EXCLUDED.first_trade THEN EXCLUDED.open ELSE ${OHLCTable.tableName}.${open.name} END,
                        high = CASE WHEN ${OHLCTable.tableName}.${high.name} < EXCLUDED.high THEN EXCLUDED.high ELSE ${OHLCTable.tableName}.${high.name} END,
                        low = CASE WHEN ${OHLCTable.tableName}.${low.name} > EXCLUDED.low THEN EXCLUDED.low ELSE ${OHLCTable.tableName}.${low.name} END,
                        close = CASE WHEN ${OHLCTable.tableName}.${lastTrade.name} <= EXCLUDED.last_trade THEN EXCLUDED.close ELSE ${OHLCTable.tableName}.${close.name} END,
                        amount = ${OHLCTable.tableName}.${volume.name} + EXCLUDED.amount,
                        first_trade = CASE WHEN ${OHLCTable.tableName}.${firstTrade.name} > EXCLUDED.first_trade THEN EXCLUDED.first_trade ELSE ${OHLCTable.tableName}.${firstTrade.name} END,
                        last_trade = CASE WHEN ${OHLCTable.tableName}.${lastTrade.name} <= EXCLUDED.last_trade THEN EXCLUDED.last_trade ELSE ${OHLCTable.tableName}.${lastTrade.name} END,
                        updated_at = now()
                   RETURNING ${OHLCTable.columns.joinToString(", ") { it.name }}
                """,
                explicitStatementType = StatementType.SELECT,
            ) { rs: ResultSet ->
                val entityCache = TransactionManager.current().entityCache
                val fieldsIndex = OHLCTable.fields.withIndex().associate { (index, field) -> field to index }

                generateSequence {
                    if (rs.next()) {
                        // remove entity from cache to make sure updated OHLC record is actually read from the result set
                        val entityId = EntityID(OHLCId(rs.getString(1)), OHLCTable)
                        entityCache.find(OHLCEntity, entityId)?.let {
                            entityCache.remove(OHLCTable, it)
                        }

                        // read updated OHLC record
                        val resultRow = ResultRow.create(rs, fieldsIndex)
                        OHLCEntity.wrapRow(resultRow)
                    } else {
                        null
                    }
                }.toList()
            } ?: emptyList()
        }

        fun fetchForMarketStartingFrom(market: MarketId, duration: OHLCDuration, startTime: Instant): List<OHLCEntity> {
            return OHLCEntity.find {
                OHLCTable.marketGuid.eq(market) and OHLCTable.duration.eq(duration) and OHLCTable.start.greaterEq(startTime)
            }.orderBy(OHLCTable.start to SortOrder.ASC).toList()
        }

        fun findSingleByClosestStartTime(market: MarketId, duration: OHLCDuration, startTime: Instant): OHLCEntity? {
            return OHLCEntity.find {
                OHLCTable.marketGuid.eq(market) and OHLCTable.duration.eq(duration) and OHLCTable.start.greaterEq(startTime)
            }.orderBy(OHLCTable.start to SortOrder.ASC).limit(1).singleOrNull()
        }
    }

    var marketGuid by OHLCTable.marketGuid
    var market by MarketEntity referencedOn OHLCTable.marketGuid
    var start by OHLCTable.start
    var duration by OHLCTable.duration

    var firstTrade by OHLCTable.lastTrade
    var lastTrade by OHLCTable.lastTrade

    var open by OHLCTable.open
    var high by OHLCTable.high
    var low by OHLCTable.low
    var close by OHLCTable.close
    var volume by OHLCTable.volume

    var createdAt by OHLCTable.createdAt
    var updatedAt by OHLCTable.updatedAt
}
