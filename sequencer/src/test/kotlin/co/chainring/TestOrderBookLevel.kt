package co.chainring

import co.chainring.sequencer.core.BookSide
import co.chainring.sequencer.core.OrderBookLevel
import co.chainring.sequencer.core.toDecimalValue
import co.chainring.sequencer.core.toIntegerValue
import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.OrderDisposition
import co.chainring.sequencer.proto.order
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class TestOrderBookLevel {

    private var nextOrderId: Long = 1000
    private val expectedOrderIdSet = mutableSetOf<Long>()

    @Test
    fun test() {
        val obl = OrderBookLevel(300, BookSide.Buy, BigDecimal.ONE, 1000)
        (0 until 100).forEach { _ ->
            assertEquals(obl.addOrder(0L, getNextOrder()).first, OrderDisposition.Accepted, "failed at $nextOrderId")
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
            assertEquals(obl.addOrder(0L, getNextOrder()).first, OrderDisposition.Accepted, "failed at $nextOrderId")
        }
        // make sure we fail if we hit the max
        assertEquals(obl.addOrder(0L, getNextOrder(false)).first, OrderDisposition.Rejected)
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

    private fun getNextOrder(addToSet: Boolean = true): Order {
        return order {
            this.guid = nextOrderId++
            this.amount = BigInteger.ONE.toIntegerValue()
            this.price = BigDecimal.ONE.toDecimalValue()
            this.type = Order.Type.LimitBuy
        }.also {
            if (addToSet) {
                expectedOrderIdSet.add(it.guid)
            }
        }
    }
}
