package co.chainring.core.evm

import co.chainring.apps.api.middleware.SignInMessage
import co.chainring.core.model.Address
import co.chainring.core.model.db.ChainId
import io.github.oshai.kotlinlogging.KotlinLogging
import org.web3j.crypto.StructuredData
import org.web3j.crypto.StructuredDataEncoder

object EIP712Helper {
    val logger = KotlinLogging.logger {}

    fun computeHash(tx: EIP712Transaction, chainId: ChainId, verifyingContract: Address): ByteArray {
        //
        // Structured data contains both the schema and the data, the 'types' section holds the schema. The field ordering
        // in type definitions is important since that is the order in which the data in these fields is appended
        // before being hashed for signing.
        //
        val encoder = StructuredDataEncoder(
            StructuredData.EIP712Message(
                /* types = */
                hashMapOf(
                    "EIP712Domain" to listOf(
                        StructuredData.Entry("name", "string"),
                        StructuredData.Entry("version", "string"),
                        StructuredData.Entry("chainId", "uint256"),
                        StructuredData.Entry("verifyingContract", "address"),
                    ),
                    tx.getTransactionType().name to tx.getModel(),
                ),
                /* primaryType = */
                tx.getTransactionType().name,
                /* message = */
                tx.getMessage(),
                StructuredData.EIP712Domain(
                    /* name = */
                    "ChainRing Labs",
                    /* version = */
                    "0.0.1",
                    /* chainId = */
                    chainId.value.toString(),
                    /* verifyingContract = */
                    verifyingContract.value,
                    /* salt = */
                    null,
                ),
            ),
        )
        return encoder.hashStructuredData()
    }

    fun computeHash(signInMessage: SignInMessage): ByteArray {
        val encoder = StructuredDataEncoder(
            StructuredData.EIP712Message(
                /* types = */
                hashMapOf(
                    "EIP712Domain" to listOf(
                        StructuredData.Entry("name", "string"),
                        StructuredData.Entry("chainId", "uint32"),
                    ),
                    "Sign In" to listOf(
                        StructuredData.Entry("message", "string"),
                        StructuredData.Entry("address", "string"),
                        StructuredData.Entry("chainId", "uint32"),
                        StructuredData.Entry("timestamp", "string"),
                    ),
                ),
                /* primaryType = */
                "Sign In",
                /* message = */
                mapOf(
                    "message" to signInMessage.message,
                    "address" to signInMessage.address.value,
                    "chainId" to signInMessage.chainId.value,
                    "timestamp" to signInMessage.timestamp,
                ),
                /* domain = */
                StructuredData.EIP712Domain(
                    /* name = */
                    "ChainRing Labs",
                    /* version = */
                    null,
                    /* chainId = */
                    signInMessage.chainId.toString(),
                    /* verifyingContract = */
                    null,
                    /* salt = */
                    null,
                ),
            ),
        )

        return encoder.hashStructuredData()
    }
}
