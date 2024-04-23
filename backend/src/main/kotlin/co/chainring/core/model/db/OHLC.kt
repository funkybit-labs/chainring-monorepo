package co.chainring.core.model.db

import co.chainring.apps.api.model.websocket.OHLC
import co.chainring.core.model.db.OHLCTable.close
import co.chainring.core.model.db.OHLCTable.firstTrade
import co.chainring.core.model.db.OHLCTable.high
import co.chainring.core.model.db.OHLCTable.lastTrade
import co.chainring.core.model.db.OHLCTable.low
import co.chainring.core.model.db.OHLCTable.marketGuid
import co.chainring.core.model.db.OHLCTable.open
import co.chainring.core.model.db.OHLCTable.period
import co.chainring.core.model.db.OHLCTable.start
import co.chainring.core.model.db.OHLCTable.updatedAt
import co.chainring.core.model.db.OHLCTable.volume
import co.chainring.core.utils.truncateTo
import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Case
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.decimalLiteral
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestampLiteral
import org.jetbrains.exposed.sql.statements.UpsertStatement
import org.jetbrains.exposed.sql.upsert
import java.math.BigDecimal
import java.math.BigInteger
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

enum class OHLCPeriod {
    P1M,
    P5M,
    P15M,
    P1H,
    P4H,
    P1D,
    ;

    fun periodStart(instant: Instant): Instant {
        return when (this) {
            P1M -> instant.truncateTo(DateTimeUnit.MINUTE)
            P5M -> instant.truncateTo(DateTimeUnit.MINUTE * 5)
            P15M -> instant.truncateTo(DateTimeUnit.MINUTE * 15)
            P1H -> instant.truncateTo(DateTimeUnit.HOUR)
            P4H -> instant.truncateTo(DateTimeUnit.HOUR * 4)
            P1D -> instant.truncateTo(DateTimeUnit.HOUR * 24)
        }
    }

    fun durationMs(): Long {
        return when (this) {
            P1M -> 1.minutes.inWholeMilliseconds
            P5M -> 5.minutes.inWholeMilliseconds
            P15M -> 15.minutes.inWholeMilliseconds
            P1H -> 1.hours.inWholeMilliseconds
            P4H -> 4.hours.inWholeMilliseconds
            P1D -> 1.days.inWholeMilliseconds
        }
    }
}

object OHLCTable : GUIDTable<OHLCId>("ohlc", ::OHLCId) {
    val marketGuid = reference("market_guid", MarketTable).index()
    val start = timestamp("start")
    val period = customEnumeration(
        "duration",
        "OHLCDuration",
        { value -> OHLCPeriod.valueOf(value as String) },
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
            columns = arrayOf(marketGuid, period, start),
        )
    }
}

class OHLCEntity(guid: EntityID<OHLCId>) : GUIDEntity<OHLCId>(guid) {
    fun toWSResponse(): OHLC {
        return OHLC(
            start = this.start,
            open = this.open.toDouble(),
            high = this.high.toDouble(),
            low = this.low.toDouble(),
            close = this.close.toDouble(),
            durationMs = this.period.durationMs(),
        )
    }

    companion object : EntityClass<OHLCId, OHLCEntity>(OHLCTable) {
        fun updateWith(market: MarketId, tradeTimestamp: Instant, tradePrice: BigDecimal, tradeAmount: BigInteger): List<OHLCEntity> {
            return OHLCPeriod.entries.map { ohlcPeriod ->
                ohlcPeriod to ohlcPeriod.periodStart(tradeTimestamp)
            }.map { (ohlcPeriod, ohlcStart) ->
                OHLCTable.upsert(
                    keys = arrayOf(marketGuid, start, period),
                    onUpdate = mutableListOf(
                        open to Case().When(firstTrade.greater(tradeTimestamp), decimalLiteral(tradePrice)).Else(open),
                        high to Case().When(high.less(tradePrice), decimalLiteral(tradePrice)).Else(high),
                        low to Case().When(low.greater(tradePrice), decimalLiteral(tradePrice)).Else(low),
                        close to Case().When(lastTrade.lessEq(tradeTimestamp), decimalLiteral(tradePrice)).Else(close),
                        volume to volume.plus(decimalLiteral(tradeAmount.toBigDecimal())),
                        firstTrade to Case().When(firstTrade.greater(tradeTimestamp), timestampLiteral(tradeTimestamp)).Else(firstTrade),
                        lastTrade to Case().When(lastTrade.lessEq(tradeTimestamp), timestampLiteral(tradeTimestamp)).Else(lastTrade),
                        updatedAt to timestampLiteral(Clock.System.now()),
                    ),
                    body = fun OHLCTable.(it: UpsertStatement<Long>) {
                        it[id] = OHLCId.generate()
                        it[marketGuid] = market
                        it[start] = ohlcStart
                        it[period] = ohlcPeriod
                        it[open] = tradePrice
                        it[high] = tradePrice
                        it[low] = tradePrice
                        it[close] = tradePrice
                        it[volume] = tradeAmount.toBigDecimal()
                        it[firstTrade] = tradeTimestamp
                        it[lastTrade] = tradeTimestamp
                        it[createdAt] = Clock.System.now()
                    },
                )
                (ohlcPeriod to ohlcStart)
            }.mapNotNull { (ohlcPeriod, ohlcStart) ->
                findSingle(market, ohlcPeriod, ohlcStart)
            }
        }

        fun findFrom(market: MarketId, period: OHLCPeriod, startTime: Instant): List<OHLCEntity> {
            return OHLCEntity.find {
                OHLCTable.marketGuid.eq(market) and OHLCTable.period.eq(period) and OHLCTable.start.greaterEq(startTime)
            }.orderBy(OHLCTable.start to SortOrder.ASC).toList()
        }

        fun findSingle(market: MarketId, period: OHLCPeriod, startTime: Instant): OHLCEntity? {
            return OHLCEntity.find {
                OHLCTable.marketGuid.eq(market) and OHLCTable.period.eq(period) and OHLCTable.start.eq(startTime)
            }.orderBy(OHLCTable.start to SortOrder.ASC).singleOrNull()
        }
    }

    var marketGuid by OHLCTable.marketGuid
    var market by MarketEntity referencedOn OHLCTable.marketGuid
    var start by OHLCTable.start
    var period by OHLCTable.period

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
