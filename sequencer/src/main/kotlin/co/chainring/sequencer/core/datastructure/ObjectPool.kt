package co.chainring.sequencer.core.datastructure

class ObjectPool<T>(
    private val create: () -> T,
    private val reset: (T) -> Unit,
    initialSize: Int,
) {
    private var borrowedCount = 0
    private val pool: MutableList<T> = ArrayList<T>(initialSize).apply {
        repeat(initialSize) {
            add(create())
        }
    }

    fun borrow(init: ((T) -> T)? = null): T {
        borrowedCount++
        val obj = pool.removeLastOrNull() ?: create()
        return init?.invoke(obj) ?: obj
    }

    fun release(obj: T) {
        reset(obj)
        pool.add(obj)
        borrowedCount--
    }

    fun getBorrowedCount(): Int = borrowedCount

    fun getPoolSize(): Int = pool.size
}
