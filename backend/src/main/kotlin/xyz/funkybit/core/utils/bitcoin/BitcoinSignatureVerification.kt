package xyz.funkybit.core.utils.bitcoin

import io.github.oshai.kotlinlogging.KotlinLogging
import org.bitcoinj.core.Base58
import org.bitcoinj.core.Coin
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.LegacyAddress
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionInput
import org.bitcoinj.core.TransactionOutPoint
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.core.Utils
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.script.ScriptOpCodes
import xyz.funkybit.core.blockchain.bitcoin.BitcoinClient.getParams
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.utils.doubleSha256
import xyz.funkybit.core.utils.schnorr.Schnorr
import xyz.funkybit.core.utils.sha256
import xyz.funkybit.core.utils.toHex
import xyz.funkybit.core.utils.toHexBytes
import java.math.BigInteger

object BitcoinSignatureVerification {
    private val logger = KotlinLogging.logger {}

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
            val recoveredAddress = BitcoinAddress.fromKey(getParams(), recoveredPubkey!!)
            logger.debug { "Recovered pubkey $recoveredPubkey from ECDSA signature, corresponding address $recoveredAddress" }

            when (address) {
                is BitcoinAddress.P2PKH -> {
                    // transaction is pay-2-public-key-hash, therefore comparing a hash of the recovered from the signature public key
                    val p2PKHAddress = LegacyAddress.fromPubKeyHash(getParams(), recoveredPubkey.pubKeyHash)

                    return p2PKHAddress.toBase58() == address.value
                }

                is BitcoinAddress.P2SH -> {
                    // create the 1 of 1 scriptSig: OP_HASH160 <20-byte hash160(pubkey)> OP_EQUAL
                    val recoveredPubKeyHash160 = Utils.sha256hash160(recoveredPubkey.pubKey)
                    val scriptSig = ScriptBuilder.createP2WPKHOutputScript(recoveredPubKeyHash160)

                    // create the P2SH address
                    val scriptHash = Utils.sha256hash160(scriptSig.program)
                    val p2shAddress = LegacyAddress.fromScriptHash(getParams(), scriptHash)

                    return p2shAddress.toBase58() == address.value
                }

                else -> {
                    return recoveredAddress == address
                }
            }
        } else {
            // Segwit P2WPKH and Taproot P2TR use BIP322 signing process
            val script = address.script()
            val txToSign = getVirtualTx(message, script.toHexBytes())
            val zero4 = ByteArray(4)
            val zero8 = ByteArray(8)
            val firstOp = script.toHexBytes()[0].toInt()
            return when (firstOp) {
                ScriptOpCodes.OP_0 -> {
                    val sigLen = signatureHex.substring(2, 4).toInt(16) * 2
                    val sig = signatureHex.substring(4, 4 + sigLen - 2)
                    val pubkey = signatureHex.substring(6 + sigLen)
                    val scriptCode = generateSingleSigScript(pubkey, address)
                    // witness msg prefix for txSign:
                    // ...versionByte -- 4 byte - 0
                    // ...prevHash - sha256(sha256(reversed(txToSend.txHash) + 4 bytes(0))
                    // ...sequenceHash - sha256(sha256(4 bytes(0)))
                    val witnessMsgPrefix =
                        zero4 +
                            doubleSha256(txToSign.inputs[0].outpoint.hash.reversedBytes + zero4) +
                            doubleSha256(zero4)
                    // witness msg suffix for txSign:
                    // ...outputHash - sha256(sha256(8 bytes(0) + txToSign.scriptPubKey))
                    // ...lockTimeByte -- 4 bytes = 0
                    val outputScript = txToSign.outputs[0].scriptPubKey.program
                    val witnessMsgSuffix =
                        doubleSha256(zero8 + getVarInt(outputScript.size.toLong()).toHexBytes() + outputScript) +
                            zero4
                    val msgHash = doubleSha256(
                        witnessMsgPrefix +
                            // outpoint
                            txToSign.inputs[0].outpoint.hash.reversedBytes + zero4 +
                            // script code
                            getVarInt(scriptCode.size.toLong()).toHexBytes() + scriptCode +
                            // value
                            zero8 +
                            // sequence
                            zero4 +
                            witnessMsgSuffix +
                            // sig hash
                            "01000000".hexToByteArray(),
                    )
                    ECKey.verify(msgHash, sig.hexToByteArray(), pubkey.hexToByteArray())
                }

                ScriptOpCodes.OP_1 -> {
                    val sig = signatureHex.substring(4, 132)
                    val pubkey = script.slice(4..script.lastIndex).hexToByteArray()
                    val txToSend = txToSign.inputs[0]
                    val outputScript = txToSign.outputs[0].scriptPubKey.program
                    val sigMsg =
                        // hashType
                        ByteArray(1) +
                            // transaction
                            // version
                            zero4 +
                            // locktime
                            zero4 +
                            // prevoutHash
                            sha256(txToSign.inputs[0].outpoint.hash.reversedBytes + zero4) +
                            // amountHash
                            sha256(zero8) +
                            // scriptPubKeyHash
                            sha256(getVarInt(txToSend.scriptBytes.size.toLong()).toHexBytes() + txToSend.scriptBytes) +
                            // sequenceHash
                            sha256(zero4) +
                            // outputHash
                            sha256(zero8 + getVarInt(outputScript.size.toLong()).toHexBytes() + outputScript) +
                            // inputs
                            // spend type
                            ByteArray(1) +
                            // input idx
                            zero4
                    val msgHash = sha256(
                        getTapTag("TapSighash".toByteArray()) + byteArrayOf(0) + sigMsg,
                    )
                    Schnorr.verify(msgHash, pubkey, sig.hexToByteArray())
                }

                else -> throw IllegalArgumentException("Only P2WPKH and P2TR addresses are supported")
            }
        }
    }

    fun base58Check(addrType: Int, payload: ByteArray): String {
        val addressBytes = byteArrayOf(addrType.toByte()) + payload
        val checksum = doubleSha256(addressBytes).copyOfRange(0, 4)
        return Base58.encode(addressBytes + checksum)
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun generateSingleSigScript(pubkey: String, address: BitcoinAddress): ByteArray {
        return when (address) {
            is BitcoinAddress.Taproot -> {
                val pubkeyBytes = Utils.HEX.decode(pubkey)
                val script = ScriptBuilder()
                    .data(pubkeyBytes)
                    .op(ScriptOpCodes.OP_CHECKSIG)
                    .build()
                script.program
            }

            else -> {
                val pubkeyHash = Utils.sha256hash160(Utils.HEX.decode(pubkey))
                return (
                    ScriptOpCodes.OP_DUP.toString(16) +
                        ScriptOpCodes.OP_HASH160.toString(16) +
                        opPushData(pubkeyHash.toHexString()) +
                        ScriptOpCodes.OP_EQUALVERIFY.toString(16) +
                        ScriptOpCodes.OP_CHECKSIG.toString(16)
                    ).toHexBytes()
            }
        }
    }

    private fun getVarInt(num: Long): String {
        return when {
            num <= 252 -> padZeroHexN(num.toString(16), 2)
            num <= 65535 -> "fd" + reverseHex(padZeroHexN(num.toString(16), 4))
            num <= 4294967295 -> "fe" + reverseHex(padZeroHexN(num.toString(16), 8))
            else -> "ff" + reverseHex(padZeroHexN(num.toString(16), 16))
        }
    }

    fun padZeroHexN(hex: String, length: Int): String {
        return hex.padStart(length, '0')
    }

    private fun reverseHex(hex: String): String {
        return hex.chunked(2).reversed().joinToString("")
    }

    fun opPushData(data: String): String {
        val length = data.length / 2 // Hex string length is twice the byte length
        return when {
            length < 0x4c -> {
                // length byte only
                String.format("%02x", length)
            }

            length <= 0xff -> {
                // OP_PUSHDATA1 format
                String.format("%02x%02x", ScriptOpCodes.OP_PUSHDATA1, length)
            }

            length <= 0xffff -> {
                // OP_PUSHDATA2 format
                String.format("%02x%04x", ScriptOpCodes.OP_PUSHDATA2, length)
            }

            else -> {
                // OP_PUSHDATA4 format
                String.format("%02x%08x", ScriptOpCodes.OP_PUSHDATA4, length)
            }
        } + data
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun getVirtualTx(msg: String, script: ByteArray): Transaction {
        // Build transaction to spend
        val txToSpend = Transaction(getParams())
        txToSpend.setVersion(0)

        // Add input to txToSpend
        val dummyTxHash = Sha256Hash.ZERO_HASH
        val input = TransactionInput(
            getParams(),
            txToSpend,
            byteArrayOf(),
            TransactionOutPoint(getParams(), 0xffffffffL, dummyTxHash),
        )
        input.sequenceNumber = 0x00000000L // Sequence number

        // Add output to txToSpend
        val outputScript = Script(script)
        val output = TransactionOutput(getParams(), txToSpend, Coin.ZERO, outputScript.program)
        txToSpend.addOutput(output)

        // Build the message hash
        val messageBytes = msg.toByteArray()
        val bip0322Tag = "BIP0322-signed-message".toByteArray()
        val msgHash = sha256(
            getTapTag(bip0322Tag) + messageBytes,
        ).toHexString()

        // Sign the input
        val scriptSig = ("00" + opPushData(msgHash)).toHexBytes()
        input.scriptSig = Script(scriptSig)

        txToSpend.addInput(input)

        // Build transaction to sign
        val txToSign = Transaction(getParams())
        txToSign.setVersion(0)

        // Add input to txToSign
        val inputToSign = TransactionInput(
            getParams(),
            txToSign,
            script,
            TransactionOutPoint(getParams(), 0L, txToSpend.txId),
        )
        inputToSign.sequenceNumber = 0x00000000L

        txToSign.addInput(inputToSign)

        // Add OP_RETURN output to txToSign
        val opReturnScript = ScriptOpCodes.OP_RETURN
        val opReturnOutput = TransactionOutput(getParams(), txToSign, Coin.ZERO, byteArrayOf(opReturnScript.toByte()))
        txToSign.addOutput(opReturnOutput)

        return txToSign
    }

    private fun getTapTag(tag: ByteArray): ByteArray {
        val hashed = sha256(tag)
        return hashed + hashed
    }
}
