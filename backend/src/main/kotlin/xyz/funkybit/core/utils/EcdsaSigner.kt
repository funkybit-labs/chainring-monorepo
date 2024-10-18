package xyz.funkybit.core.utils

import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.jce.ECNamedCurveTable
import java.math.BigInteger

fun signPrehashedMessage(privateKey: BigInteger, preHashedMessage: ByteArray): ByteArray {
    // Get the parameters for the secp256k1 curve (used in Bitcoin)
    val ecSpec = ECNamedCurveTable.getParameterSpec("secp256k1")
    val domainParams = ECDomainParameters(ecSpec.curve, ecSpec.g, ecSpec.n, ecSpec.h)

    // Create the EC private key parameters
    val privKey = ECPrivateKeyParameters(privateKey, domainParams)

    // Create an ECDSA signer
    val signer = ECDSASigner()
    signer.init(true, privKey)

    // Sign the pre-hashed message
    val signature = signer.generateSignature(preHashedMessage)

    // The signature consists of two components: r and s
    val r = signature[0]
    val s = signature[1]

    // Encode the signature in DER format
    val derSignature = DERSequence(arrayOf(ASN1Integer(r), ASN1Integer(s)))

    return derSignature.encoded
}

// Helper function to add the hash type to the signature
fun addHashType(signature: ByteArray, hashType: Byte): ByteArray {
    return signature + hashType
}
