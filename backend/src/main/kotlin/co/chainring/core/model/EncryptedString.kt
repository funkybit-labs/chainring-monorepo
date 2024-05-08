
package co.chanring.core.model

import co.chainring.core.utils.ECIESManager
import co.chainring.core.utils.ECPublicKeyDecoder
import co.chainring.core.utils.generateRandomBytes
import co.chainring.core.utils.toHex
import co.chainring.core.utils.toHexBytes
import co.chainring.core.utils.uncompressedPublicKey
import kotlinx.serialization.Serializable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec

private val bcProvider = BouncyCastleProvider()
private val dataEncryptionKey = ECPublicKeyDecoder.fromHexEncodedString(System.getenv("DATA_ENCRYPTION_KEY") ?: "0x0443975c5a5cf6caaf3cc846dac466f1e81321f5dc36f97fc2528c68449381a828520946caf970fa3f7e508a56d902c2f4405aed01972f5e3bb382f84bff56b042")
private val dataDecryptionKey = run {
    val privateKeyBytes = (System.getenv("DATA_DECRYPTION_KEY") ?: "0x308193020100301306072a8648ce3d020106082a8648ce3d0301070479307702010104206d251224ebaeedfd87cca2093ed45c9d3516f7bb8d9a66567378455524944d58a00a06082a8648ce3d030107a1440342000443975c5a5cf6caaf3cc846dac466f1e81321f5dc36f97fc2528c68449381a828520946caf970fa3f7e508a56d902c2f4405aed01972f5e3bb382f84bff56b042").toHexBytes()
    KeyFactory.getInstance("EC", bcProvider).generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes)) as ECPrivateKey
}
val dataKeyId = System.getenv("DATA_KEY_ID") ?: "local-v1"

@Serializable
@JvmInline
value class EncryptedString(val encrypted: String) {
    fun decrypt(): String {
        val parts = encrypted.split('|', limit = 3)
        val salt = parts[1]
        val encryptedData = parts[2].toHexBytes()
        return ECIESManager.decryptMessage(encryptedData, dataDecryptionKey).decodeToString().removePrefix("$salt|")
    }
}

fun String.encrypt(): EncryptedString {
    val salt = generateRandomBytes(10).toHex(false)
    return EncryptedString(
        """$dataKeyId|$salt|${ECIESManager.encryptMessage("$salt|$this".encodeToByteArray(), dataEncryptionKey.uncompressedPublicKey()).toHex(false)}""",
    )
}
