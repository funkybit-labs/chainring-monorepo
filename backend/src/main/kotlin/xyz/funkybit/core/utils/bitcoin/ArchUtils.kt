package xyz.funkybit.core.utils.bitcoin

import com.funkatronics.kborsh.Borsh
import kotlinx.serialization.encodeToByteArray
import org.bitcoinj.core.Coin
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionInput
import org.bitcoinj.core.TransactionOutPoint
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.core.TransactionWitness
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.script.ScriptOpCodes
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.blockchain.bitcoin.ArchNetworkClient
import xyz.funkybit.core.blockchain.bitcoin.BitcoinClient
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.ExchangeInstruction
import xyz.funkybit.core.model.UtxoId
import xyz.funkybit.core.model.db.ArchStateUtxoEntity
import xyz.funkybit.core.model.db.TxHash
import xyz.funkybit.core.model.db.UnspentUtxo
import xyz.funkybit.core.model.rpc.ArchNetworkRpc
import xyz.funkybit.core.utils.schnorr.Schnorr
import xyz.funkybit.core.utils.toHexBytes
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

    fun buildFeeTx(
        ecKey: ECKey,
        changeAddress: BitcoinAddress,
        feeAmount: BigInteger,
        utxos: List<UnspentUtxo>,
    ): Transaction {
        val params = BitcoinClient.getParams()
        val rawTx = Transaction(params)
        rawTx.setVersion(2)
        val changeAmount = BigInteger.ZERO.max(utxos.sumOf { it.amount } - feeAmount)
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
        utxos.forEachIndexed { index, utxo ->
            val input = TransactionInput(
                params,
                rawTx,
                ScriptBuilder.createEmpty().program,
                TransactionOutPoint(
                    params,
                    utxo.utxoId.vout(),
                    Sha256Hash.wrap(utxo.utxoId.txId().value),
                ),
                Coin.valueOf(utxo.amount.toLong()),
            )
            rawTx.addInput(input)
            val signature = rawTx.calculateWitnessSignature(
                index,
                ecKey,
                ScriptBuilder.createP2PKHOutputScript(ecKey),
                input.value,
                Transaction.SigHash.NONE,
                true,
            )
            input.witness = TransactionWitness.redeemP2WPKH(signature, ecKey)
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

    @OptIn(ExperimentalUnsignedTypes::class)
    fun signAndSendInstruction(programId: Address, utxoIds: List<UtxoId>, exchangeInstruction: ExchangeInstruction): TxHash {
        val instruction = ArchNetworkRpc.Instruction(
            programId = ArchNetworkRpc.Pubkey(programId.toString().toHexBytes().toUByteArray()),
            utxos = utxoIds.map {
                ArchNetworkRpc.UtxoMeta(it.txId(), it.vout().toInt())
            },
            data = Borsh.encodeToByteArray(exchangeInstruction).toUByteArray(),
        )

        val message = ArchNetworkRpc.Message(
            signers = listOf(
                ArchNetworkRpc.Pubkey(BitcoinClient.bitcoinConfig.submitterXOnlyPublicKey.toUByteArray()),
            ),
            instructions = listOf(instruction),
        )

        val signature = Schnorr.sign(message.hash(), BitcoinClient.bitcoinConfig.privateKey)

        val runtimeTransaction = ArchNetworkRpc.RuntimeTransaction(
            version = 0,
            signatures = listOf(
                ArchNetworkRpc.Signature(signature.toUByteArray()),
            ),
            message = message,
        )

        return ArchNetworkClient.sendTransaction(runtimeTransaction)
    }

    fun completeTransaction(processedTx: ArchNetworkRpc.ProcessedTransaction, onCompletion: () -> Unit) {
        val bitcoinTxId = processedTx.bitcoinTxIds.values.first()
        val inputUtxoIds = processedTx.runtimeTransaction.message.instructions.first().utxos.map { it.toUtxoId() }.toSet()

        transaction {
            // update any modified state utxos
            val bitcoinTx = BitcoinClient.getRawTransaction(bitcoinTxId)
            bitcoinTx.txIns.forEachIndexed { index, txIn ->
                val utxoId = txIn.toUtxoId()
                if (inputUtxoIds.contains(utxoId)) {
                    ArchStateUtxoEntity.findByUtxoId(txIn.toUtxoId())
                        ?.updateUtxoId(bitcoinTx.txOuts.first { it.index == index }.toUtxoId(bitcoinTxId))
                }
            }
            onCompletion()
        }
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
