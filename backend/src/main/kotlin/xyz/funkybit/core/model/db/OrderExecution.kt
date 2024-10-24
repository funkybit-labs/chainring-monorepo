package xyz.funkybit.core.model.db

import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import xyz.funkybit.apps.api.model.Trade
import xyz.funkybit.core.model.Symbol
import java.math.BigInteger

@Serializable
@JvmInline
value class ExecutionId(override val value: String) : EntityId {
    companion object {
        fun generate(): ExecutionId = ExecutionId(TypeId.generate("execution").toString())
    }

    override fun toString(): String = value
}

@Serializable
enum class ExecutionRole {
    Taker,
    Maker,
}

object OrderExecutionTable : GUIDTable<ExecutionId>("order_execution", ::ExecutionId) {
    val createdAt = timestamp("created_at")
    val timestamp = timestamp("timestamp").index()
    val orderGuid = reference("order_guid", OrderTable).index()
    val counterOrderGuid = reference("counter_order_guid", OrderTable).index()
    val tradeGuid = reference("trade_guid", TradeTable).index()
    val role = customEnumeration(
        "role",
        "ExecutionRole",
        { value -> ExecutionRole.valueOf(value as String) },
        { PGEnum("ExecutionRole", it) },
    ).index()
    val feeAmount = decimal("fee_amount", 30, 0)
    val feeSymbol = varchar("fee_symbol", 10485760)
    val marketGuid = reference("market_guid", MarketTable)
    val side = customEnumeration(
        "side",
        "OrderSide",
        { value -> OrderSide.valueOf(value as String) },
        { PGEnum("OrderSide", it) },
    )
    val userGuid = reference("user_guid", UserTable).index()
    val responseSequence = long("response_sequence").index()

    init {
        OrderTable.index(
            customIndexName = "order_execution_user_guid_timestamp_index",
            columns = arrayOf(userGuid, timestamp),
        )
        OrderTable.index(
            customIndexName = "order_execution_user_guid_response_sequence_index",
            columns = arrayOf(userGuid, responseSequence),
        )
    }
}

class OrderExecutionEntity(guid: EntityID<ExecutionId>) : GUIDEntity<ExecutionId>(guid) {
    fun toTradeResponse(): Trade {
        return Trade(
            id = this.trade.guid.value,
            orderId = this.orderGuid.value,
            marketId = this.marketGuid.value,
            executionRole = this.role,
            counterOrderId = this.counterOrderGuid.value,
            timestamp = this.timestamp,
            side = this.side,
            amount = this.trade.amount,
            price = this.trade.price,
            feeAmount = this.feeAmount,
            feeSymbol = this.feeSymbol,
            settlementStatus = this.trade.settlementStatus,
            error = this.trade.error,
        )
    }

    companion object : EntityClass<ExecutionId, OrderExecutionEntity>(OrderExecutionTable) {
        fun create(
            timestamp: Instant,
            orderEntity: OrderEntity,
            counterOrderEntity: OrderEntity,
            tradeEntity: TradeEntity,
            role: ExecutionRole,
            feeAmount: BigInteger,
            feeSymbol: Symbol,
            side: OrderSide,
            marketEntity: MarketEntity,
            responseSequence: Long,
        ) = OrderExecutionEntity.new(ExecutionId.generate()) {
            val now = Clock.System.now()
            this.createdAt = now
            this.timestamp = timestamp
            this.order = orderEntity
            this.counterOrderGuid = counterOrderEntity.guid
            this.trade = tradeEntity
            this.role = role
            this.feeAmount = feeAmount
            this.feeSymbol = feeSymbol
            this.marketGuid = marketEntity.guid
            this.side = side
            this.userGuid = orderEntity.wallet.userGuid
            this.responseSequence = responseSequence
        }

        fun findForOrder(orderEntity: OrderEntity): List<OrderExecutionEntity> {
            return OrderExecutionEntity.find {
                OrderExecutionTable.orderGuid.eq(orderEntity.guid)
            }.orderBy(Pair(OrderExecutionTable.timestamp, SortOrder.DESC)).toList()
        }

        fun findForOrders(orderIds: List<OrderId>): List<OrderExecutionEntity> {
            return OrderExecutionEntity.find {
                OrderExecutionTable.orderGuid.inList(orderIds)
            }.toList()
        }

        fun findForTrades(tradeEntities: List<TradeEntity>): List<OrderExecutionEntity> {
            return OrderExecutionEntity.find {
                OrderExecutionTable.tradeGuid.inList(tradeEntities.map { it.guid })
            }.toList()
        }

        fun listLatestForUser(userId: EntityID<UserId>, maxSequencerResponses: Int): List<OrderExecutionEntity> {
            val sequencerResponseNumbers = TradeTable
                .join(OrderExecutionTable, JoinType.INNER, TradeTable.guid, OrderExecutionTable.tradeGuid)
                .select(TradeTable.responseSequence)
                .withDistinct(true)
                .where {
                    OrderExecutionTable.userGuid.eq(userId)
                }
                .orderBy(TradeTable.responseSequence, SortOrder.DESC)
                .limit(maxSequencerResponses)
                .mapNotNull { it[TradeTable.responseSequence] }

            return OrderExecutionTable
                .join(TradeTable, JoinType.INNER, TradeTable.guid, OrderExecutionTable.tradeGuid)
                .select(OrderExecutionTable.columns)
                .where {
                    OrderExecutionTable.userGuid.eq(userId)
                        .and(TradeTable.responseSequence.inList(sequencerResponseNumbers))
                }
                .orderBy(Pair(TradeTable.responseSequence, SortOrder.DESC), Pair(TradeTable.sequenceId, SortOrder.ASC))
                .map(Companion::wrapRow).toList()
        }

        fun listForUser(userId: EntityID<UserId>, beforeTimestamp: Instant, limit: Int): List<OrderExecutionEntity> {
            return OrderExecutionTable
                .join(TradeTable, JoinType.INNER, TradeTable.guid, OrderExecutionTable.tradeGuid)
                .select(OrderExecutionTable.columns)
                .where {
                    OrderExecutionTable.userGuid.eq(userId) and OrderExecutionTable.timestamp.less(beforeTimestamp)
                }
                .orderBy(Pair(OrderExecutionTable.timestamp, SortOrder.DESC))
                .limit(limit)
                .map(Companion::wrapRow).toList()
        }
    }

    var createdAt by OrderExecutionTable.createdAt
    var timestamp by OrderExecutionTable.timestamp
    var orderGuid by OrderExecutionTable.orderGuid
    var counterOrderGuid by OrderExecutionTable.counterOrderGuid
    var order by OrderEntity referencedOn OrderExecutionTable.orderGuid
    var tradeGuid by OrderExecutionTable.tradeGuid
    var trade by TradeEntity referencedOn OrderExecutionTable.tradeGuid
    var role by OrderExecutionTable.role
    var feeAmount by OrderExecutionTable.feeAmount.transform(
        toReal = { it.toBigInteger() },
        toColumn = { it.toBigDecimal() },
    )
    var feeSymbol by OrderExecutionTable.feeSymbol.transform(
        toReal = { Symbol(it) },
        toColumn = { it.value },
    )

    var marketGuid by OrderExecutionTable.marketGuid
    var market by MarketEntity referencedOn OrderExecutionTable.marketGuid
    var side by OrderExecutionTable.side

    var userGuid by OrderExecutionTable.userGuid
    var user by UserEntity referencedOn OrderExecutionTable.userGuid
    var responseSequence by OrderExecutionTable.responseSequence
}
