package co.chainring.sequencer.core

import co.chainring.sequencer.proto.MarketCheckpoint
import co.chainring.sequencer.proto.MarketCheckpointKt.levelOrder
import co.chainring.sequencer.proto.MarketCheckpointKt.orderBookLevel
import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.OrderDisposition
import java.math.BigDecimal
import java.math.BigInteger

enum class BookSide {
    Buy,
    Sell,
}

val noExecutions = listOf<Execution>()

data class OrderBookLevelFill(
    val remainingAmount: BigInteger,
    val executions: List<Execution>,
)

data class LevelOrder(
    var guid: OrderGuid,
    var wallet: WalletAddress,
    var quantity: BigInteger,
    var levelIx: Int,
    var originalQuantity: BigInteger = quantity,
) {
    fun update(order: Order) {
        this.guid = order.guid.toOrderGuid()
        this.wallet = order.wallet.toWalletAddress()
        this.quantity = order.amount.toBigInteger()
        this.originalQuantity = this.quantity
    }

    fun reset() {
        this.guid = OrderGuid.none
        this.wallet = WalletAddress.none
        this.quantity = BigInteger.ZERO
        this.originalQuantity = this.quantity
    }

    fun toCheckpoint(): MarketCheckpoint.LevelOrder {
        return levelOrder {
            this.guid = this@LevelOrder.guid.value
            this.wallet = this@LevelOrder.wallet.value
            this.quantity = this@LevelOrder.quantity.toIntegerValue()
            this.levelIx = this@LevelOrder.levelIx
            this.originalQuantity = this@LevelOrder.originalQuantity.toIntegerValue()
        }
    }

    fun fromCheckpoint(checkpoint: MarketCheckpoint.LevelOrder) {
        this.guid = OrderGuid(checkpoint.guid)
        this.wallet = WalletAddress(checkpoint.wallet)
        this.quantity = checkpoint.quantity.toBigInteger()
        this.levelIx = checkpoint.levelIx
        this.originalQuantity = checkpoint.originalQuantity.toBigInteger()
    }
}

class OrderBookLevel(val levelIx: Int, val side: BookSide, val price: BigDecimal, val maxOrderCount: Int) {
    val orders = Array(maxOrderCount) { _ -> LevelOrder(0L.toOrderGuid(), 0L.toWalletAddress(), BigInteger.ZERO, levelIx) }
    var totalQuantity = BigInteger.ZERO
    var orderHead = 0
    var orderTail = 0

    fun toCheckpoint(): MarketCheckpoint.OrderBookLevel {
        return orderBookLevel {
            this.levelIx = this@OrderBookLevel.levelIx
            this.side = when (this@OrderBookLevel.side) {
                BookSide.Buy -> MarketCheckpoint.BookSide.Buy
                BookSide.Sell -> MarketCheckpoint.BookSide.Sell
            }
            this.price = this@OrderBookLevel.price.toDecimalValue()
            this.maxOrderCount = this@OrderBookLevel.maxOrderCount
            this.totalQuantity = this@OrderBookLevel.totalQuantity.toIntegerValue()
            this.orderHead = this@OrderBookLevel.orderHead
            this.orderTail = this@OrderBookLevel.orderTail
            (this@OrderBookLevel.orderHead..this@OrderBookLevel.orderTail).forEach { i ->
                val order = this@OrderBookLevel.orders[i]
                this.orders.add(order.toCheckpoint())
            }
        }
    }

    fun fromCheckpoint(checkpoint: MarketCheckpoint.OrderBookLevel) {
        orderHead = checkpoint.orderHead
        orderTail = checkpoint.orderTail
        val checkpointOrdersCount = checkpoint.ordersList.size
        (0.until(checkpointOrdersCount)).forEach { i ->
            val orderCheckpoint = checkpoint.ordersList[i]
            orders[orderHead + i].fromCheckpoint(orderCheckpoint)
        }
        totalQuantity = checkpoint.totalQuantity.toBigInteger()
    }

    fun addOrder(order: Order): Pair<OrderDisposition, LevelOrder?> {
        val nextTail = (orderTail + 1) % maxOrderCount
        return if (nextTail == orderHead) {
            OrderDisposition.Rejected to null
        } else {
            val levelOrder = orders[orderTail]
            levelOrder.update(order)
            totalQuantity += levelOrder.quantity
            orderTail = nextTail
            OrderDisposition.Accepted to levelOrder
        }
    }

    fun fillOrder(requestedAmount: BigInteger): OrderBookLevelFill {
        var ix = orderHead
        val executions = mutableListOf<Execution>()
        var remainingAmount = requestedAmount
        while (ix != orderTail && remainingAmount > BigInteger.ZERO) {
            val curOrder = orders[ix]
            if (remainingAmount >= curOrder.quantity) {
                executions.add(
                    Execution(
                        counterOrder = curOrder,
                        amount = curOrder.quantity,
                        price = this.price,
                        counterOrderExhausted = true,
                    ),
                )
                totalQuantity -= curOrder.quantity
                remainingAmount -= curOrder.quantity
                ix = (ix + 1) % maxOrderCount
            } else {
                executions.add(
                    Execution(
                        counterOrder = curOrder,
                        amount = remainingAmount,
                        price = this.price,
                        counterOrderExhausted = false,
                    ),
                )
                totalQuantity -= remainingAmount
                curOrder.quantity -= remainingAmount
                remainingAmount = BigInteger.ZERO
            }
        }
        // remove consumed orders
        orderHead = ix

        return OrderBookLevelFill(
            remainingAmount,
            executions,
        )
    }

    fun removeLevelOrder(levelOrder: LevelOrder) {
        val orderIx = orders.indexOf(levelOrder)
        totalQuantity -= levelOrder.quantity
        levelOrder.reset()
        if (orderIx == (orderTail - 1) % maxOrderCount) {
            orderTail = (orderTail - 1) % maxOrderCount
        } else if (orderIx < orderHead) {
            // copy from after orderIx to orderTail, and decrement orderTail
            if (orderIx < orderTail) {
                val orderIxRef = orders[orderIx]
                System.arraycopy(orders, orderIx + 1, orders, orderIx, orderTail - orderIx)
                orders[orderTail] = orderIxRef
            }
            orderTail = (orderTail - 1) % maxOrderCount
        } else {
            if (orderIx > orderHead) {
                val orderIxRef = orders[orderIx]
                System.arraycopy(orders, orderHead, orders, orderHead + 1, orderIx - orderHead)
                orders[orderHead] = orderIxRef
            }
            orderHead = (orderHead + 1) % maxOrderCount
        }
    }

    // equals and hashCode are overridden because of orders are stored in array
    // see https://kotlinlang.org/docs/arrays.html#compare-arrays
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as OrderBookLevel

        if (levelIx != other.levelIx) return false
        if (side != other.side) return false
        if (price != other.price) return false
        if (maxOrderCount != other.maxOrderCount) return false
        if (!orders.contentEquals(other.orders)) return false
        if (totalQuantity != other.totalQuantity) return false
        if (orderHead != other.orderHead) return false
        return orderTail == other.orderTail
    }

    override fun hashCode(): Int {
        var result = levelIx
        result = 31 * result + side.hashCode()
        result = 31 * result + price.hashCode()
        result = 31 * result + maxOrderCount
        result = 31 * result + orders.contentHashCode()
        result = 31 * result + (totalQuantity.hashCode() ?: 0)
        result = 31 * result + orderHead
        result = 31 * result + orderTail
        return result
    }
}

data class Execution(
    val counterOrder: LevelOrder,
    val amount: BigInteger,
    val price: BigDecimal,
    val counterOrderExhausted: Boolean,
)

data class AddOrderResult(
    val disposition: OrderDisposition,
    val executions: List<Execution>,
)

data class RemoveOrderResult(
    val wallet: WalletAddress,
    val baseAssetAmount: BigInteger,
    val quoteAssetAmount: BigInteger,
)

data class ChangeOrderResult(
    val wallet: WalletAddress,
    val disposition: OrderDisposition,
    val baseAssetDelta: BigInteger,
    val quoteAssetDelta: BigInteger,
)
