package co.chainring.core.model.db

import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.min
import org.jetbrains.exposed.sql.update
import java.math.BigDecimal
import java.math.BigInteger

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
    Settling,
    PendingRollback,
    FailedSettling,
    Completed,
    Failed,
    ;

    fun isFinal(): Boolean {
        return this in listOf(SettlementStatus.Completed, SettlementStatus.Failed)
    }
}

val pendingTradeStatuses = listOf(SettlementStatus.Pending, SettlementStatus.PendingRollback)

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
    val error = varchar("error", 10485760).nullable()
    val sequenceId = integer("sequence_id").autoIncrement()
    val tradeHash = varchar("trade_hash", 10485760).uniqueIndex()
    val settlementBatchGuid = reference("settlement_batch_guid", SettlementBatchTable).index().nullable()
    val responseSequence = long("response_sequence").nullable().index()

    init {
        index(
            customIndexName = "trade_sequence_pending_settlement_status",
            columns = arrayOf(sequenceId, settlementStatus),
            filterCondition = {
                settlementStatus.inList(pendingTradeStatuses)
            },
        )
        index(
            customIndexName = "trade_response_sequence_pending_settlement_status",
            columns = arrayOf(responseSequence, settlementStatus),
            filterCondition = {
                settlementStatus.eq(SettlementStatus.Pending)
            },
        )
    }
}

class TradeEntity(guid: EntityID<TradeId>) : GUIDEntity<TradeId>(guid) {
    companion object : EntityClass<TradeId, TradeEntity>(TradeTable) {
        fun create(
            timestamp: Instant,
            market: MarketEntity,
            amount: BigInteger,
            price: BigDecimal,
            tradeHash: String,
            responseSequence: Long,
        ) = TradeEntity.new(TradeId.generate()) {
            val now = Clock.System.now()
            this.createdAt = now
            this.timestamp = timestamp
            this.market = market
            this.amount = amount
            this.price = price
            this.settlementStatus = SettlementStatus.Pending
            this.tradeHash = tradeHash
            this.responseSequence = responseSequence
        }

        fun markAsFailedSettling(tradeHashes: Set<String>, error: String) {
            TradeTable.update({ TradeTable.tradeHash.inList(tradeHashes) }) {
                it[this.settlementStatus] = SettlementStatus.FailedSettling
                it[this.error] = error
            }
        }

        fun markAsSettling(tradeGuids: List<TradeId>, settlementBatch: SettlementBatchEntity) {
            TradeTable.update({ TradeTable.guid.inList(tradeGuids) }) {
                it[this.settlementStatus] = SettlementStatus.Settling
                it[this.settlementBatchGuid] = settlementBatch.guid
            }
        }

        fun minResponseSequenceForPending(): Long? {
            return TradeTable
                .select(TradeTable.responseSequence.min())
                .where { TradeTable.settlementStatus.eq(SettlementStatus.Pending) }
                .minByOrNull { TradeTable.responseSequence }
                ?.let { it[TradeTable.responseSequence.min()]?.toLong() }
        }

        fun findPending(limit: Int = 100): List<TradeEntity> {
            return TradeEntity.find {
                TradeTable.settlementStatus.inList(pendingTradeStatuses)
            }.orderBy(TradeTable.sequenceId to SortOrder.ASC).limit(limit).toList()
        }

        fun findSettling(): List<TradeEntity> {
            return TradeEntity.find {
                TradeTable.settlementStatus.eq(SettlementStatus.Settling)
            }.orderBy(TradeTable.sequenceId to SortOrder.ASC).toList()
        }

        fun findFailedSettling(settlementBatch: SettlementBatchEntity): List<TradeEntity> {
            return TradeEntity.find {
                TradeTable.settlementBatchGuid.eq(settlementBatch.guid) and
                    TradeTable.settlementStatus.eq(SettlementStatus.FailedSettling)
            }.orderBy(TradeTable.sequenceId to SortOrder.ASC).toList()
        }
    }

    fun settle() {
        this.settledAt = Clock.System.now()
        this.settlementStatus = SettlementStatus.Completed
    }

    fun fail(error: String? = null) {
        this.settlementStatus = SettlementStatus.Failed
        error?.let { this.error = error }
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
    var error by TradeTable.error
    var sequenceId by TradeTable.sequenceId
    var tradeHash by TradeTable.tradeHash
    val executions by OrderExecutionEntity referrersOn OrderExecutionTable.tradeGuid

    val settlementBatchGuid by TradeTable.settlementBatchGuid
    var settlementBatch by SettlementBatchEntity optionalReferencedOn TradeTable.settlementBatchGuid

    var responseSequence by TradeTable.responseSequence
}
