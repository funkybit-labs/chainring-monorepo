package co.chainring.core.evm

import co.chainring.core.model.Address
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.toEvmSignature
import co.chainring.core.utils.toHex
import io.github.oshai.kotlinlogging.KotlinLogging
import org.web3j.crypto.Credentials
import org.web3j.crypto.ECDSASignature
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import java.math.BigInteger

object ECHelper {
    val logger = KotlinLogging.logger {}

    fun tradeHash(buyOrderId: Long, sellOrderId: Long): String {
        return sha3((buyOrderId.toString() + sellOrderId.toString()).toByteArray()).toHex()
    }

    fun sha3(messageBytes: ByteArray): ByteArray {
        return Hash.sha3(messageBytes)
    }

    fun signData(credentials: Credentials, hash: ByteArray): EvmSignature {
        val signature = Sign.signMessage(hash, credentials.ecKeyPair, false)
        return (signature.r + signature.s + signature.v).toHex().toEvmSignature()
    }

    fun isValidSignature(messageHash: ByteArray, signature: EvmSignature, signerAddress: Address): Boolean {
        val (r, s, v) = decodeSignature(signature)
        val ecdsaSig = ECDSASignature(r, s).toCanonicalised()

        val recoveredAddress = Keys.toChecksumAddress(Keys.getAddress(Sign.recoverFromSignature(v.toInt() - 27, ecdsaSig, messageHash)))
        return recoveredAddress == Keys.toChecksumAddress(signerAddress.value)
    }

    private fun decodeSignature(signature: EvmSignature): Triple<BigInteger, BigInteger, BigInteger> {
        val bytes = signature.toByteArray()
        return Triple(
            BigInteger(1, bytes.slice(0..31).toByteArray()),
            BigInteger(1, bytes.slice(32..63).toByteArray()),
            BigInteger(1, bytes.slice(64..64).toByteArray()),
        )
    }
}
