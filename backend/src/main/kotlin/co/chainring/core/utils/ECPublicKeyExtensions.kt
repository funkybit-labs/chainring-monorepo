package co.chainring.core.utils

import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec

private val bcProvider = BouncyCastleProvider()
private const val CURVE_NAME = "secp256r1"

object ECPublicKeyDecoder {
    init {
        java.security.Security.addProvider(bcProvider)
    }

    fun fromHexEncodedString(hexKey: String): ECPublicKey {
        // create a public key using the provided hex string
        val bytes = hexKey.toHexBytes()
        val keyLength = 64
        val startingOffset = if (bytes.size == keyLength + 1 && bytes[0].compareTo(4) == 0) 1 else 0
        val x = bytes.slice(IntRange(startingOffset, 31 + startingOffset)).toByteArray()
        val y = bytes.slice(IntRange(startingOffset + 32, 63 + startingOffset)).toByteArray()

        val pubPoint = ECPoint(BigInteger(1, x), BigInteger(1, y))
        val params = AlgorithmParameters.getInstance("EC", bcProvider).apply {
            init(ECGenParameterSpec(CURVE_NAME))
        }
        val pubECSpec = ECPublicKeySpec(
            pubPoint,
            params.getParameterSpec(ECParameterSpec::class.java),
        )
        return KeyFactory.getInstance("EC", bcProvider)
            .generatePublic(pubECSpec) as ECPublicKey
    }
}

fun ECPublicKey.uncompressedPublicKey(): ByteArray = (this as BCECPublicKey).q.getEncoded(false)
