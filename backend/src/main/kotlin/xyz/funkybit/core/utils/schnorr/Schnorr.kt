package xyz.funkybit.core.utils.schnorr

import xyz.funkybit.core.utils.toByteArrayNoSign
import java.math.BigInteger
import java.util.Arrays

/* This implementation was adapted from https://github.com/SamouraiDev/BIP340_Schnorr */

object Schnorr {
    fun sign(msg: ByteArray, secKey: ByteArray?): ByteArray {
        if (msg.size != 32) {
            throw Exception("The message must be a 32-byte array.")
        }
        var secKey0 = BigInteger(1, secKey)

        if (!(BigInteger.ONE <= secKey0 && secKey0 <= Point.n.subtract(BigInteger.ONE))) {
            throw Exception("The secret key must be an integer in the range 1..n-1.")
        }
        val point = Point.G.mul(secKey0)!!
        if (!point.hasSquareY()) {
            secKey0 = Point.n.subtract(secKey0)
        }
        val secKey0Bytes = secKey0.toByteArrayNoSign(32)
        var len: Int = secKey0Bytes.size + msg.size
        var buf = ByteArray(len)
        System.arraycopy(secKey0Bytes, 0, buf, 0, secKey0Bytes.size)
        System.arraycopy(msg, 0, buf, secKey0Bytes.size, msg.size)
        val k0 = BigInteger(1, Point.taggedHash("BIPSchnorrDerive", buf)).mod(Point.n)
        if (k0.compareTo(BigInteger.ZERO) == 0) {
            throw Exception("Failure. This happens only with negligible probability.")
        }
        val r = Point.G.mul(k0)!!
        val k: BigInteger? = if (!r.hasSquareY()) {
            Point.n.subtract(k0)
        } else {
            k0
        }
        val rBytes = r.toBytes()
        len = rBytes.size + point.toBytes().size + msg.size
        buf = ByteArray(len)
        System.arraycopy(rBytes, 0, buf, 0, rBytes.size)
        System.arraycopy(point.toBytes(), 0, buf, rBytes.size, point.toBytes().size)
        System.arraycopy(msg, 0, buf, rBytes.size + point.toBytes().size, msg.size)
        val e: BigInteger = BigInteger(1, Point.taggedHash("BIPSchnorr", buf)).mod(Point.n)
        val kes: BigInteger = k!!.add(e.multiply(secKey0)).mod(Point.n)
        val kesBytes = kes.toByteArrayNoSign(32)
        len = rBytes.size + kesBytes.size
        val ret = ByteArray(len)
        System.arraycopy(rBytes, 0, ret, 0, rBytes.size)
        System.arraycopy(
            kesBytes,
            0,
            ret,
            rBytes.size,
            kesBytes.size,
        )
        return ret
    }

    fun verify(msg: ByteArray, pubkey: ByteArray, sig: ByteArray): Boolean {
        if (msg.size != 32) {
            throw Exception("The message must be a 32-byte array.")
        }
        if (pubkey.size != 32) {
            throw Exception("The public key must be a 32-byte array.")
        }
        if (sig.size != 64) {
            throw Exception("The signature must be a 64-byte array.")
        }

        val point = Point.pointFromBytes(pubkey) ?: return false
        val r = BigInteger(1, Arrays.copyOfRange(sig, 0, 32))
        val s = BigInteger(1, Arrays.copyOfRange(sig, 32, 64))
        if (r >= Point.p || s >= Point.n) {
            return false
        }
        val len = 32 + pubkey.size + msg.size
        val buf = ByteArray(len)
        System.arraycopy(sig, 0, buf, 0, 32)
        System.arraycopy(pubkey, 0, buf, 32, pubkey.size)
        System.arraycopy(msg, 0, buf, 32 + pubkey.size, msg.size)
        val e: BigInteger = BigInteger(1, Point.taggedHash("BIPSchnorr", buf)).mod(Point.n)
        val r2 = Point.G.mul(s)!!.add(point.mul(Point.n.subtract(e)))
        return !(r2 == null || !r2.hasSquareY() || r2.getX().compareTo(r) != 0)
    }
}
