package co.chainring.sequencer.core.datastructure

class AVLTree<T : AVLTree.Node<T>> {

    abstract class Node<T : Node<T>>(
        var ix: Int,
        var height: Int = 1,
        var left: T? = null,
        var right: T? = null,
        var parent: T? = null,
    ) {
        @Suppress("UNCHECKED_CAST")
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

        @Suppress("UNCHECKED_CAST")
        fun prev(): T? {
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

        open fun reset() {
            ix = 0
            height = 1
            left = null
            right = null
            parent = null
        }
    }

    private var root: T? = null

    fun add(value: T): T {
        root = add(root, value)
        return value
    }

    private fun add(parent: T?, value: T): T {
        if (parent == null) return value

        if (value.ix < parent.ix) {
            parent.left = add(parent.left, value)
            parent.left?.parent = parent
        } else if (value.ix > parent.ix) {
            parent.right = add(parent.right, value)
            parent.right?.parent = parent
        } else {
            return parent
        }

        parent.height = 1 + maxOf(height(parent.left), height(parent.right))

        return balance(parent)
    }

    fun get(ix: Int): T? {
        return get(root, ix)
    }

    private fun get(parent: T?, ix: Int): T? {
        if (parent == null || parent.ix == ix) return parent

        return if (ix < parent.ix) {
            get(parent.left, ix)
        } else {
            get(parent.right, ix)
        }
    }

    fun remove(ix: Int) {
        root = remove(root, ix)
    }

    private fun remove(parent: T?, ix: Int): T? {
        if (parent == null) return null

        when {
            ix < parent.ix -> {
                parent.left = remove(parent.left, ix)
            }
            ix > parent.ix -> {
                parent.right = remove(parent.right, ix)
            }
            else -> {
                // node with only one child or no child
                if (parent.left == null || parent.right == null) {
                    val temp = parent.left ?: parent.right
                    if (temp == null) {
                        // no child case
                        return null
                    } else {
                        // one child case
                        temp.parent = parent.parent
                        return temp
                    }
                }

                // node with two children
                val successor = minValueNode(parent.right!!)

                // connect the right child of the successor to its parent
                if (successor.parent != parent) {
                    successor.parent?.left = successor.right
                    if (successor.right != null) {
                        successor.right?.parent = successor.parent
                    }

                    successor.right = parent.right
                    successor.right?.parent = successor
                }

                // connect the left child of the treeNode to the successor
                successor.left = parent.left
                successor.left?.parent = successor

                // update the parent of the treeNode to point to the successor
                successor.parent = parent.parent
                if (parent.parent == null) {
                    root = successor
                } else if (parent == parent.parent?.left) {
                    parent.parent?.left = successor
                } else {
                    parent.parent?.right = successor
                }

                parent.left = null
                parent.right = null
                parent.parent = null

                parent.height = 1 + maxOf(height(parent.left), height(parent.right))

                return balance(successor)
            }
        }

        parent.height = 1 + maxOf(height(parent.left), height(parent.right))

        return balance(parent)
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
        val t2 = x.right

        x.right = y
        y.left = t2

        y.height = maxOf(height(y.left), height(y.right)) + 1
        x.height = maxOf(height(x.left), height(x.right)) + 1

        x.parent = y.parent
        y.parent = x
        if (t2 != null) t2.parent = y

        return x
    }

    private fun leftRotate(x: T): T {
        val y = x.right!!
        val t2 = y.left

        y.left = x
        x.right = t2

        x.height = maxOf(height(x.left), height(x.right)) + 1
        y.height = maxOf(height(y.left), height(y.right)) + 1

        y.parent = x.parent
        x.parent = y
        if (t2 != null) t2.parent = x

        return y
    }

    private fun minValueNode(parent: T): T {
        var current = parent
        while (current.left != null) {
            current = current.left!!
        }
        return current
    }

    fun traverse(action: (T) -> Unit) {
        traverse(root, action)
    }

    private fun traverse(parent: T?, action: (T) -> Unit) {
        if (parent != null) {
            traverse(parent.left, action)
            action(parent)
            traverse(parent.right, action)
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
