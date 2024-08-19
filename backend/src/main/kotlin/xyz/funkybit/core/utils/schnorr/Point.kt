package xyz.funkybit.core.utils.schnorr

import xyz.funkybit.core.utils.sha256
import xyz.funkybit.core.utils.toByteArrayNoSign
import java.math.BigInteger

/* This implementation was adapted from https://github.com/SamouraiDev/BIP340_Schnorr */

class Point(x: BigInteger?, y: BigInteger?) {
    private val pair = Pair(x, y)

    companion object {
        val p: BigInteger = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
        val n: BigInteger = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)

        val G: Point = Point(
            BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16),
            BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16),
        )

        fun taggedHash(tag: String, msg: ByteArray): ByteArray {
            return sha256(sha256(tag.toByteArray()) + sha256(tag.toByteArray()) + msg)
        }

        fun pointFromBytes(b: ByteArray?): Point? {
            val x = BigInteger(1, b)
            if (x >= p) {
                return null
            }
            val ySq: BigInteger = x.modPow(BigInteger.valueOf(3L), p).add(BigInteger.valueOf(7L)).mod(p)
            val y: BigInteger = ySq.modPow(p.add(BigInteger.ONE).divide(BigInteger.valueOf(4L)), p)

            return if (y.modPow(BigInteger.TWO, p).compareTo(ySq) != 0) {
                null
            } else {
                Point(x, if (y.and(BigInteger.ONE) == BigInteger.ZERO) y else p.subtract(y))
            }
        }

        fun bytesFromPoint(point: Point): ByteArray {
            return point.getX().toByteArrayNoSign(32)
        }

        fun genPubKey(secKey: ByteArray?): ByteArray {
            val x = BigInteger(1, secKey)
            if (!(BigInteger.ONE <= x && x <= n.subtract(BigInteger.ONE))) {
                throw Exception("The secret key must be an integer in the range 1..n-1.")
            }
            val ret = G.mul(x)!!
            return bytesFromPoint(ret)
        }

        private fun infinityPoint(): Point {
            return Point(null as BigInteger?, null as BigInteger?)
        }

        fun add(point1: Point?, point2: Point?): Point? {
            if ((point1 != null && point2 != null && point1.isInfinity() && point2.isInfinity())) {
                return infinityPoint()
            }
            if (point1 == null || point1.isInfinity()) {
                return point2
            }
            if (point2 == null || point2.isInfinity()) {
                return point1
            }
            if (point1.getX() == point2.getX() && point1.getY() != point2.getY()) {
                return infinityPoint()
            }

            val lam = if (point1.isEqual(point2)) {
                val base: BigInteger = point2.getY().multiply(BigInteger.TWO)
                BigInteger.valueOf(3L).multiply(point1.getX()).multiply(point1.getX())
                    .multiply(base.modPow(p.subtract(BigInteger.TWO), p))
                    .mod(p)
            } else {
                val base: BigInteger = point2.getX().subtract(point1.getX())
                point2.getY().subtract(point1.getY())
                    .multiply(base.modPow(p.subtract(BigInteger.TWO), p)).mod(p)
            }

            val x3: BigInteger = lam.multiply(lam).subtract(point1.getX()).subtract(point2.getX()).mod(p)
            return Point(x3, lam.multiply(point1.getX().subtract(x3)).subtract(point1.getY()).mod(p))
        }
    }

    fun getX(): BigInteger {
        return pair.first!!
    }

    fun getY(): BigInteger {
        return pair.second!!
    }

    private fun getPair(): Pair<BigInteger?, BigInteger?> {
        return pair
    }

    fun isInfinity(): Boolean {
        return pair.first == null || pair.second == null
    }

    fun add(point: Point?): Point? {
        return add(this, point)
    }

    fun mul(n: BigInteger): Point? {
        return mul(this, n)
    }

    private fun mul(p: Point?, n: BigInteger): Point? {
        var point = p
        var r: Point? = null

        for (i in 0..255) {
            if (n.shiftRight(i).and(BigInteger.ONE) > BigInteger.ZERO) {
                r = add(r, point)
            }
            point = add(point, point)
        }

        return r
    }

    private fun isEven(x: BigInteger): Boolean {
        return x.mod(BigInteger.TWO) == BigInteger.ZERO
    }

    fun hasEvenY(): Boolean {
        return hasEvenY(this)
    }

    private fun hasEvenY(point: Point): Boolean {
        return (!point.isInfinity() && isEven(point.getY()))
    }

    fun isEqual(point: Point): Boolean {
        return getPair() == point.getPair()
    }

    override fun toString(): String {
        return "Point$pair"
    }
}
