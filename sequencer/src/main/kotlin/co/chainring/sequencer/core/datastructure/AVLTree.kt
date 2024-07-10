package co.chainring.sequencer.core.datastructure

class AVLTree<T : AVLTree.Node<T>> {

    abstract class Node<T : Node<T>>(
        val id: Int,
        var height: Int = 1,
        var left: T? = null,
        var right: T? = null,
        var parent: T? = null,
    ) {
        fun next(): T? {
            var current: T? = this as T
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

        fun previous(): T? {
            var current: T? = this as T
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
    }

    private var root: T? = null

    fun add(value: T): T {
        root = add(root, value)
        return value
    }

    private fun add(treeNode: T?, value: T): T {
        if (treeNode == null) return value

        if (value.id < treeNode.id) {
            treeNode.left = add(treeNode.left, value)
            treeNode.left?.parent = treeNode
        } else if (value.id > treeNode.id) {
            treeNode.right = add(treeNode.right, value)
            treeNode.right?.parent = treeNode
        } else {
            return treeNode
        }

        treeNode.height = 1 + maxOf(height(treeNode.left), height(treeNode.right))

        return balance(treeNode)
    }

    fun get(levelIx: Int): T? {
        return getTreeNode(root, levelIx)
    }

    fun getTreeNode(levelIx: Int): T? {
        return getTreeNode(root, levelIx)
    }

    private fun getTreeNode(treeNode: T?, levelIx: Int): T? {
        if (treeNode == null || treeNode.id == levelIx) return treeNode

        return if (levelIx < treeNode.id) {
            getTreeNode(treeNode.left, levelIx)
        } else {
            getTreeNode(treeNode.right, levelIx)
        }
    }

    fun remove(levelIx: Int) {
        root = remove(root, levelIx)
    }

    private fun remove(treeNode: T?, levelIx: Int): T? {
        if (treeNode == null) return null

        when {
            levelIx < treeNode.id -> {
                treeNode.left = remove(treeNode.left, levelIx)
            }
            levelIx > treeNode.id -> {
                treeNode.right = remove(treeNode.right, levelIx)
            }
            else -> {
                // node with only one child or no child
                if (treeNode.left == null || treeNode.right == null) {
                    val temp = treeNode.left ?: treeNode.right
                    if (temp == null) {
                        // no child case
                        return null
                    } else {
                        // one child case
                        temp.parent = treeNode.parent
                        return temp
                    }
                }

                // node with two children
                val successor = minValueNode(treeNode.right!!)

                // connect the right child of the successor to its parent
                if (successor.parent != treeNode) {
                    successor.parent?.left = successor.right
                    if (successor.right != null) {
                        successor.right?.parent = successor.parent
                    }

                    successor.right = treeNode.right
                    successor.right?.parent = successor
                }

                // Connect the left child of the treeNode to the successor
                successor.left = treeNode.left
                successor.left?.parent = successor

                // Update the parent of the treeNode to point to the successor
                successor.parent = treeNode.parent
                if (treeNode.parent == null) {
                    root = successor
                } else if (treeNode == treeNode.parent?.left) {
                    treeNode.parent?.left = successor
                } else {
                    treeNode.parent?.right = successor
                }

                treeNode.left = null
                treeNode.right = null
                treeNode.parent = null

                treeNode.height = 1 + maxOf(height(treeNode.left), height(treeNode.right))

                return balance(successor)
            }
        }

        treeNode.height = 1 + maxOf(height(treeNode.left), height(treeNode.right))

        return balance(treeNode)
    }

    fun first(): T? {
        var current = root ?: return null
        while (current.left != null) {
            current = current.left!!
        }
        return current
    }

    fun last(): T? {
        var current = root ?: return null
        while (current.right != null) {
            current = current.right!!
        }
        return current
    }

    private fun height(treeNode: T?): Int {
        return treeNode?.height ?: 0
    }

    private fun balance(treeNode: T): T {
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

    private fun getBalance(treeNode: T?): Int {
        return if (treeNode == null) 0 else height(treeNode.left) - height(treeNode.right)
    }

    private fun rightRotate(y: T): T {
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

    private fun leftRotate(x: T): T {
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

    private fun minValueNode(treeNode: T): T {
        var current = treeNode
        while (current.left != null) {
            current = current.left!!
        }
        return current
    }

    fun traverse(action: (T) -> Unit) {
        inorderTraversal(root, action)
    }

    private fun inorderTraversal(treeNode: T?, action: (T) -> Unit) {
        if (treeNode != null) {
            inorderTraversal(treeNode.left, action)
            action(treeNode)
            inorderTraversal(treeNode.right, action)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AVLTree<*>) return false

        var thisNode = this.first()
        var otherNode = other.first()

        while (thisNode != null && otherNode != null) {
            if (thisNode != otherNode) return false
            thisNode = thisNode.next()
            otherNode = otherNode.next()
        }

        return thisNode == null && otherNode == null
    }

    override fun hashCode(): Int {
        var result = 1
        var node = first()

        while (node != null) {
            result = 31 * result + node.hashCode()
            node = node.next()
        }

        return result
    }
}
