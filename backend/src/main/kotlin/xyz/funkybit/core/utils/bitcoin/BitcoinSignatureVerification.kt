package xyz.funkybit.core.utils.bitcoin

import io.github.oshai.kotlinlogging.KotlinLogging
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.LegacyAddress
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.Utils
import org.bitcoinj.script.ScriptBuilder
import xyz.funkybit.core.blockchain.bitcoin.bitcoinConfig
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.utils.bitcoin.BitcoinSignatureUtils.generateMessageHashSegWit
import xyz.funkybit.core.utils.bitcoin.BitcoinSignatureUtils.generateMessageHashTaproot
import xyz.funkybit.core.utils.bitcoin.BitcoinSignatureUtils.getVarInt
import xyz.funkybit.core.utils.doubleSha256
import xyz.funkybit.core.utils.schnorr.Schnorr
import xyz.funkybit.core.utils.toHex
import xyz.funkybit.core.utils.toHexBytes
import java.math.BigInteger

object BitcoinSignatureVerification {
    private val logger = KotlinLogging.logger {}

    private val params = bitcoinConfig.params

    @OptIn(ExperimentalStdlibApi::class)
    fun verifyMessage(address: BitcoinAddress, signature: String, message: String): Boolean {
        logger.debug { "verifyMessage($address, $signature, $message)" }
        val signatureHex = java.util.Base64.getDecoder().decode(signature).toHex(false)
        if (signatureHex.length == 130) {
            // note: ECKey.signedMessageToKey(message, signature) follows the same public key recovery process,
            // but has stricter signature's header byte verification (header < 27 || header > 34) and therefore fails P2SH

            val prefix = "\u0018Bitcoin Signed Message:\n".toByteArray(Charsets.UTF_8)
            val msgHex = message.toByteArray(Charsets.UTF_8)
            val len = getVarInt(msgHex.size.toLong())
            val msgHash = doubleSha256(prefix + len.hexToByteArray() + msgHex)

            val header = signatureHex.substring(0, 2).hexToInt()

            val (recId, compressed) = when {
                header > 42 -> throw Exception("Header byte too high: $header")
                header < 27 -> throw Exception("Header byte too low: $header")
                header > 38 -> header - 39 to true
                header > 34 -> header - 35 to true
                header > 30 -> header - 31 to true
                else -> header - 27 to false
            }

            val (r, s) = Pair(
                BigInteger(1, signatureHex.substring(2, 66).hexToByteArray()),
                BigInteger(1, signatureHex.substring(66, 130).hexToByteArray()),
            )

            val recoveredPubkey = ECKey.recoverFromSignature(recId, ECKey.ECDSASignature(r, s), Sha256Hash.wrap(msgHash), compressed)
            val recoveredAddress = BitcoinAddress.fromKey(params, recoveredPubkey!!)
            logger.debug { "Recovered pubkey $recoveredPubkey from ECDSA signature, corresponding address $recoveredAddress" }

            when (address) {
                is BitcoinAddress.P2PKH -> {
                    // comparing a hash of the recovered from the signature public key
                    val p2PKHAddress = LegacyAddress.fromPubKeyHash(params, recoveredPubkey.pubKeyHash)

                    return p2PKHAddress.toBase58() == address.value
                }

                is BitcoinAddress.P2SH -> {
                    // check P2SH (e.g., multisig)
                    val p2shRedeemScript = ScriptBuilder.createMultiSigOutputScript(1, listOf(recoveredPubkey))
                    var p2shScriptHash = Utils.sha256hash160(p2shRedeemScript.program)
                    var p2shpAddress = LegacyAddress.fromScriptHash(params, p2shScriptHash)

                    if (p2shpAddress.toBase58() == address.value) return true

                    // check P2SH-P2WPKH
                    val p2shp2wpkhScriptPubKey =
                        ScriptBuilder.createP2WPKHOutputScript(Utils.sha256hash160(recoveredPubkey.pubKey))
                    val p2shp2wpkhScriptHash = Utils.sha256hash160(p2shp2wpkhScriptPubKey.program)
                    val p2shp2wpkhAddress = LegacyAddress.fromScriptHash(params, p2shp2wpkhScriptHash)

                    return p2shp2wpkhAddress.toBase58() == address.value
                }

                else -> {
                    return recoveredAddress == address
                }
            }
        } else {
            // Segwit P2WPKH and Taproot P2TR use BIP322 signing process
            return when (address) {
                is BitcoinAddress.SegWit -> {
                    val sigLen = signatureHex.substring(2, 4).toInt(16) * 2
                    val sig = signatureHex.substring(4, 4 + sigLen - 2)
                    val pubkey = signatureHex.substring(6 + sigLen)
                    val msgHash = generateMessageHashSegWit(address, message.toByteArray(), pubkey)
                    ECKey.verify(msgHash, sig.hexToByteArray(), pubkey.hexToByteArray())
                }

                is BitcoinAddress.Taproot -> {
                    val script = address.script()
                    val sigBytes = signatureHex.toHexBytes()
                    val sigLength = sigBytes[1].toInt()
                    val sigHash = if (sigLength == 65) {
                        Transaction.SigHash.entries.first { it.value == sigBytes[66].toInt() }
                    } else {
                        Transaction.SigHash.UNSET
                    }
                    val sig = sigBytes.slice(2..65).toByteArray()

                    val pubkey = script.slice(4..script.lastIndex).hexToByteArray()
                    val msgHash = generateMessageHashTaproot(address, message.toByteArray(), sigHash)
                    Schnorr.verify(msgHash, pubkey, sig)
                }

                else -> throw IllegalArgumentException("Only P2WPKH and P2TR addresses are supported")
            }
        }
    }
}
