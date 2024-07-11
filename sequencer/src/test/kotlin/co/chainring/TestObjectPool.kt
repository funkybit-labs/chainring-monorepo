package co.chainring

import co.chainring.sequencer.core.datastructure.ObjectPool
import kotlin.random.Random
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class TestObjectPool {

    data class TestObject(var value: Int)

    @Test
    fun testBorrowAndReturn() {
        val pool = ObjectPool(
            create = { TestObject(Int.MIN_VALUE) },
            reset = { it.value = Int.MIN_VALUE },
            initialSize = 100
        )
        assertEquals(100, pool.getPoolSize())
        assertEquals(0, pool.getBorrowedCount())

        // borrow object
        val obj1 = pool.borrow(init = { it.also { it.value = Random.nextInt(0, Int.MAX_VALUE) } })
        assertEquals(99, pool.getPoolSize())
        assertEquals(1, pool.getBorrowedCount())

        // borrow all
        val allOther = (1 until 100).map { pool.borrow(init = { it.also { it.value = Random.nextInt(0, Int.MAX_VALUE) } }) }
        assertEquals(0, pool.getPoolSize())
        assertEquals(100, pool.getBorrowedCount())

        // release object
        pool.release(obj1)
        assertEquals(1, pool.getPoolSize())
        assertEquals(99, pool.getBorrowedCount())

        // release all
        allOther.forEach { pool.release(it) }
        assertEquals(100, pool.getPoolSize())
        assertEquals(0, pool.getBorrowedCount())
    }

    @Test
    fun testRecycleObjects() {
        val pool = ObjectPool(
            create = { TestObject(Int.MIN_VALUE) },
            reset = { it.value = Int.MIN_VALUE },
            initialSize = 100
        )

        val allObjectsAttempt1 = (1 until 100).map { pool.borrow(init = { it.also { it.value = 1 } }) }
        allObjectsAttempt1.forEach { assertEquals(1, it.value)}
        allObjectsAttempt1.forEach { pool.release(it) }
        allObjectsAttempt1.forEach { assertEquals(Int.MIN_VALUE, it.value)}

        val allObjectsAttempt2 = (1 until 100).map { pool.borrow(init = { it.also { it.value = 2 } }) }
        allObjectsAttempt2.forEach { assertEquals(2, it.value)}
        allObjectsAttempt2.forEach { pool.release(it) }
        allObjectsAttempt2.forEach { assertEquals(Int.MIN_VALUE, it.value)}

        val allObjectsAttempt3 = (1 until 100).map { pool.borrow(init = { it.also { it.value = 3 } }) }
        allObjectsAttempt3.forEach { assertEquals(3, it.value)}
    }

    @Test
    fun testCanGetAnyAmountOfObjectsFromThePool() {
        val pool = ObjectPool(
            create = { TestObject(Int.MIN_VALUE) },
            reset = { it.value = Int.MIN_VALUE },
            initialSize = 100
        )

        val allExisting = (0 until 100).map { pool.borrow(init = { it.also { it.value = 1 } }) }
        allExisting.forEach { assertEquals(1, it.value)}
        assertEquals(0, pool.getPoolSize())
        assertEquals(100, pool.getBorrowedCount())

        val newObjectsWithInit = (0 until 100).map { pool.borrow(init = { it.also { it.value = 2 } }) }
        assertEquals(0, pool.getPoolSize())
        assertEquals(200, pool.getBorrowedCount())

        val newObjectsWithoutInit = (0 until 100).map { pool.borrow() }
        assertEquals(0, pool.getPoolSize())
        assertEquals(300, pool.getBorrowedCount())

        // release all
        allExisting.forEach { pool.release(it) }
        newObjectsWithInit.forEach { pool.release(it) }
        newObjectsWithoutInit.forEach { pool.release(it) }

        // pool size has increased
        assertEquals(300, pool.getPoolSize())
        assertEquals(0, pool.getBorrowedCount())
    }

    @Test
    fun testObjectInitOnBorrow() {
        val pool = ObjectPool(
            create = { TestObject(Int.MIN_VALUE) },
            reset = { it.value = Int.MIN_VALUE },
            initialSize = 100
        )

        val obj1 = pool.borrow()
        assertEquals(Int.MIN_VALUE, obj1.value)

        val obj2 = pool.borrow(init = { it.also { it.value = 10 } })
        assertEquals(10, obj2.value)
    }

    @Test
    fun testResetOnRelease() {
        val pool = ObjectPool(
            create = { TestObject(Int.MIN_VALUE) },
            reset = { it.value = Int.MIN_VALUE },
            initialSize = 100
        )

        val obj = pool.borrow { it.also { it.value = 10 } }
        assertEquals(10, obj.value)

        pool.release(obj)
        assertEquals(Int.MIN_VALUE, obj.value)
    }

}
