package co.chainring.sequencer.core

import kotlin.random.Random
import kotlin.system.measureNanoTime

class MarketLevels {

    data class TreeNode(
        var value: OrderBookLevel,
        var height: Int = 1,
        var left: TreeNode? = null,
        var right: TreeNode? = null,
        var parent: TreeNode? = null,
    ) {
        fun next(): TreeNode? {
            var current: TreeNode? = this
            if (current?.right != null) {
                current = current.right
                while (current?.left != null) {
                    current = current.left
                }
                return current
            } else {
                var parent = current?.parent
                while (parent != null && current == parent.right) {
                    current = parent
                    parent = parent.parent
                }
                return parent
            }
        }

        fun previous(): TreeNode? {
            var current: TreeNode? = this
            if (current?.left != null) {
                current = current.left
                while (current?.right != null) {
                    current = current.right
                }
                return current
            } else {
                var parent = current?.parent
                while (parent != null && current == parent.left) {
                    current = parent
                    parent = parent.parent
                }
                return parent
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TreeNode) return false
            return value == other.value
        }

        override fun hashCode(): Int {
            return value.hashCode()
        }

        override fun toString(): String {
            return "Node(levelIx=${value.levelIx}, levelIx=${value.price}, quantity=${value.totalQuantity}, height=$height)"
        }
    }

    private var root: TreeNode? = null

    fun add(value: OrderBookLevel): OrderBookLevel {
        root = add(root, value)
        return value
    }

    private fun add(treeNode: TreeNode?, value: OrderBookLevel): TreeNode {
        if (treeNode == null) return TreeNode(value)

        if (value.levelIx < treeNode.value.levelIx) {
            treeNode.left = add(treeNode.left, value)
            treeNode.left?.parent = treeNode
        } else if (value.levelIx > treeNode.value.levelIx) {
            treeNode.right = add(treeNode.right, value)
            treeNode.right?.parent = treeNode
        } else {
            return treeNode
        }

        treeNode.height = 1 + maxOf(height(treeNode.left), height(treeNode.right))

        return balance(treeNode)
    }

    fun get(levelIx: Int): OrderBookLevel? {
        val node = getTreeNode(root, levelIx)
        return node?.value
    }

    fun getTreeNode(levelIx: Int): TreeNode? {
        return getTreeNode(root, levelIx)
    }

    private fun getTreeNode(treeNode: TreeNode?, levelIx: Int): TreeNode? {
        if (treeNode == null || treeNode.value.levelIx == levelIx) return treeNode

        return if (levelIx < treeNode.value.levelIx) {
            getTreeNode(treeNode.left, levelIx)
        } else {
            getTreeNode(treeNode.right, levelIx)
        }
    }

    fun remove(levelIx: Int) {
        root = remove(root, levelIx)
    }

    private fun remove(treeNode: TreeNode?, levelIx: Int): TreeNode? {
        if (treeNode == null) return treeNode

        when {
            levelIx < treeNode.value.levelIx -> {
                treeNode.left = remove(treeNode.left, levelIx)
            }
            levelIx > treeNode.value.levelIx -> {
                treeNode.right = remove(treeNode.right, levelIx)
            }
            else -> {
                if (treeNode.left == null || treeNode.right == null) {
                    val temp = treeNode.left ?: treeNode.right

                    if (temp == null) {
                        return null
                    } else {
                        temp.parent = treeNode.parent
                        return temp
                    }
                }

                val temp = minValueNode(treeNode.right!!)
                treeNode.value = temp.value
                treeNode.right = remove(treeNode.right, temp.value.levelIx)
            }
        }

        treeNode.height = 1 + maxOf(height(treeNode.left), height(treeNode.right))

        return balance(treeNode)
    }

    fun first(): TreeNode? {
        var current = root ?: return null
        while (current.left != null) {
            current = current.left!!
        }
        return current
    }

    fun last(): TreeNode? {
        var current = root ?: return null
        while (current.right != null) {
            current = current.right!!
        }
        return current
    }

    private fun height(treeNode: TreeNode?): Int {
        return treeNode?.height ?: 0
    }

    private fun balance(treeNode: TreeNode): TreeNode {
        val balance = getBalance(treeNode)

        return when {
            balance > 1 && getBalance(treeNode.left) >= 0 -> rightRotate(treeNode)
            balance > 1 && getBalance(treeNode.left) < 0 -> {
                treeNode.left = leftRotate(treeNode.left!!)
                rightRotate(treeNode)
            }
            balance < -1 && getBalance(treeNode.right) <= 0 -> leftRotate(treeNode)
            balance < -1 && getBalance(treeNode.right) > 0 -> {
                treeNode.right = rightRotate(treeNode.right!!)
                leftRotate(treeNode)
            }
            else -> treeNode
        }
    }

    private fun getBalance(treeNode: TreeNode?): Int {
        return if (treeNode == null) 0 else height(treeNode.left) - height(treeNode.right)
    }

    private fun rightRotate(y: TreeNode): TreeNode {
        val x = y.left!!
        val T2 = x.right

        x.right = y
        y.left = T2

        y.height = maxOf(height(y.left), height(y.right)) + 1
        x.height = maxOf(height(x.left), height(x.right)) + 1

        x.parent = y.parent
        y.parent = x
        if (T2 != null) T2.parent = y

        return x
    }

    private fun leftRotate(x: TreeNode): TreeNode {
        val y = x.right!!
        val T2 = y.left

        y.left = x
        x.right = T2

        x.height = maxOf(height(x.left), height(x.right)) + 1
        y.height = maxOf(height(y.left), height(y.right)) + 1

        y.parent = x.parent
        x.parent = y
        if (T2 != null) T2.parent = x

        return y
    }

    private fun minValueNode(treeNode: TreeNode): TreeNode {
        var current = treeNode
        while (current.left != null) {
            current = current.left!!
        }
        return current
    }

    fun traverse(action: (OrderBookLevel) -> Unit) {
        inorderTraversal(root, action)
    }

    private fun inorderTraversal(treeNode: TreeNode?, action: (OrderBookLevel) -> Unit) {
        if (treeNode != null) {
            inorderTraversal(treeNode.left, action)
            action(treeNode.value)
            inorderTraversal(treeNode.right, action)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MarketLevels) return false

        var thisNode = this.first()
        var otherNode = other.first()

        while (thisNode != null && otherNode != null) {
            if (thisNode.value != otherNode.value) return false
            thisNode = thisNode.next()
            otherNode = otherNode.next()
        }

        return thisNode == null && otherNode == null
    }

    override fun hashCode(): Int {
        var result = 1
        var node = first()

        while (node != null) {
            result = 31 * result + node.value.hashCode()
            node = node.next()
        }

        return result
    }
}

fun main() {
    val tree = MarketLevels()

    repeat(50) { i ->
        measureNanoTime {
            repeat(1000) { j ->
                tree.add(OrderBookLevel(i * 1000 + j, side = BookSide.Sell, (i * 1000 + j).toBigDecimal(), 0))
            }
        }.also {
            println("Adding 1000 entries took $it nanos")
        }
    }

    repeat(50) { i ->
        measureNanoTime {
            repeat(1000) { j ->
                tree.get(Random.nextInt())
            }
        }.also {
            println("Getting 1000 entries took $it nanos")
        }
    }

    /*repeat(100) { i ->
        measureNanoTime {
            (0 until 1000).forEach { j ->
                tree.delete(i * j)
            }
        }.also {
            println("Removing 1000 entries took $it nanos")
        }
    }*/

    // skipList.dumpListState()
}
