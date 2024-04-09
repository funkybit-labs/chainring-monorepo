package co.chainring.core.model.db

import co.chainring.apps.api.model.Order
import co.chainring.core.evm.EIP712Transaction
import co.chainring.core.model.Address
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.SequencerOrderId
import co.chainring.core.model.db.WalletEntity.Companion.transform
import co.chainring.core.model.db.migrations.V15_WalletTable.V15_WithdrawalTable.index
import co.chainring.core.model.db.migrations.V15_WalletTable.V15_WithdrawalTable.nullable
import co.chainring.core.utils.toFundamentalUnits
import co.chainring.core.utils.toHexBytes
import co.chainring.sequencer.proto.OrderDisposition
import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.update
import java.math.BigDecimal
import java.math.BigInteger

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
    CrossesMarket,
    ;

    fun isFinal(): Boolean {
        return this in listOf(Filled, Cancelled, Expired, Failed, Rejected, CrossesMarket)
    }

    fun isError(): Boolean {
        return this in listOf(Failed, Rejected, CrossesMarket)
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
                OrderDisposition.CrossesMarket -> CrossesMarket
                OrderDisposition.Rejected -> Rejected
                OrderDisposition.AutoReduced -> Open
            }
        }
    }
}

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
}

class OrderEntity(guid: EntityID<OrderId>) : GUIDEntity<OrderId>(guid) {
    fun toOrderResponse(): Order {
        return when (type) {
            OrderType.Market -> Order.Market(
                id = this.id.value,
                status = this.status,
                marketId = this.marketGuid.value,
                side = this.side,
                amount = this.amount,
                originalAmount = this.originalAmount,
                executions = OrderExecutionEntity.findForOrder(this).map { execution ->
                    Order.Execution(
                        timestamp = execution.timestamp,
                        amount = execution.trade.amount,
                        price = execution.trade.price,
                        role = execution.role,
                        feeAmount = execution.feeAmount,
                        feeSymbol = execution.feeSymbol,
                    )
                },
                timing = Order.Timing(
                    createdAt = this.createdAt,
                    updatedAt = this.updatedAt,
                    closedAt = this.closedAt,
                ),
            )

            OrderType.Limit -> Order.Limit(
                id = this.id.value,
                status = this.status,
                marketId = this.marketGuid.value,
                side = this.side,
                amount = this.amount,
                originalAmount = this.originalAmount,
                price = this.price!!,
                executions = OrderExecutionEntity.findForOrder(this).map { execution ->
                    Order.Execution(
                        timestamp = execution.timestamp,
                        amount = execution.trade.amount,
                        price = execution.trade.price,
                        role = execution.role,
                        feeAmount = execution.feeAmount,
                        feeSymbol = execution.feeSymbol,
                    )
                },
                timing = Order.Timing(
                    createdAt = this.createdAt,
                    updatedAt = this.updatedAt,
                    closedAt = this.closedAt,
                ),
            )
        }
    }

    fun toEip712Transaction(baseToken: Address, quoteToken: Address, quoteDecimals: Int) = EIP712Transaction.Order(
        this.wallet.address,
        baseToken,
        quoteToken,
        this.amount,
        this.price?.toFundamentalUnits(quoteDecimals) ?: BigInteger.ZERO,
        BigInteger(1, this.nonce.toHexBytes()),
        EvmSignature(this.signature),
    )

    companion object : EntityClass<OrderId, OrderEntity>(OrderTable) {
        fun create(
            nonce: String,
            market: MarketEntity,
            wallet: WalletEntity,
            type: OrderType,
            side: OrderSide,
            amount: BigInteger,
            price: BigDecimal?,
            signature: EvmSignature,
        ) = OrderEntity.new(OrderId.generate()) {
            val now = Clock.System.now()
            this.nonce = nonce
            this.createdAt = now
            this.createdBy = "system"
            this.market = market
            this.walletGuid = wallet.guid
            this.status = OrderStatus.Open
            this.type = type
            this.side = side
            this.amount = amount
            this.originalAmount = amount
            this.price = price
            this.signature = signature.value
        }

        fun findByNonce(nonce: String): OrderEntity? {
            return OrderEntity.find {
                OrderTable.nonce.eq(nonce)
            }.firstOrNull()
        }

        fun findBySequencerOrderId(sequencerOrderId: Long): OrderEntity? {
            return OrderEntity.find {
                OrderTable.sequencerOrderId.eq(sequencerOrderId)
            }.firstOrNull()
        }

        fun listOrders(wallet: WalletEntity): List<OrderEntity> {
            return OrderEntity
                .find { OrderTable.walletGuid.eq(wallet.guid) }
                .orderBy(Pair(OrderTable.createdAt, SortOrder.DESC))
                .toList()
        }

        fun listOpenOrders(wallet: WalletEntity): List<OrderEntity> {
            return OrderEntity
                .find { OrderTable.status.inList(listOf(OrderStatus.Open, OrderStatus.Partial)) and OrderTable.walletGuid.eq(wallet.guid) }
                .orderBy(Pair(OrderTable.createdAt, SortOrder.DESC))
                .toList()
        }

        fun cancelAll(wallet: WalletEntity) {
            val now = Clock.System.now()
            OrderTable.update({
                OrderTable.status.inList(listOf(OrderStatus.Open, OrderStatus.Partial)) and OrderTable.walletGuid.eq(wallet.guid)
            }) {
                it[this.status] = OrderStatus.Cancelled
                it[this.closedAt] = now
            }
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
}
