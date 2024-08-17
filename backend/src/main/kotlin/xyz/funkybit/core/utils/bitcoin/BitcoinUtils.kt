package xyz.funkybit.core.utils.bitcoin

import org.bitcoinj.core.Address
import org.bitcoinj.core.Base58
import org.bitcoinj.core.NetworkParameters
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest
import java.security.PublicKey

data class AddressAndScriptInfo(
    val address: String,
    val redeemScriptHex: String,
)

object BitcoinUtils {
    init {
        java.security.Security.addProvider(BouncyCastleProvider())
    }

    fun isValidAddress(networkParameters: NetworkParameters, address: String): Boolean {
        return try {
            Address.fromString(networkParameters, address)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun generateP2PKHAddress(publicKey: PublicKey, networkParameters: NetworkParameters): String {
        val compressedBytes = (publicKey as BCECPublicKey).q.getEncoded(true)
        val rmd1 = sha256Hash160(compressedBytes)
        val rmd1WithVersion = byteArrayOf(networkParameters.addressHeader.toByte()) + rmd1
        return Base58.encode(addChecksum(rmd1WithVersion))
    }

    fun sha256Hash160(input: ByteArray): ByteArray {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val rmd = MessageDigest.getInstance("RipeMD160")
        return rmd.digest(sha256.digest(input))
    }

    fun addChecksum(input: ByteArray): ByteArray {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val checksum = sha256.digest(sha256.digest(input)).slice(IntRange(0, 3))
        return input + checksum
    }
}

fun BigInteger.toByteArrayNoSign(): ByteArray {
    val byteArray = this.toByteArray()
    return if (byteArray[0].compareTo(0) == 0) {
        byteArray.slice(IntRange(1, byteArray.size - 1)).toByteArray()
    } else {
        byteArray
    }
}

fun BigInteger.fromSatoshi(): BigDecimal {
    return BigDecimal(this).setScale(8) / BigDecimal("1e8")
}

fun BigInteger.inSatsAsDecimalString(): String {
    return this.fromSatoshi().toPlainString()
}

fun BigDecimal.toSatoshi(): BigInteger {
    return (this * BigDecimal("1e8")).toBigInteger()
}
