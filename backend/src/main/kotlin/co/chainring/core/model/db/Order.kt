package co.chainring.core.model.db

import co.chainring.apps.api.model.Order
import co.chainring.apps.api.model.OrderApiResponse
import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.update
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
    Expired, ;

    fun isFinal(): Boolean {
        return this in listOf(Filled, Cancelled, Expired)
    }
}

object OrderTable : GUIDTable<OrderId>("order", ::OrderId) {
    val nonce = varchar("nonce", 10485760).index()
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
    val marketGuid = reference("market_guid", MarketTable).index()
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
    val price = decimal("price", 30, 0).nullable()
    val updatedAt = timestamp("updated_at").nullable()
    val updatedBy = varchar("updated_by", 10485760).nullable()
    val closedAt = timestamp("closed_at").nullable()
    val closedBy = varchar("closed_by", 10485760).nullable()
}

class OrderEntity(guid: EntityID<OrderId>) : GUIDEntity<OrderId>(guid) {
    fun toOrderResponse(): OrderApiResponse {
        return when (type) {
            OrderType.Market -> OrderApiResponse.Market(
                id = this.id.value,
                status = this.status,
                marketId = this.marketGuid.value,
                side = this.side,
                amount = this.amount,
                originalAmount = this.originalAmount,
                execution = null,
                timing = Order.Timing(
                    createdAt = this.createdAt,
                    updatedAt = this.updatedAt,
                    closedAt = this.closedAt,
                ),
            )

            OrderType.Limit -> OrderApiResponse.Limit(
                id = this.id.value,
                status = this.status,
                marketId = this.marketGuid.value,
                side = this.side,
                amount = this.amount,
                originalAmount = this.originalAmount,
                price = this.price!!,
                execution = null,
                timing = Order.Timing(
                    createdAt = this.createdAt,
                    updatedAt = this.updatedAt,
                    closedAt = this.closedAt,
                ),
            )
        }
    }

    companion object : EntityClass<OrderId, OrderEntity>(OrderTable) {
        fun create(
            nonce: String,
            market: MarketEntity,
            type: OrderType,
            side: OrderSide,
            amount: BigInteger,
            price: BigInteger?,
        ) = OrderEntity.new(OrderId.generate()) {
            val now = Clock.System.now()
            this.nonce = nonce
            this.createdAt = now
            this.createdBy = "system"
            this.market = market
            this.status = OrderStatus.Open
            this.type = type
            this.side = side
            this.amount = amount
            this.originalAmount = amount
            this.price = price
        }

        fun findByNonce(nonce: String): OrderEntity? {
            return OrderEntity.find {
                OrderTable.nonce.eq(nonce)
            }.firstOrNull()
        }

        fun findAll(): List<OrderEntity> {
            return OrderEntity.all().toList()
        }

        fun cancelAll() {
            val now = Clock.System.now()
            OrderTable.update({ OrderTable.status.inList(listOf(OrderStatus.Open, OrderStatus.Partial)) }) {
                it[this.status] = OrderStatus.Cancelled
                it[this.closedAt] = now
            }
        }
    }

    fun update(amount: BigInteger, price: BigInteger?) {
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

    var nonce by OrderTable.nonce
    var createdAt by OrderTable.createdAt
    var createdBy by OrderTable.createdBy
    var marketGuid by OrderTable.marketGuid
    var market by MarketEntity referencedOn OrderTable.marketGuid
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
    var price by OrderTable.price.transform(
        toReal = { it?.toBigInteger() },
        toColumn = { it?.toBigDecimal() },
    )
    var updatedAt by OrderTable.updatedAt
    var updatedBy by OrderTable.updatedBy
    var closedAt by OrderTable.closedAt
    var closedBy by OrderTable.closedBy
}
