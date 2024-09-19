package xyz.funkybit

import xyz.funkybit.sequencer.core.BaseAmount
import xyz.funkybit.sequencer.core.BookSide
import xyz.funkybit.sequencer.core.FeeRate
import xyz.funkybit.sequencer.core.LevelOrder
import xyz.funkybit.sequencer.core.OrderBookLevel
import xyz.funkybit.sequencer.core.toBaseAmount
import xyz.funkybit.sequencer.core.toBigInteger
import xyz.funkybit.sequencer.core.toIntegerValue
import xyz.funkybit.sequencer.proto.Order
import xyz.funkybit.sequencer.proto.OrderDisposition
import xyz.funkybit.sequencer.proto.order
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TestOrderBookLevel {

    private var nextOrderId: Long = 1000
    private val expectedOrderIdSet = mutableSetOf<Long>()

    @Test
    fun test() {
        val obl = OrderBookLevel(300, BookSide.Buy, BigDecimal.ONE, 1000)
        (0 until 100).forEach { _ ->
            assertEquals(obl.addOrder(0L, getNextOrder(), feeRate = FeeRate.zero).first, OrderDisposition.Accepted, "failed at $nextOrderId")
        }
        assertEquals(obl.orderHead, 0)
        assertEquals(obl.orderTail, 100)
        verifyOrders(obl)

        // remove some in middle, tail and head
        (50 until 55).forEach {
            obl.removeLevelOrder(obl.orders[it])
        }
        obl.removeLevelOrder(obl.orders[obl.orderTail - 1])
        obl.removeLevelOrder(obl.orders[obl.orderTail - 1])
        obl.removeLevelOrder(obl.orders[obl.orderHead])
        expectedOrderIdSet.removeAll(setOf(1000, 1050, 1051, 1052, 1053, 1054, 1098, 1099))
        verifyOrders(obl)

        // fill up to max orders which is obl.maxOrderCount - 1
        (0 until obl.maxOrderCount - 100 + 7).forEach { _ ->
            assertEquals(obl.addOrder(0L, getNextOrder(), feeRate = FeeRate.zero).first, OrderDisposition.Accepted, "failed at $nextOrderId")
        }
        // make sure we fail if we hit the max
        assertEquals(obl.addOrder(0L, getNextOrder(false), feeRate = FeeRate.zero).first, OrderDisposition.Rejected)
        assertEquals(expectedOrderIdSet.size, obl.maxOrderCount - 1)
        assertEquals(5, obl.orderTail)
        assertEquals(6, obl.orderHead)

        // remove some in the middle
        expectedOrderIdSet.remove(obl.orders[obl.orderHead + 1].guid.value)
        expectedOrderIdSet.remove(obl.orders[obl.orderTail - 3].guid.value)
        obl.removeLevelOrder(obl.orders[obl.orderHead + 1])
        obl.removeLevelOrder(obl.orders[obl.orderTail - 3])
        verifyOrders(obl)

        // remove head and tail
        expectedOrderIdSet.remove(obl.orders[obl.orderHead].guid.value)
        expectedOrderIdSet.remove(obl.orders[obl.orderTail - 1].guid.value)
        obl.removeLevelOrder(obl.orders[obl.orderHead])
        obl.removeLevelOrder(obl.orders[obl.orderTail - 1])
        verifyOrders(obl)

        assertEquals(3, obl.orderTail)
        assertEquals(8, obl.orderHead)
    }

    private fun verifyOrders(obl: OrderBookLevel) {
        val orderIdsFromBookLevel =
            if (obl.orderTail > obl.orderHead) {
                (obl.orderHead until obl.orderTail).map {
                    obl.orders[it].guid.value
                }
            } else {
                (obl.orderHead until obl.maxOrderCount).map {
                    obl.orders[it].guid.value
                } +
                    (0 until obl.orderTail).map {
                        obl.orders[it].guid.value
                    }
            }

        // make sure no duplicates in list - list size should be same as set size.
        assertEquals(orderIdsFromBookLevel.size, orderIdsFromBookLevel.toSet().size)

        assertEquals(
            expectedOrderIdSet,
            orderIdsFromBookLevel.toSet(),
        )
    }

    @Test
    fun wrapAroundWhileRemovingFromStart() {
        val obl = OrderBookLevel(300, BookSide.Buy, BigDecimal.ONE, 100)
        // add 99 orders
        (0 until 99).forEach { _ ->
            val addOrderResult = obl.addOrder(0L, getNextOrder(), feeRate = FeeRate.zero)
            assertEquals(addOrderResult.first, OrderDisposition.Accepted, "failed at $nextOrderId")
        }

        // remove 95 orders from head
        (0 until 95).forEach { index ->
            val order = obl.orders[index]
            expectedOrderIdSet.remove(order.guid.value)
            obl.removeLevelOrder(order)
        }
        assertEquals(95, obl.orderHead)
        assertEquals(99, obl.orderTail)
        verifyOrders(obl)

        // add 20 orders so that buffer wraps around
        (99 until 119).forEach { _ ->
            val addOrderResult = obl.addOrder(0L, getNextOrder(), feeRate = FeeRate.zero)
            assertEquals(addOrderResult.first, OrderDisposition.Accepted, "failed at $nextOrderId")
        }
        assertEquals(95, obl.orderHead)
        assertEquals(19, obl.orderTail)
        verifyOrders(obl)

        // remove 20 orders
        (95 until 115).forEach { index ->
            val adjustedIndex = index % obl.maxOrderCount
            val order = obl.orders[adjustedIndex]
            expectedOrderIdSet.remove(order.guid.value)
            obl.removeLevelOrder(order)
        }
        assertEquals(15, obl.orderHead)
        assertEquals(19, obl.orderTail)
        verifyOrders(obl)
    }

    @Test
    fun wrapAroundWhileRemovingFromTheEnd() {
        val obl = OrderBookLevel(300, BookSide.Buy, BigDecimal.ONE, 100)
        // add 95 orders
        (0 until 95).forEach { _ ->
            val addOrderResult = obl.addOrder(0L, getNextOrder(), feeRate = FeeRate.zero)
            assertEquals(addOrderResult.first, OrderDisposition.Accepted, "failed at $nextOrderId")
        }

        // remove 90 orders from head
        (0 until 90).forEach { index ->
            val order = obl.orders[index]
            expectedOrderIdSet.remove(order.guid.value)
            obl.removeLevelOrder(order)
        }
        assertEquals(90, obl.orderHead)
        assertEquals(95, obl.orderTail)
        verifyOrders(obl)

        // add 20 orders to let buffer wrap around
        val twentyOrders = (0 until 20).map {
            val addOrderResult = obl.addOrder(0L, getNextOrder(), feeRate = FeeRate.zero)
            assertEquals(addOrderResult.first, OrderDisposition.Accepted, "failed at $nextOrderId")
            addOrderResult.second!!
        }

        assertEquals(90, obl.orderHead)
        assertEquals(15, obl.orderTail)
        verifyOrders(obl)

        // remove orders from the end to unwrap
        twentyOrders
            .reversed()
            .forEach { levelOrder ->
                expectedOrderIdSet.remove(levelOrder.guid.value)
                obl.removeLevelOrder(levelOrder)
            }
        assertEquals(90, obl.orderHead)
        assertEquals(95, obl.orderTail)
        verifyOrders(obl)
    }

    @Test
    fun totalQuantity() {
        val obl = OrderBookLevel(300, BookSide.Buy, BigDecimal.ONE, 100)
        (1..2000)
            .map { getNextOrder(amount = it.toBigInteger()) }
            .chunked(42)
            .forEach { sublist ->
                val subListAmount = sublist.sumOf { it.amount.toBigInteger() }.toBaseAmount()

                // add chuck of orders and verify totalQuantity
                val addedOrders: List<LevelOrder> = sublist.map { obl.addOrder(0L, it, feeRate = FeeRate.zero).second!! }
                assertEquals(subListAmount, obl.totalQuantity)

                // remove chuck of orders and verify totalQuantity is 0
                addedOrders.forEach { obl.removeLevelOrder(it) }
                assertEquals(BaseAmount.ZERO, obl.totalQuantity)
            }
    }

    @Test
    fun maxOrderCount() {
        val obl = OrderBookLevel(300, BookSide.Buy, BigDecimal.ONE, 100)
        // add 99 orders
        (0 until 99).forEach { _ ->
            val addOrderResult = obl.addOrder(0L, getNextOrder(), feeRate = FeeRate.zero)
            assertEquals(addOrderResult.first, OrderDisposition.Accepted, "failed at $nextOrderId")
        }
        assertEquals(0, obl.orderHead)
        assertEquals(99, obl.orderTail)
        verifyOrders(obl)

        // order number 100 is rejected. Otherwise head and tail will have same value (empty level)
        assertEquals(obl.addOrder(0L, getNextOrder(), feeRate = FeeRate.zero).first, OrderDisposition.Rejected, "failed at $nextOrderId")
    }

    @Test
    fun equalsComparesOrdersBetweenHeadAndTail() {
        val obl1 = OrderBookLevel(300, BookSide.Buy, BigDecimal.ONE, 100)
        assertEquals(0, obl1.orderHead)
        assertEquals(0, obl1.orderTail)

        val obl2 = OrderBookLevel(300, BookSide.Buy, BigDecimal.ONE, 100)
        (0..40)
            .map { obl2.addOrder(0L, getNextOrder(), feeRate = FeeRate.zero).second!! }
            .forEach { obl2.removeLevelOrder(it) }
        assertEquals(40, obl2.orderHead)
        assertEquals(40, obl2.orderTail)

        // empty levels are equal despite head/tail position
        assertEquals(obl1, obl2)

        val fourtyOrders = (40..80).map { getNextOrder(amount = it.toBigInteger()) }
        val obl1Orders = fourtyOrders.map { obl1.addOrder(0L, it, feeRate = FeeRate.zero).second!! }.also {
            assertEquals(0, obl1.orderHead)
            assertEquals(41, obl1.orderTail)
        }
        val obl2Orders = fourtyOrders.map { obl2.addOrder(0L, it, feeRate = FeeRate.zero).second!! }.also {
            assertEquals(40, obl2.orderHead)
            assertEquals(81, obl2.orderTail)
        }

        // only orders between head/tail are compared
        assertEquals(obl1, obl2)

        obl1Orders.forEach { obl1.removeLevelOrder(it) }.also {
            assertEquals(40, obl1.orderHead)
            assertEquals(40, obl1.orderTail)
        }
        obl2Orders.forEach { obl2.removeLevelOrder(it) }.also {
            assertEquals(80, obl2.orderHead)
            assertEquals(80, obl2.orderTail)
        }

        // back to empty
        assertEquals(obl1, obl2)

        fourtyOrders.map { obl1.addOrder(0L, it, feeRate = FeeRate.zero).second!! }.also {
            assertEquals(40, obl1.orderHead)
            assertEquals(81, obl1.orderTail)
        }
        fourtyOrders.map { obl2.addOrder(0L, it, feeRate = FeeRate.zero).second!! }.also {
            assertEquals(80, obl2.orderHead)
            assertEquals(21, obl2.orderTail)
        }

        // buffer can also wrap around
        assertEquals(obl1, obl2)

        // orders between head and tail should match
        obl2.addOrder(0L, getNextOrder(), feeRate = FeeRate.zero)
        assertNotEquals(obl1, obl2)
    }

    private fun getNextOrder(addToSet: Boolean = true, amount: BigInteger = BigInteger.ONE): Order {
        return order {
            this.guid = nextOrderId++
            this.amount = amount.toIntegerValue()
            this.levelIx = 1
            this.type = Order.Type.LimitBuy
        }.also {
            if (addToSet) {
                expectedOrderIdSet.add(it.guid)
            }
        }
    }
}
