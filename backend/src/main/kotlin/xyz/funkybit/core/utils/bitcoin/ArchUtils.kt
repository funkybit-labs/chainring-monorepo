package xyz.funkybit.core.utils.bitcoin

import org.bitcoinj.core.Coin
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionOutPoint
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.script.ScriptOpCodes
import xyz.funkybit.core.blockchain.bitcoin.BitcoinClient
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.db.UnspentUtxo
import java.math.BigDecimal
import java.math.BigInteger

object ArchUtils {

    private val zeroCoinValue = Coin.valueOf(0)

    fun buildOnboardingTx(
        ecKey: ECKey,
        archNetworkAddress: BitcoinAddress,
        authority: ByteArray,
        amount: BigInteger,
        changeAddress: BitcoinAddress,
        feeAmount: BigInteger,
        utxos: List<UnspentUtxo>,
    ): Transaction {
        val params = BitcoinClient.getParams()
        val rawTx = Transaction(params)
        rawTx.setVersion(2)
        rawTx.addOutput(
            zeroCoinValue,
            ScriptBuilder()
                .op(ScriptOpCodes.OP_RETURN)
                .data(authority).build(),
        )
        rawTx.addOutput(
            TransactionOutput(
                params,
                rawTx,
                Coin.valueOf(amount.toLong()),
                archNetworkAddress.toBitcoinCoreAddress(params),
            ),
        )
        val changeAmount = BigInteger.ZERO.max(utxos.sumOf { it.amount } - amount - feeAmount)
        if (changeAmount > BitcoinClient.bitcoinConfig.changeDustThreshold) {
            rawTx.addOutput(
                TransactionOutput(
                    params,
                    rawTx,
                    Coin.valueOf(changeAmount.toLong()),
                    changeAddress.toBitcoinCoreAddress(params),
                ),
            )
        }
        utxos.forEach {
            rawTx.addSignedInput(
                TransactionOutPoint(
                    params,
                    it.utxoId.vout(),
                    Sha256Hash.wrap(it.utxoId.txId().value),
                ),
                ScriptBuilder.createP2WPKHOutputScript(ecKey),
                Coin.valueOf(it.amount.toLong()),
                ecKey,
                Transaction.SigHash.NONE,
                true,
            )
        }
        return rawTx
    }

    fun estimateOnboardingTxFee(
        ecKey: ECKey,
        archNetworkAddress: BitcoinAddress,
        changeAddress: BitcoinAddress,
        utxos: List<UnspentUtxo>,
    ): BigInteger {
        val params = BitcoinClient.getParams()
        val rawTx = Transaction(params)
        rawTx.setVersion(2)
        rawTx.addOutput(
            zeroCoinValue,
            ScriptBuilder()
                .op(ScriptOpCodes.OP_RETURN)
                .data(ByteArray(32)).build(),
        )
        rawTx.addOutput(
            TransactionOutput(
                params,
                rawTx,
                zeroCoinValue,
                archNetworkAddress.toBitcoinCoreAddress(params),
            ),
        )
        rawTx.addOutput(
            TransactionOutput(
                params,
                rawTx,
                zeroCoinValue,
                changeAddress.toBitcoinCoreAddress(params),
            ),
        )
        utxos.forEach {
            rawTx.addSignedInput(
                TransactionOutPoint(
                    params,
                    it.utxoId.vout(),
                    Sha256Hash.wrap(it.utxoId.txId().value),
                ),
                ScriptBuilder.createP2WPKHOutputScript(ecKey),
                Coin.valueOf(it.amount.toLong()),
                ecKey,
                Transaction.SigHash.NONE,
                true,
            )
        }
        return calculateFee(rawTx.vsize)
    }

    fun calculateFee(vsize: Int) =
        BitcoinClient.estimateSmartFeeInSatPerVByte().toBigInteger() * vsize.toBigInteger()
}

fun BigInteger.fromSatoshi(): BigDecimal {
    return BigDecimal(this).setScale(8) / BigDecimal("1e8")
}

fun BigInteger.inSatsAsDecimalString(): String {
    return this.fromSatoshi().toPlainString()
}
