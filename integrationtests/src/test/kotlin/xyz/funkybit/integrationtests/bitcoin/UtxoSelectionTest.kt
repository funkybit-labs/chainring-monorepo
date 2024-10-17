package xyz.funkybit.integrationtests.bitcoin

import org.bitcoinj.core.Coin
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionOutPoint
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.script.ScriptBuilder
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import xyz.funkybit.core.blockchain.bitcoin.BitcoinClient
import xyz.funkybit.core.blockchain.bitcoin.MempoolSpaceClient
import xyz.funkybit.core.blockchain.bitcoin.bitcoinConfig
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.db.BitcoinUtxoEntity
import xyz.funkybit.core.model.db.BitcoinUtxoId
import xyz.funkybit.core.services.UtxoManager
import xyz.funkybit.core.utils.bitcoin.BitcoinInsufficientFundsException
import xyz.funkybit.core.utils.toHex
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.testutils.isBitcoinDisabled
import xyz.funkybit.integrationtests.testutils.waitForTx
import java.math.BigInteger
import kotlin.test.assertEquals

@ExtendWith(AppUnderTestRunner::class)
class UtxoSelectionTest {

    companion object {
        val params = bitcoinConfig.params

        fun validateUtxoAndMempoolBalances(address: BitcoinAddress, isProgramAddress: Boolean = false) {
            transaction {
                assertEquals(BitcoinUtxoEntity.findUnspentTotal(address).toLong(), MempoolSpaceClient.getBalance(address) - if (isProgramAddress) 1500 else 0)
            }
        }
    }

    @Test
    fun testUtxoSelection() {
        Assumptions.assumeFalse(isBitcoinDisabled())

        // create a wallet
        val ecKey = ECKey()
        val address = BitcoinAddress.fromKey(params, ecKey)

        // airdrop 3000 sats
        val txId = BitcoinClient.sendToAddress(address, BigInteger("3000"))

        waitForTx(address, txId)
        val expectedVout = MempoolSpaceClient.getTransaction(txId)!!.vouts.indexOfFirst { it.value == BigInteger("3000") }

        // check the expected Utxo is returned
        var selectedUtxos = transaction {
            UtxoManager.selectUtxos(address, BigInteger("1500"), BigInteger("1500"))
        }

        assertEquals(1, selectedUtxos.size)
        assertEquals(BitcoinUtxoId.fromTxHashAndVout(txId, expectedVout), selectedUtxos[0].guid.value)
        assertEquals(BigInteger("3000"), selectedUtxos[0].amount)

        // check it fails if we request more that available
        assertThrows<BitcoinInsufficientFundsException> {
            transaction {
                UtxoManager.selectUtxos(address, BigInteger("2000"), BigInteger("1500"))
            }
        }

        // airdrop some more
        val txId2 = BitcoinClient.sendToAddress(address, BigInteger("3100"))
        waitForTx(address, txId2)
        val expectedVout2 = MempoolSpaceClient.getTransaction(txId2)!!.vouts.indexOfFirst { it.value == BigInteger("3100") }
        selectedUtxos = transaction {
            UtxoManager.selectUtxos(address, BigInteger("3000"), BigInteger("1500"))
        }
        assertEquals(2, selectedUtxos.size)
        assertEquals(setOf(BitcoinUtxoId.fromTxHashAndVout(txId, expectedVout), BitcoinUtxoId.fromTxHashAndVout(txId2, expectedVout2)), selectedUtxos.map { it.guid.value }.toSet())
        assertEquals(BigInteger("6100"), selectedUtxos.sumOf { it.amount })

        val transferToAddress = BitcoinAddress.fromKey(params, ECKey())

        val transaction = buildTransferTx(ecKey, transferToAddress, BigInteger("3000"), address, BigInteger("1500"), selectedUtxos)
        val txId3 = MempoolSpaceClient.sendTransaction(transaction.toHexString())
        assertEquals(transaction.txId.bytes.toHex(false), txId3.value)

        waitForTx(transferToAddress, txId3, address)
        val tx = MempoolSpaceClient.getTransaction(txId3)!!
        val changeVout = tx.vouts.indexOfFirst { it.value == BigInteger("1600") }
        val transferVout = tx.vouts.indexOfFirst { it.value == BigInteger("3000") }
        val unspentUtxos = transaction {
            UtxoManager.getAllUnspent(address)
        }
        assertEquals(unspentUtxos.size, 1)
        assertEquals(BitcoinUtxoId.fromTxHashAndVout(txId3, changeVout), unspentUtxos[0].guid.value)
        assertEquals(BigInteger("1600"), unspentUtxos[0].amount)

        val transferToUnspentUtxos = transaction {
            UtxoManager.getAllUnspent(transferToAddress)
        }
        assertEquals(transferToUnspentUtxos.size, 1)
        assertEquals(BitcoinUtxoId.fromTxHashAndVout(txId3, transferVout), transferToUnspentUtxos[0].guid.value)
        assertEquals(BigInteger("3000"), transferToUnspentUtxos[0].amount)

        validateUtxoAndMempoolBalances(address)
    }

    @Test
    fun testMultipleUtxos() {
        Assumptions.assumeFalse(isBitcoinDisabled())

        // create a wallet
        val ecKey = ECKey()
        val address = BitcoinAddress.fromKey(params, ecKey)
        val numUtxos = 30

        val txIds = (1..numUtxos).map {
            Thread.sleep(10)
            BitcoinClient.sendToAddress(address, BigInteger("3000"))
        }

        txIds.forEach {
            waitForTx(address, it)
        }
        val initialUnspentUtxos = transaction {
            UtxoManager.getAllUnspent(address)
        }
        assertEquals(numUtxos, initialUnspentUtxos.size)

        // transfer all the UTXOs
        val transferAmount = BigInteger("3000") * (numUtxos - 2).toBigInteger()
        val feeAmount = BigInteger("5000")
        val selectedUtxos = transaction {
            UtxoManager.selectUtxos(address, transferAmount, feeAmount)
        }
        val transferToAddress = BitcoinAddress.fromKey(params, ECKey())

        val transaction = buildTransferTx(ecKey, transferToAddress, transferAmount, address, feeAmount, selectedUtxos)
        val txId3 = MempoolSpaceClient.sendTransaction(transaction.toHexString())
        waitForTx(transferToAddress, txId3, address)
        val tx = MempoolSpaceClient.getTransaction(txId3)!!
        val changeVout = tx.vouts.indexOfFirst { it.value == BigInteger("1000") }
        val transferVout = tx.vouts.indexOfFirst { it.value == transferAmount }

        val unspentUtxos = transaction {
            UtxoManager.getAllUnspent(address)
        }
        assertEquals(unspentUtxos.size, 1)
        assertEquals(BitcoinUtxoId.fromTxHashAndVout(txId3, changeVout), unspentUtxos[0].guid.value)
        assertEquals(BigInteger("1000"), unspentUtxos[0].amount)

        val transferToUnspentUtxos = transaction {
            UtxoManager.getAllUnspent(transferToAddress)
        }
        assertEquals(transferToUnspentUtxos.size, 1)
        assertEquals(BitcoinUtxoId.fromTxHashAndVout(txId3, transferVout), transferToUnspentUtxos[0].guid.value)
        assertEquals(transferAmount, transferToUnspentUtxos[0].amount)

        validateUtxoAndMempoolBalances(address)
    }

    private fun buildTransferTx(ecKey: ECKey, toAddress: BitcoinAddress, amount: BigInteger, changeAddress: BitcoinAddress, feeAmount: BigInteger, utxos: List<BitcoinUtxoEntity>): Transaction {
        val rawTx = Transaction(params)
        rawTx.setVersion(2)
        rawTx.addOutput(TransactionOutput(params, rawTx, Coin.valueOf(amount.toLong()), toAddress.toBitcoinCoreAddress(params)))
        val changeAmount = utxos.sumOf { it.amount } - amount - feeAmount
        if (changeAmount > BigInteger.ZERO) {
            rawTx.addOutput(TransactionOutput(params, rawTx, Coin.valueOf(changeAmount.toLong()), changeAddress.toBitcoinCoreAddress(params)))
        }
        utxos.forEach {
            rawTx.addSignedInput(TransactionOutPoint(params, it.vout(), Sha256Hash.wrap(it.txId().value)), ScriptBuilder.createP2WPKHOutputScript(ecKey), Coin.valueOf(it.amount.toLong()), ecKey, Transaction.SigHash.SINGLE, true)
        }
        return rawTx
    }
}
