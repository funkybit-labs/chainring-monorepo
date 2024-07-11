package co.chainring.datastructure

import co.chainring.sequencer.core.datastructure.AVLTree
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TestAVLTree {

    class TestLevel(ix: Int) : AVLTree.Node<TestLevel>(ix)

    @Test
    fun testInsert1() {
        //        20+      20++         20++      15
        //       /        /            /         /  \
        //      4     => 4-     =>   15+     => 4    20
        //                \         /
        //                 15      4

        val avlTree = AVLTree<TestLevel>()
        avlTree.add(TestLevel(20))
        avlTree.add(TestLevel(4))

        avlTree.add(TestLevel(15))
        assertEquals(2, avlTree.get(15)?.height)
        assertEquals(1, avlTree.get(4)?.height)
        assertEquals(1, avlTree.get(20)?.height)
    }

    @Test
    fun testInsert2() {
        //          20+          20++           20++         9
        //         /  \         /  \           /  \         / \
        //        4    26 =>   4-   26 =>     9+   26 =>   4+  20
        //       / \          / \            / \          /   /  \
        //      3   9        3   9-         4+  15       3  15    26
        //                         \       /
        //                          15    3

        val avlTree = AVLTree<TestLevel>()
        avlTree.add(TestLevel(20))
        avlTree.add(TestLevel(4))
        avlTree.add(TestLevel(26))
        avlTree.add(TestLevel(3))
        avlTree.add(TestLevel(9))

        avlTree.add(TestLevel(15))
        assertEquals(3, avlTree.get(9)?.height)
        assertEquals(2, avlTree.get(4)?.height)
        assertEquals(2, avlTree.get(20)?.height)
        assertEquals(1, avlTree.get(3)?.height)
        assertEquals(1, avlTree.get(15)?.height)
        assertEquals(1, avlTree.get(26)?.height)
    }

    @Test
    fun testInsert3() {
        //          20+          20++           20++         9
        //         /  \         /  \           /  \         / \
        //        4    26 =>   4-   26 =>     9++  26 =>   4   20-
        //       / \          / \            /            / \    \
        //      3   9        3   9+         4            3   8    26
        //                      /          / \
        //                     8          3   8

        val avlTree = AVLTree<TestLevel>()
        avlTree.add(TestLevel(20))
        avlTree.add(TestLevel(4))
        avlTree.add(TestLevel(26))
        avlTree.add(TestLevel(3))
        avlTree.add(TestLevel(9))

        avlTree.add(TestLevel(8))
        assertEquals(3, avlTree.get(9)?.height)
        assertEquals(2, avlTree.get(4)?.height)
        assertEquals(2, avlTree.get(20)?.height)
        assertEquals(1, avlTree.get(3)?.height)
        assertEquals(1, avlTree.get(8)?.height)
        assertEquals(1, avlTree.get(26)?.height)
    }

    @Test
    fun testInsert4() {
        //            __20+__                _20++_                  __20++_                ___9___
        //           /       \              /      \                /       \              /       \
        //          4         26    =>     4-       26    =>       9+        26    =>     4+      __20__
        //         / \       /  \         / \      /  \           / \       /  \         / \     /      \
        //        3+  9    21    30      3+  9-  21    30        4+  11-  21    30      3+  7  11-       26
        //       /   / \                /   / \                 / \   \                /         \      /  \
        //      2   7   11             2   7   11-             3+  7   15             2           15  21    30
        //                                       \            /
        //                                        15         2

        val avlTree = AVLTree<TestLevel>()
        avlTree.add(TestLevel(20))
        avlTree.add(TestLevel(4))
        avlTree.add(TestLevel(26))
        avlTree.add(TestLevel(3))
        avlTree.add(TestLevel(9))
        avlTree.add(TestLevel(21))
        avlTree.add(TestLevel(30))
        avlTree.add(TestLevel(2))
        avlTree.add(TestLevel(7))
        avlTree.add(TestLevel(11))

        avlTree.add(TestLevel(15))
        assertEquals(4, avlTree.get(9)?.height)
        assertEquals(3, avlTree.get(4)?.height)
        assertEquals(3, avlTree.get(20)?.height)
        assertEquals(2, avlTree.get(3)?.height)
        assertEquals(1, avlTree.get(7)?.height)
        assertEquals(2, avlTree.get(11)?.height)
        assertEquals(2, avlTree.get(26)?.height)
        assertEquals(1, avlTree.get(2)?.height)
        assertEquals(1, avlTree.get(15)?.height)
        assertEquals(1, avlTree.get(21)?.height)
        assertEquals(1, avlTree.get(30)?.height)
    }

    @Test
    fun testInsertRotate5() {
        //            __20+__                _20++_                  __20++_                ___9___
        //           /       \              /      \                /       \              /       \
        //          4         26           4-       26             9+        26           4        _20-
        //         / \       /  \         / \      /  \           / \       /  \         / \      /    \
        //        3+  9    21    30 =>   3+  9+  21    30 =>     4   11   21    30 =>   3+  7-  11      26
        //       /   / \                /   / \                 / \                    /     \         /  \
        //      2   7   11             2   7-  11              3+  7-                 2       8      21    30
        //                                  \                 /     \
        //                                   8               2       8

        val avlTree = AVLTree<TestLevel>()
        avlTree.add(TestLevel(20))
        avlTree.add(TestLevel(4))
        avlTree.add(TestLevel(26))
        avlTree.add(TestLevel(3))
        avlTree.add(TestLevel(9))
        avlTree.add(TestLevel(21))
        avlTree.add(TestLevel(30))
        avlTree.add(TestLevel(2))
        avlTree.add(TestLevel(7))
        avlTree.add(TestLevel(11))

        avlTree.add(TestLevel(8))
        assertEquals(4, avlTree.get(9)?.height)
        assertEquals(3, avlTree.get(4)?.height)
        assertEquals(3, avlTree.get(20)?.height)
        assertEquals(2, avlTree.get(3)?.height)
        assertEquals(2, avlTree.get(7)?.height)
        assertEquals(1, avlTree.get(11)?.height)
        assertEquals(2, avlTree.get(26)?.height)
        assertEquals(1, avlTree.get(2)?.height)
        assertEquals(1, avlTree.get(8)?.height)
        assertEquals(1, avlTree.get(21)?.height)
        assertEquals(1, avlTree.get(30)?.height)
    }

    @Test
    fun testDelete1() {
        //        2          2            4
        //       / \          \          / \
        //      1   4    =>    4    =>  2   5
        //         / \        / \        \
        //        3   5      3   5        3

        val avlTree = AVLTree<TestLevel>()
        avlTree.add(TestLevel(2))
        avlTree.add(TestLevel(1))
        avlTree.add(TestLevel(4))
        avlTree.add(TestLevel(3))
        avlTree.add(TestLevel(5))

        avlTree.remove(1)
        assertEquals(3, avlTree.get(4)?.height)
        assertEquals(2, avlTree.get(2)?.height)
        assertEquals(1, avlTree.get(5)?.height)
        assertEquals(1, avlTree.get(3)?.height)
    }

    @Test
    fun testDelete2() {
        //          ___6___                ___6___                 ___6___
        //         /       \              /       \               /       \
        //        2         9            2         9             4         9
        //       / \       / \            \       / \           / \       / \
        //      1   4     8   11    =>     4     8   11     => 2   5     8   11
        //         / \   /   / \          / \   /   / \         \       /   / \
        //        3   5 7   10  12       3   5 7   10  12        3     7   10  12
        //                       \                      \                       \
        //                        13                     13                      13

        val avlTree = AVLTree<TestLevel>()
        avlTree.add(TestLevel(6))
        avlTree.add(TestLevel(2))
        avlTree.add(TestLevel(9))
        avlTree.add(TestLevel(1))
        avlTree.add(TestLevel(4))
        avlTree.add(TestLevel(8))
        avlTree.add(TestLevel(11))
        avlTree.add(TestLevel(3))
        avlTree.add(TestLevel(5))
        avlTree.add(TestLevel(7))
        avlTree.add(TestLevel(10))
        avlTree.add(TestLevel(12))
        avlTree.add(TestLevel(13))

        avlTree.remove(1)
        assertEquals(5, avlTree.get(6)?.height)
        assertEquals(3, avlTree.get(4)?.height)
        assertEquals(4, avlTree.get(9)?.height)
        assertEquals(2, avlTree.get(2)?.height)
        assertEquals(1, avlTree.get(5)?.height)
        assertEquals(2, avlTree.get(8)?.height)
        assertEquals(3, avlTree.get(11)?.height)
        assertEquals(1, avlTree.get(3)?.height)
        assertEquals(1, avlTree.get(7)?.height)
        assertEquals(1, avlTree.get(10)?.height)
        assertEquals(2, avlTree.get(12)?.height)
        assertEquals(1, avlTree.get(13)?.height)
    }

    @Test
    fun testDelete3() {
        //          ___5___              ___5___                 ___5___                   ____8____
        //         /       \            /       \               /       \                 /         \
        //        2         8          2         8             3         8              _5_          10
        //       / \       / \          \       / \           / \       / \            /   \        / \
        //      1   3     7   10    =>   3     7   10     => 2   4     7   10    =>   3     7      9   11
        //           \   /   / \          \   /   / \                 /   / \        / \   /            \
        //            4 6   9   11         4 6   9   11              6   9   11     2   4 6              12
        //                       \                    \                       \
        //                        12                   12                      12

        val avlTree = AVLTree<TestLevel>()
        avlTree.add(TestLevel(5))
        avlTree.add(TestLevel(2))
        avlTree.add(TestLevel(8))
        avlTree.add(TestLevel(1))
        avlTree.add(TestLevel(3))
        avlTree.add(TestLevel(7))
        avlTree.add(TestLevel(10))
        avlTree.add(TestLevel(4))
        avlTree.add(TestLevel(6))
        avlTree.add(TestLevel(9))
        avlTree.add(TestLevel(11))
        avlTree.add(TestLevel(12))

        avlTree.remove(1)
        assertEquals(4, avlTree.get(8)?.height)
        assertEquals(3, avlTree.get(5)?.height)
        assertEquals(3, avlTree.get(10)?.height)
        assertEquals(2, avlTree.get(3)?.height)
        assertEquals(2, avlTree.get(7)?.height)
        assertEquals(1, avlTree.get(9)?.height)
        assertEquals(2, avlTree.get(11)?.height)
        assertEquals(1, avlTree.get(2)?.height)
        assertEquals(1, avlTree.get(4)?.height)
        assertEquals(1, avlTree.get(6)?.height)
        assertEquals(1, avlTree.get(12)?.height)
    }
}
