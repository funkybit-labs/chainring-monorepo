package co.chainring.core.evm

import co.chainring.core.model.Address
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.db.ChainId
import co.chainring.core.utils.toHex
import io.github.oshai.kotlinlogging.KotlinLogging
import org.web3j.crypto.Credentials
import org.web3j.crypto.ECDSASignature
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import org.web3j.crypto.StructuredDataEncoder
import java.math.BigInteger

object EIP712Helper {
    val logger = KotlinLogging.logger {}

    fun computeHash(tx: EIP712Transaction, chainId: ChainId, verifyingContract: Address): ByteArray {
        //
        // Structured data contains both the schema and the data, The type section holds the schema. The field ordering
        // is important since that is the order in which the data in these fields is appended before being hashed
        // for signing.
        //
        val encoder = StructuredDataEncoder(
            """
                {
                  "types": {
                    "EIP712Domain": [
                      {"name": "name", "type": "string"},
                      {"name": "version", "type": "string"},
                      {"name": "chainId", "type": "uint256"},
                      {"name": "verifyingContract", "type": "address"}
                    ],
                    "${tx.getTransactionType().name}": ${tx.getModel()}
                  },
                  "primaryType": "${tx.getTransactionType().name}",
                  "domain": {
                    "name": "ChainRing Labs",
                    "version": "0.0.1",
                    "chainId": ${chainId.value},
                    "verifyingContract": "${verifyingContract.value}"
                  },
                  "message": ${tx.getMessage()}
                }
            """.trimIndent(),
        )
        return encoder.hashStructuredData()
    }

    fun signData(credentials: Credentials, hash: ByteArray): EvmSignature {
        val signature = Sign.signMessage(hash, credentials.ecKeyPair, false)
        return EvmSignature((signature.r + signature.s + signature.v).toHex())
    }

    fun isValidSignature(hash: ByteArray, signature: EvmSignature, signerAddress: Address): Boolean {
        val (r, s) = decodeSignature(signature)
        val ecdsaSig = ECDSASignature(r, s).toCanonicalised()
        return setOf(
            Keys.toChecksumAddress(Keys.getAddress(Sign.recoverFromSignature(0, ecdsaSig, hash))),
            Keys.toChecksumAddress(Keys.getAddress(Sign.recoverFromSignature(1, ecdsaSig, hash))),
        ).contains(Keys.toChecksumAddress(signerAddress.value))
    }

    private fun decodeSignature(signature: EvmSignature): Pair<BigInteger, BigInteger> {
        val bytes = signature.toByteArray()
        return Pair(
            BigInteger(1, bytes.slice(0..31).toByteArray()),
            BigInteger(1, bytes.slice(32..63).toByteArray()),
        )
    }
}
