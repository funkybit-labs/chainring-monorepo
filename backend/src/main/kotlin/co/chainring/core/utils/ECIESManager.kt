package co.chainring.core.utils

import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.KDF2BytesGenerator
import org.bouncycastle.crypto.params.KDFParameters
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec
import org.bouncycastle.jce.spec.ECNamedCurveSpec
import org.bouncycastle.jce.spec.ECPublicKeySpec
import org.bouncycastle.math.ec.ECCurve
import org.bouncycastle.math.ec.ECPoint
import java.security.Key
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECFieldF2m
import java.security.spec.ECFieldFp
import java.security.spec.ECParameterSpec
import java.security.spec.EllipticCurve
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object ECIESManager {

    //region variables
    private const val CIPHER_INSTANCE_TYPE = "AES/GCM/NoPadding"
    private const val SECP_256_R1 = "secp256r1"
    private const val ECDH = "ECDH"
    private const val EC = "EC"
    private const val AES = "AES"

    private const val KDF_BYTE_LENGTH = 32
    private const val PUBLIC_KEY_INDEX = 65
    private const val IV_SIZE = 16
    private const val AES_IV_INDEX = 16

    private val bcProvider = BouncyCastleProvider()
    //endregion

    //region public methods
    fun encryptMessage(dataToEncrypt: ByteArray, publicKeyBytes: ByteArray): ByteArray {
        val publicKey = getPublicKeyFromBytes(publicKeyBytes)

        val ephemeralKeyPair = createECKeyPair()
        val ephemeralPublicKeyBytes = extractUncompressedPublicKey(ephemeralKeyPair.public.encoded)

        val sharedSecret = createSharedSecret(
            privateKey = ephemeralKeyPair.private,
            publicKey = publicKey,
        )

        val kdfResult = generateKDFBytes(
            sharedSecret = sharedSecret,
            publicKeyBytes = ephemeralPublicKeyBytes,
        )

        val cipher: Cipher = Cipher.getInstance(CIPHER_INSTANCE_TYPE)
        cipher.init(Cipher.ENCRYPT_MODE, kdfResult.aesKey, kdfResult.ivParameterSpec)

        val cipherResult = cipher.doFinal(dataToEncrypt)

        return ephemeralPublicKeyBytes + cipherResult
    }

    fun decryptMessage(cipherData: ByteArray, privateKey: PrivateKey): ByteArray {
        // Public key is first 65 bytes
        val ephemeralPublicKeyBytes = cipherData.slice(0 until PUBLIC_KEY_INDEX).toByteArray()

        // Encrypted data is the rest of the data
        val encryptedData = cipherData.slice(PUBLIC_KEY_INDEX until cipherData.size).toByteArray()

        val ephemeralPublicKey = getPublicKeyFromBytes(ephemeralPublicKeyBytes)

        val sharedSecret = createSharedSecret(
            privateKey = privateKey,
            publicKey = ephemeralPublicKey,
        )

        val kdfResult = generateKDFBytes(
            sharedSecret = sharedSecret,
            publicKeyBytes = ephemeralPublicKeyBytes,
        )

        val cipher = Cipher.getInstance(CIPHER_INSTANCE_TYPE)
        cipher.init(Cipher.DECRYPT_MODE, kdfResult.aesKey, kdfResult.ivParameterSpec)
        return cipher.doFinal(encryptedData)
    }
    //endregion

    //region private helper methods
    private fun extractUncompressedPublicKey(uncompressedPublicKey: ByteArray): ByteArray {
        val sequence: ASN1Sequence = DERSequence.getInstance(uncompressedPublicKey)
        val subjectPublicKey: DERBitString = sequence.getObjectAt(1) as DERBitString
        return subjectPublicKey.bytes
    }

    private fun createECKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance(EC, bcProvider)
        val secp256r1 = ECNamedCurveTable.getParameterSpec(SECP_256_R1)
        kpg.initialize(secp256r1)

        return kpg.generateKeyPair()
    }

    private fun createSharedSecret(privateKey: PrivateKey, publicKey: Key): ByteArray {
        val keyAgreement = KeyAgreement.getInstance(ECDH, bcProvider)
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(publicKey, true)

        return keyAgreement.generateSecret()
    }

    private fun generateKDFBytes(
        sharedSecret: ByteArray,
        publicKeyBytes: ByteArray,
    ): KDFResult {
        val aesKeyBytes = ByteArray(KDF_BYTE_LENGTH)
        val kdf = KDF2BytesGenerator(SHA256Digest())
        kdf.init(KDFParameters(sharedSecret, publicKeyBytes))
        kdf.generateBytes(aesKeyBytes, 0, KDF_BYTE_LENGTH)

        val iv = aesKeyBytes.slice(AES_IV_INDEX until aesKeyBytes.size).toByteArray()
        val aesKey = SecretKeySpec(aesKeyBytes.slice(0 until AES_IV_INDEX).toByteArray(), AES)

        val ivParameterSpec = GCMParameterSpec(IV_SIZE * Byte.SIZE_BITS, iv)

        return KDFResult(
            aesKey = aesKey,
            ivParameterSpec = ivParameterSpec,
        )
    }

    private fun getPublicKeyFromBytes(pubKey: ByteArray): PublicKey {
        val spec = ECNamedCurveTable.getParameterSpec(SECP_256_R1)
        val kf = KeyFactory.getInstance(EC, bcProvider)
        val params: ECParameterSpec = ECNamedCurveSpec(SECP_256_R1, spec.curve, spec.g, spec.n)
        val bouncyParams: org.bouncycastle.jce.spec.ECParameterSpec =
            ECNamedCurveParameterSpec(SECP_256_R1, spec.curve, spec.g, spec.n)
        val securityPoint: ECPoint = createPoint(params.curve, pubKey)
        val pubKeySpec = ECPublicKeySpec(securityPoint, bouncyParams)
        return kf.generatePublic(pubKeySpec)
    }

    private fun createPoint(
        curve: EllipticCurve,
        encoded: ByteArray?,
    ): ECPoint {
        val c: ECCurve = if (curve.field is ECFieldFp) {
            ECCurve.Fp(
                (curve.field as ECFieldFp).p,
                curve.a,
                curve.b,
                null,
                null,
            )
        } else {
            val k = (curve.field as ECFieldF2m).midTermsOfReductionPolynomial
            if (k.size == 3) {
                ECCurve.F2m(
                    (curve.field as ECFieldF2m).m,
                    k[2],
                    k[1],
                    k[0],
                    curve.a,
                    curve.b,
                    null,
                    null,
                )
            } else {
                ECCurve.F2m(
                    (curve.field as ECFieldF2m).m,
                    k[0],
                    curve.a,
                    curve.b,
                    null,
                    null,
                )
            }
        }
        return c.decodePoint(encoded)
    }
    //endregion
}

data class KDFResult(
    val aesKey: SecretKeySpec,
    val ivParameterSpec: GCMParameterSpec,
)
