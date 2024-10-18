package xyz.funkybit.core.utils.bitcoin

import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Transaction
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.utils.bitcoin.BitcoinSignatureUtils.generateMessageHashSegWit
import xyz.funkybit.core.utils.schnorr.Schnorr
import xyz.funkybit.core.utils.signPrehashedMessage
import java.nio.ByteBuffer

object BitcoinSignatureGeneration {

    fun signMessage(address: BitcoinAddress, message: ByteArray, privateKey: ECKey, sigHash: Transaction.SigHash = Transaction.SigHash.UNSET): ByteArray {
        // Segwit P2WPKH and Taproot P2TR use BIP322 signing process
        return when (address) {
            is BitcoinAddress.SegWit -> {
                val msgHash = generateMessageHashSegWit(address, message, privateKey.publicKeyAsHex)
                val signature = signPrehashedMessage(privateKey.privKey, msgHash)
                val pubKey = privateKey.pubKey
                val buffer = ByteBuffer.allocate(signature.size + pubKey.size + 4)
                buffer.put(2)
                buffer.put((signature.size + 1).toByte())
                buffer.put(signature)
                buffer.put(1)
                buffer.put(pubKey.size.toByte())
                buffer.put(pubKey)
                buffer.array()
            }

            is BitcoinAddress.Taproot -> {
                val msgHash = BitcoinSignatureUtils.generateMessageHashTaproot(address, message, sigHash)
                val signature = Schnorr.sign(msgHash, TaprootUtils.tweakSeckey(privateKey.privKeyBytes))
                val sigSize = if (sigHash != Transaction.SigHash.UNSET) 65 else 64
                val buffer = ByteBuffer.allocate(2 + sigSize)
                buffer.put(1)
                buffer.put(sigSize.toByte())
                buffer.put(signature)
                if (sigSize == 65) {
                    buffer.put(sigHash.byteValue())
                }
                buffer.array()
            }

            else -> throw IllegalArgumentException("Only P2WPKH and P2TR addresses are supported")
        }
    }
}
