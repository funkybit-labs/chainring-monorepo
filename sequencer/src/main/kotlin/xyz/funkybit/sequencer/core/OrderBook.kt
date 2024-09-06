package xyz.funkybit.sequencer.core

import xyz.funkybit.sequencer.core.datastructure.AVLTree
import xyz.funkybit.sequencer.proto.MarketCheckpoint
import xyz.funkybit.sequencer.proto.MarketCheckpointKt.levelOrder
import xyz.funkybit.sequencer.proto.MarketCheckpointKt.orderBookLevel
import xyz.funkybit.sequencer.proto.Order
import xyz.funkybit.sequencer.proto.OrderDisposition
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
    var user: UserGuid,
    var quantity: BigInteger,
    var feeRate: FeeRate,
    var level: OrderBookLevel,
    var originalQuantity: BigInteger = quantity,
) {
    fun update(user: Long, order: Order, feeRate: FeeRate) {
        this.guid = order.guid.toOrderGuid()
        this.user = user.toUserGuid()
        this.quantity = order.amount.toBigInteger()
        this.originalQuantity = this.quantity
        this.feeRate = feeRate
    }

    fun reset() {
        this.guid = OrderGuid.none
        this.user = UserGuid.none
        this.quantity = BigInteger.ZERO
        this.feeRate = FeeRate.zero
        this.originalQuantity = this.quantity
    }

    fun toCheckpoint(): MarketCheckpoint.LevelOrder {
        return levelOrder {
            this.guid = this@LevelOrder.guid.value
            this.user = this@LevelOrder.user.value
            this.quantity = this@LevelOrder.quantity.toIntegerValue()
            this.originalQuantity = this@LevelOrder.originalQuantity.toIntegerValue()
            this.feeRate = this@LevelOrder.feeRate.value
        }
    }

    fun fromCheckpoint(checkpoint: MarketCheckpoint.LevelOrder, level: OrderBookLevel) {
        this.guid = OrderGuid(checkpoint.guid)
        this.user = UserGuid(checkpoint.user)
        this.quantity = checkpoint.quantity.toBigInteger()
        this.level = level
        this.originalQuantity = checkpoint.originalQuantity.toBigInteger()
        this.feeRate = FeeRate(checkpoint.feeRate)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as LevelOrder

        if (guid != other.guid) return false
        if (user != other.user) return false
        if (quantity != other.quantity) return false
        if (feeRate != other.feeRate) return false
        if (level.ix != other.level.ix) return false
        if (originalQuantity != other.originalQuantity) return false

        return true
    }

    override fun hashCode(): Int {
        var result = guid.hashCode()
        result = 31 * result + user.hashCode()
        result = 31 * result + quantity.hashCode()
        result = 31 * result + feeRate.hashCode()
        result = 31 * result + level.ix.hashCode()
        result = 31 * result + originalQuantity.hashCode()
        return result
    }
}

// price is used for notional calculation
class OrderBookLevel(ix: Int, var side: BookSide, var price: BigDecimal, val maxOrderCount: Int) : AVLTree.Node<OrderBookLevel>(ix) {

    val orders = Array(maxOrderCount) { _ ->
        LevelOrder(guid = 0L.toOrderGuid(), user = 0L.toUserGuid(), quantity = BigInteger.ZERO, feeRate = FeeRate.zero, level = this)
    }
    var totalQuantity = BigInteger.ZERO
    var orderHead = 0
    var orderTail = 0

    companion object {
        fun empty(maxOrderCount: Int): OrderBookLevel {
            return OrderBookLevel(0, BookSide.Sell, BigDecimal.ZERO, maxOrderCount)
        }
    }

    fun init(levelIx: Int, buy: BookSide, price: BigDecimal): OrderBookLevel {
        this.ix = levelIx
        this.side = buy
        this.price = price
        return this
    }

    override fun reset() {
        ix = 0
        side = BookSide.Sell
        price = BigDecimal.ZERO
        totalQuantity = BigInteger.ZERO
        super.reset()
    }

    fun toCheckpoint(): MarketCheckpoint.OrderBookLevel {
        return orderBookLevel {
            this.levelIx = this@OrderBookLevel.ix
            this.side = when (this@OrderBookLevel.side) {
                BookSide.Buy -> MarketCheckpoint.BookSide.Buy
                BookSide.Sell -> MarketCheckpoint.BookSide.Sell
            }
            this.price = this@OrderBookLevel.price.toDecimalValue()
            this.maxOrderCount = this@OrderBookLevel.maxOrderCount
            this.totalQuantity = this@OrderBookLevel.totalQuantity.toIntegerValue()
            this.orderHead = this@OrderBookLevel.orderHead
            this.orderTail = this@OrderBookLevel.orderTail

            // store orders respecting level's circular buffer
            var currentIndex = this@OrderBookLevel.orderHead
            while (currentIndex != this@OrderBookLevel.orderTail) {
                val order = this@OrderBookLevel.orders[currentIndex]
                this.orders.add(order.toCheckpoint())
                currentIndex = (currentIndex + 1) % this@OrderBookLevel.maxOrderCount
            }
        }
    }

    fun fromCheckpoint(checkpoint: MarketCheckpoint.OrderBookLevel) {
        orderHead = checkpoint.orderHead
        orderTail = checkpoint.orderTail
        side = when (checkpoint.side) {
            MarketCheckpoint.BookSide.Buy -> BookSide.Buy
            MarketCheckpoint.BookSide.Sell -> BookSide.Sell
            else -> throw IllegalStateException("Unexpected level book side '${checkpoint.side}'")
        }

        // restore orders respecting level's circular buffer
        val checkpointOrdersCount = checkpoint.ordersList.size
        for (i in 0 until checkpointOrdersCount) {
            val orderCheckpoint = checkpoint.ordersList[i]
            val index = (orderHead + i) % maxOrderCount
            orders[index].fromCheckpoint(orderCheckpoint, level = this)
        }
        totalQuantity = checkpoint.totalQuantity.toBigInteger()
    }

    fun addOrder(user: Long, order: Order, feeRate: FeeRate): Pair<OrderDisposition, LevelOrder?> {
        val nextTail = (orderTail + 1) % maxOrderCount
        return if (nextTail == orderHead) {
            OrderDisposition.Rejected to null
        } else {
            val levelOrder = orders[orderTail]
            levelOrder.update(user, order, feeRate)
            totalQuantity += levelOrder.quantity
            orderTail = nextTail
            OrderDisposition.Accepted to levelOrder
        }
    }

    fun fillOrder(requestedAmount: BigInteger): OrderBookLevelFill {
        var orderIx = orderHead
        val executions = mutableListOf<Execution>()
        var remainingAmount = requestedAmount
        while (orderIx != orderTail && remainingAmount > BigInteger.ZERO) {
            val curOrder = orders[orderIx]
            if (remainingAmount >= curOrder.quantity) {
                executions.add(
                    Execution(
                        counterOrder = curOrder,
                        amount = curOrder.quantity,
                        levelIx = this.ix,
                        price = this.price,
                        counterOrderExhausted = true,
                    ),
                )
                totalQuantity -= curOrder.quantity
                remainingAmount -= curOrder.quantity
                orderIx = (orderIx + 1) % maxOrderCount
            } else {
                executions.add(
                    Execution(
                        counterOrder = curOrder,
                        amount = remainingAmount,
                        levelIx = this.ix,
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
        orderHead = orderIx // TODO: CHAIN-274 Also reset consumed orders

        return OrderBookLevelFill(
            remainingAmount,
            executions,
        )
    }

    fun removeLevelOrder(levelOrder: LevelOrder) {
        val orderIx = orders.indexOf(levelOrder)
        totalQuantity -= levelOrder.quantity
        levelOrder.reset()
        if (orderIx == (orderTail - 1 + maxOrderCount) % maxOrderCount) {
            orderTail = (orderTail - 1 + maxOrderCount) % maxOrderCount
        } else if (orderIx < orderHead) {
            // copy from after orderIx to orderTail, and decrement orderTail
            if (orderIx < orderTail) {
                val orderIxRef = orders[orderIx]
                System.arraycopy(orders, orderIx + 1, orders, orderIx, orderTail - orderIx)
                orders[orderTail] = orderIxRef
            }
            orderTail = (orderTail - 1 + maxOrderCount) % maxOrderCount
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

        if (ix != other.ix) return false
        if (side != other.side) return false
        if (maxOrderCount != other.maxOrderCount) return false
        if (totalQuantity != other.totalQuantity) return false

        // Compare orders between orderHead and orderTail
        var thisOrderIndex = this.orderHead
        var otherOrderIndex = other.orderHead

        while (thisOrderIndex != this.orderTail && otherOrderIndex != other.orderTail) {
            if (this.orders[thisOrderIndex] != other.orders[otherOrderIndex]) return false
            thisOrderIndex = (thisOrderIndex + 1) % this.maxOrderCount
            otherOrderIndex = (otherOrderIndex + 1) % other.maxOrderCount
        }

        // Check if both iterated to their respective tails
        if (thisOrderIndex != this.orderTail || otherOrderIndex != other.orderTail) return false

        return true
    }

    override fun hashCode(): Int {
        var result = ix
        result = 31 * result + side.hashCode()
        result = 31 * result + maxOrderCount
        result = 31 * result + totalQuantity.hashCode()
        result = 31 * result + orderHead
        result = 31 * result + orderTail

        // include orders between head and tail
        var currentIndex = orderHead
        while (currentIndex != orderTail) {
            result = 31 * result + orders[currentIndex].hashCode()
            currentIndex = (currentIndex + 1) % maxOrderCount
        }

        return result
    }
}

data class Execution(
    val counterOrder: LevelOrder,
    val amount: BigInteger,
    val levelIx: Int,
    val price: BigDecimal,
    val counterOrderExhausted: Boolean,
)

data class AddOrderResult(
    val disposition: OrderDisposition,
    val executions: List<Execution>,
)

data class RemoveOrderResult(
    val user: UserGuid,
    val baseAssetAmount: BigInteger,
    val quoteAssetAmount: BigInteger,
)
