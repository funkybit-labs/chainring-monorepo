package co.chainring.core.model.db

import de.fxlae.typeid.TypeId
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

@Serializable
@JvmInline
value class TradeId(override val value: String) : EntityId {
    companion object {
        fun generate(): TradeId = TradeId(TypeId.generate("trade").toString())
    }

    override fun toString(): String = value
}

@Serializable
enum class SettlementStatus {
    Pending,
    Completed,
    Failed,
}

object TradeTable : GUIDTable<TradeId>("trade", ::TradeId) {
    val createdAt = timestamp("created_at")
    val marketGuid = reference("market_guid", MarketTable).index()
    val timestamp = timestamp("timestamp")
    val amount = decimal("amount", 30, 0)
    val price = decimal("price", 30, 18)
    val settlementStatus = customEnumeration(
        "settlement_status",
        "SettlementStatus",
        { value -> SettlementStatus.valueOf(value as String) },
        { PGEnum("SettlementStatus", it) },
    )
    val settledAt = timestamp("settled_at").nullable()
}

class TradeEntity(guid: EntityID<TradeId>) : GUIDEntity<TradeId>(guid) {

    companion object : EntityClass<TradeId, TradeEntity>(TradeTable) {

        fun listTrades(beforeTimestamp: Instant, limit: Int): List<TradeEntity> {
            return TradeEntity
                .find { OrderExecutionTable.createdAt.lessEq(beforeTimestamp) }
                .orderBy(OrderExecutionTable.createdAt to SortOrder.DESC)
                .limit(limit)
                .toList()
        }
    }

    var createdAt by TradeTable.createdAt
    var timestamp by TradeTable.timestamp
    var marketGuid by TradeTable.marketGuid
    var market by MarketEntity referencedOn TradeTable.marketGuid
    var amount by TradeTable.amount.transform(
        toReal = { it.toBigInteger() },
        toColumn = { it.toBigDecimal() },
    )
    var price by TradeTable.price

    var settlementStatus by TradeTable.settlementStatus
    var settledAt by TradeTable.settledAt
}
