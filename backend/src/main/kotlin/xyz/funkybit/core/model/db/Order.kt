package xyz.funkybit.core.model.db

import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andIfNotNull
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import xyz.funkybit.apps.api.model.Order
import xyz.funkybit.core.model.EvmSignature
import xyz.funkybit.core.model.SequencerOrderId
import xyz.funkybit.core.model.db.OrderExecutionTable.nullable
import xyz.funkybit.core.utils.toByteArrayNoSign
import xyz.funkybit.core.utils.toHex
import xyz.funkybit.sequencer.proto.OrderDisposition
import java.math.BigDecimal
import java.math.BigInteger

@Serializable
@JvmInline
value class ClientOrderId(val value: String) {
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class OrderId(override val value: String) : EntityId {
    companion object {
        fun generate(): OrderId = OrderId(TypeId.generate("order").toString())
    }

    override fun toString(): String = value
}

@Serializable
enum class OrderType {
    Market,
    Limit,
    BackToBackMarket,
}

@Serializable
enum class OrderSide {
    Buy,
    Sell,
}

@Serializable
enum class OrderStatus {
    Open,
    Partial,
    Filled,
    Cancelled,
    Expired,
    Rejected,
    Failed,
    ;

    fun isFinal(): Boolean {
        return this in listOf(Filled, Cancelled, Expired, Failed, Rejected)
    }

    fun isError(): Boolean {
        return this in listOf(Failed, Rejected)
    }

    companion object {
        fun fromOrderDisposition(disposition: OrderDisposition): OrderStatus {
            return when (disposition) {
                OrderDisposition.Accepted -> Open
                OrderDisposition.Filled -> Filled
                OrderDisposition.PartiallyFilled -> Partial
                OrderDisposition.Failed,
                OrderDisposition.UNRECOGNIZED,
                -> Failed
                OrderDisposition.Canceled -> Cancelled
                OrderDisposition.Rejected -> Rejected
                OrderDisposition.AutoReduced -> Open
            }
        }
    }
}

data class CreateOrderAssignment(
    val orderId: OrderId,
    val clientOrderId: ClientOrderId?,
    val nonce: BigInteger,
    val type: OrderType,
    val side: OrderSide,
    val amount: BigInteger,
    val levelIx: Int?,
    val signature: EvmSignature,
    val sequencerOrderId: SequencerOrderId,
    val sequencerTimeNs: BigInteger,
)

object OrderTable : GUIDTable<OrderId>("order", ::OrderId) {
    val nonce = varchar("nonce", 10485760).index()
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
    val marketGuid = reference("market_guid", MarketTable).index()
    val walletGuid = reference("wallet_guid", WalletTable).index()

    val status = customEnumeration(
        "status",
        "OrderStatus",
        { value -> OrderStatus.valueOf(value as String) },
        { PGEnum("OrderStatus", it) },
    ).index()
    val type = customEnumeration(
        "type",
        "OrderType",
        { value -> OrderType.valueOf(value as String) },
        { PGEnum("OrderType", it) },
    )
    val side = customEnumeration(
        "side",
        "OrderSide",
        { value -> OrderSide.valueOf(value as String) },
        { PGEnum("OrderSide", it) },
    )
    val amount = decimal("amount", 30, 0)
    val originalAmount = decimal("original_amount", 30, 0)
    val price = decimal("price", 30, 18).nullable()
    val updatedAt = timestamp("updated_at").nullable()
    val updatedBy = varchar("updated_by", 10485760).nullable()
    val closedAt = timestamp("closed_at").nullable()
    val closedBy = varchar("closed_by", 10485760).nullable()
    val signature = varchar("signature", 10485760)
    val sequencerOrderId = long("sequencer_order_id").uniqueIndex().nullable()
    val clientOrderId = varchar("client_order_id", 10485760).uniqueIndex().nullable()
    val sequencerTimeNs = decimal("sequencer_time_ns", 30, 0)
    val secondMarketGuid = reference("second_market_guid", MarketTable).nullable()

    init {
        OrderTable.index(
            customIndexName = "order_wallet_guid_created_at_index",
            columns = arrayOf(walletGuid, createdAt),
        )
    }
}

class OrderEntity(guid: EntityID<OrderId>) : GUIDEntity<OrderId>(guid) {
    fun toOrderResponse(executions: List<OrderExecutionEntity>): Order {
        return when (type) {
            OrderType.Market -> Order.Market(
                id = this.id.value,
                clientOrderId = this.clientOrderId,
                status = this.status,
                marketId = this.marketGuid.value,
                side = this.side,
                amount = this.amount,
                originalAmount = this.originalAmount,
                executions = executions.map { execution ->
                    Order.Execution(
                        tradeId = execution.trade.guid.value,
                        timestamp = execution.timestamp,
                        amount = execution.trade.amount,
                        price = execution.trade.price,
                        role = execution.role,
                        feeAmount = execution.feeAmount,
                        feeSymbol = execution.feeSymbol,
                        marketId = execution.market?.guid?.value ?: this.marketGuid.value,
                    )
                },
                timing = Order.Timing(
                    createdAt = this.createdAt,
                    updatedAt = this.updatedAt,
                    closedAt = this.closedAt,
                    sequencerTimeNs = this.sequencerTimeNs,
                ),
            )

            OrderType.BackToBackMarket -> Order.BackToBackMarket(
                id = this.id.value,
                clientOrderId = this.clientOrderId,
                status = this.status,
                marketId = this.marketGuid.value,
                secondMarketId = this.secondMarketGuid!!.value,
                side = this.side,
                amount = this.amount,
                originalAmount = this.originalAmount,
                executions = executions.map { execution ->
                    Order.Execution(
                        tradeId = execution.trade.guid.value,
                        timestamp = execution.timestamp,
                        amount = execution.trade.amount,
                        price = execution.trade.price,
                        role = execution.role,
                        feeAmount = execution.feeAmount,
                        feeSymbol = execution.feeSymbol,
                        marketId = execution.market?.guid?.value ?: this.marketGuid.value,
                    )
                },
                timing = Order.Timing(
                    createdAt = this.createdAt,
                    updatedAt = this.updatedAt,
                    closedAt = this.closedAt,
                    sequencerTimeNs = this.sequencerTimeNs,
                ),
            )

            OrderType.Limit -> Order.Limit(
                id = this.id.value,
                clientOrderId = this.clientOrderId,
                status = this.status,
                marketId = this.marketGuid.value,
                side = this.side,
                amount = this.amount,
                originalAmount = this.originalAmount,
                price = this.price!!,
                executions = executions.map { execution ->
                    Order.Execution(
                        tradeId = execution.trade.guid.value,
                        timestamp = execution.timestamp,
                        amount = execution.trade.amount,
                        price = execution.trade.price,
                        role = execution.role,
                        feeAmount = execution.feeAmount,
                        feeSymbol = execution.feeSymbol,
                        marketId = this.marketGuid.value,
                    )
                },
                timing = Order.Timing(
                    createdAt = this.createdAt,
                    updatedAt = this.updatedAt,
                    closedAt = this.closedAt,
                    sequencerTimeNs = this.sequencerTimeNs,
                ),
            )
        }
    }

    companion object : EntityClass<OrderId, OrderEntity>(OrderTable) {
        fun batchCreate(market: MarketEntity, wallet: WalletEntity, createAssignments: List<CreateOrderAssignment>, backToBackMarket: MarketEntity? = null) {
            if (createAssignments.isEmpty()) {
                return
            }
            val now = Clock.System.now()
            OrderTable.batchInsert(createAssignments) { assignment ->
                this[OrderTable.guid] = assignment.orderId
                this[OrderTable.createdAt] = now
                this[OrderTable.createdBy] = "system"
                this[OrderTable.marketGuid] = market.guid
                this[OrderTable.walletGuid] = wallet.guid
                this[OrderTable.status] = OrderStatus.Open
                this[OrderTable.side] = assignment.side
                this[OrderTable.type] = assignment.type
                this[OrderTable.amount] = assignment.amount.toBigDecimal()
                this[OrderTable.originalAmount] = assignment.amount.toBigDecimal()
                this[OrderTable.price] = assignment.levelIx?.let { market.tickSize.multiply(it.toBigDecimal()) }
                this[OrderTable.nonce] = assignment.nonce.toByteArrayNoSign().toHex(false)
                this[OrderTable.signature] = assignment.signature.value
                this[OrderTable.sequencerOrderId] = assignment.sequencerOrderId.value
                this[OrderTable.sequencerTimeNs] = assignment.sequencerTimeNs.toBigDecimal()
                this[OrderTable.secondMarketGuid] = backToBackMarket?.guid
                this[OrderTable.clientOrderId] = assignment.clientOrderId?.value
            }
        }

        fun findBySequencerOrderId(sequencerOrderId: Long): OrderEntity? {
            return OrderEntity.find {
                OrderTable.sequencerOrderId.eq(sequencerOrderId)
            }.firstOrNull()
        }

        fun findByClientOrderId(clientOrderId: ClientOrderId): OrderEntity? {
            return OrderEntity.find {
                OrderTable.clientOrderId.eq(clientOrderId.value)
            }.firstOrNull()
        }

        fun listWithExecutionsForSequencerOrderIds(sequencerOrderIds: List<Long>): List<Pair<OrderEntity, List<OrderExecutionEntity>>> {
            return listWithExecutions(
                queryFilter = OrderTable.sequencerOrderId.inList(sequencerOrderIds),
            )
        }

        fun getOrdersMarkets(sequencerOrderIds: List<Long>): Set<MarketEntity> {
            return if (sequencerOrderIds.isEmpty()) {
                emptySet()
            } else {
                val marketIds = OrderTable
                    .select(OrderTable.marketGuid)
                    .where { OrderTable.sequencerOrderId.inList(sequencerOrderIds) }
                    .distinct()
                    .map { it[OrderTable.marketGuid].value }
                    .toSet()

                MarketEntity.find { MarketTable.guid.inList(marketIds) }.toSet()
            }
        }

        fun listWithExecutionsForWallet(wallet: WalletEntity, statuses: List<OrderStatus> = emptyList(), marketId: MarketId? = null, limit: Int? = null): List<Pair<OrderEntity, List<OrderExecutionEntity>>> {
            return listWithExecutions(
                queryFilter = OrderTable.walletGuid.eq(wallet.guid)
                    .andIfNotNull(marketId?.let { OrderTable.marketGuid.eq(it) })
                    .andIfNotNull(statuses.ifEmpty { null }?.let { OrderTable.status.inList(statuses) }),
                sort = true,
                limit = limit,
            )
        }

        private fun listWithExecutions(queryFilter: Op<Boolean>, sort: Boolean = false, limit: Int? = null): List<Pair<OrderEntity, List<OrderExecutionEntity>>> {
            val executions = mutableMapOf<OrderId, MutableList<OrderExecutionEntity>>()
            val orders = OrderTable
                .join(OrderExecutionTable, JoinType.LEFT, OrderExecutionTable.orderGuid, OrderTable.guid)
                .selectAll().where {
                    queryFilter
                }
                .let {
                    if (sort) {
                        it.orderBy(Pair(OrderTable.createdAt, SortOrder.DESC))
                    } else {
                        it
                    }
                }
                .let {
                    if (limit == null) {
                        it
                    } else {
                        it.limit(limit)
                    }
                }
                .mapNotNull { resultRow ->
                    val executionsForOrder = executions[resultRow[OrderTable.guid].value]
                    if (executionsForOrder != null) {
                        executionsForOrder.add(OrderExecutionEntity.wrapRow(resultRow))
                        null
                    } else {
                        OrderEntity.wrapRow(resultRow).also {
                            executions[it.guid.value] = listOfNotNull(
                                resultRow[OrderExecutionTable.guid.nullable()]?.let {
                                    OrderExecutionEntity.wrapRow(resultRow)
                                },
                            ).toMutableList()
                        }
                    }
                }
                .toList()

            return orders.map { Pair(it, executions[it.guid.value] ?: emptyList()) }
        }

        fun listOrdersWithExecutions(orderIds: List<OrderId>): List<Pair<OrderEntity, List<OrderExecutionEntity>>> {
            return listWithExecutions(
                queryFilter = OrderTable.guid.inList(orderIds),
            )
        }

        fun listOpenForWallet(wallet: WalletEntity): List<OrderEntity> {
            return OrderEntity
                .find {
                    OrderTable.walletGuid.eq(wallet.guid).and(
                        OrderTable.status.eq(OrderStatus.Open).or(
                            OrderTable.status.eq(OrderStatus.Partial).and(OrderTable.type.eq(OrderType.Limit)),
                        ),
                    )
                }
                .orderBy(Pair(OrderTable.createdAt, SortOrder.DESC))
                .toList()
        }
    }

    fun update(amount: BigInteger, price: BigDecimal?) {
        val now = Clock.System.now()
        this.updatedAt = now
        this.amount = amount
        this.price = price
    }

    fun cancel() {
        val now = Clock.System.now()
        this.updatedAt = now
        this.status = OrderStatus.Cancelled
    }

    fun updateStatus(status: OrderStatus) {
        val now = Clock.System.now()
        this.updatedAt = now
        this.status = status
    }

    var nonce by OrderTable.nonce
    var createdAt by OrderTable.createdAt
    var createdBy by OrderTable.createdBy
    var marketGuid by OrderTable.marketGuid
    var market by MarketEntity referencedOn OrderTable.marketGuid
    var walletGuid by OrderTable.walletGuid
    var wallet by WalletEntity referencedOn OrderTable.walletGuid
    var status by OrderTable.status
    var type by OrderTable.type
    var side by OrderTable.side
    var amount by OrderTable.amount.transform(
        toReal = { it.toBigInteger() },
        toColumn = { it.toBigDecimal() },
    )
    var originalAmount by OrderTable.originalAmount.transform(
        toReal = { it.toBigInteger() },
        toColumn = { it.toBigDecimal() },
    )
    var price by OrderTable.price
    var updatedAt by OrderTable.updatedAt
    var updatedBy by OrderTable.updatedBy
    var closedAt by OrderTable.closedAt
    var closedBy by OrderTable.closedBy
    var signature by OrderTable.signature
    var sequencerOrderId by OrderTable.sequencerOrderId.transform(
        toReal = { it?.let { SequencerOrderId(it) } },
        toColumn = { it?.value },
    )
    var clientOrderId by OrderTable.clientOrderId.transform(
        toReal = { it?.let { ClientOrderId(it) } },
        toColumn = { it?.value },
    )
    var sequencerTimeNs by OrderTable.sequencerTimeNs.transform(
        toReal = { it.toBigInteger() },
        toColumn = { it.toBigDecimal() },
    )

    var secondMarketGuid by OrderTable.secondMarketGuid
    var secondMarket by MarketEntity optionalReferencedOn OrderTable.secondMarketGuid
}

fun Pair<OrderEntity, List<OrderExecutionEntity>>.toOrderResponse() = this.first.toOrderResponse(this.second)
