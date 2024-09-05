package xyz.funkybit.core.model.db

import kotlinx.datetime.Clock
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.batchUpsert
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.or
import xyz.funkybit.apps.api.model.MarketLimits
import xyz.funkybit.core.model.db.LimitTable.nullable
import java.math.BigInteger

object LimitTable : IntIdTable("limit") {
    val marketGuid = reference("market_guid", MarketTable).index()
    val userGuid = reference("user_guid", UserTable).index()
    val base = decimal("base", 30, 0)
    val quote = decimal("quote", 30, 0)
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(
            customIndexName = "unique_limit",
            columns = arrayOf(marketGuid, userGuid),
        )
    }
}

class LimitEntity(id: EntityID<Int>) : Entity<Int>(id) {
    companion object : EntityClass<Int, LimitEntity>(LimitTable) {
        fun forUserId(userId: EntityID<UserId>): List<MarketLimits> =
            forUserId(userId.value)

        fun forUserId(userId: UserId): List<MarketLimits> {
            val availableBalances = BalanceEntity
                .getBalancesForUserId(userId)
                .filter { it.type == BalanceType.Available }
                .associateBy { it.symbolGuid }

            return MarketTable
                .leftJoin(LimitTable)
                .select(
                    MarketTable.guid,
                    MarketTable.baseSymbolGuid,
                    MarketTable.quoteSymbolGuid,
                    LimitTable.base,
                    LimitTable.quote,
                )
                .where { LimitTable.userGuid.isNull().or(LimitTable.userGuid.eq(userId)) }
                .orderBy(Pair(MarketTable.guid, SortOrder.ASC))
                .map {
                    MarketLimits(
                        marketId = it[MarketTable.guid].value,
                        base = it[LimitTable.base.nullable()]?.toBigInteger() ?: availableBalances[it[MarketTable.baseSymbolGuid]]?.balance ?: BigInteger.ZERO,
                        quote = it[LimitTable.quote.nullable()]?.toBigInteger() ?: availableBalances[it[MarketTable.quoteSymbolGuid]]?.balance ?: BigInteger.ZERO,
                    )
                }
        }

        fun update(limitUpdates: List<Pair<UserId, MarketLimits>>) {
            val now = Clock.System.now()
            LimitTable
                .batchUpsert(
                    limitUpdates,
                    keys = arrayOf(LimitTable.userGuid, LimitTable.marketGuid),
                    body = { (userId, marketLimits) ->
                        this[LimitTable.marketGuid] = EntityID(marketLimits.marketId, MarketTable)
                        this[LimitTable.userGuid] = EntityID(userId, UserTable)
                        this[LimitTable.base] = marketLimits.base.toBigDecimal()
                        this[LimitTable.quote] = marketLimits.quote.toBigDecimal()
                        this[LimitTable.updatedAt] = now
                    },
                )
        }
    }

    var marketGuid by LimitTable.marketGuid
    var walletGuid by BalanceTable.walletGuid
    var base: BigInteger by LimitTable.base.transform(
        toReal = { it.toBigInteger() },
        toColumn = { it.toBigDecimal() },
    )
    var quote: BigInteger by LimitTable.quote.transform(
        toReal = { it.toBigInteger() },
        toColumn = { it.toBigDecimal() },
    )
    var updatedAt by BalanceTable.updatedAt
}
