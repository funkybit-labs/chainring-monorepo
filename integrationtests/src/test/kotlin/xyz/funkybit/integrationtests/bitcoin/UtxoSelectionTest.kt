package xyz.funkybit.integrationtests.bitcoin

import io.github.oshai.kotlinlogging.KotlinLogging
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
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.bitcoin.UtxoId
import xyz.funkybit.core.model.db.TxHash
import xyz.funkybit.core.model.db.UnspentUtxo
import xyz.funkybit.core.services.UtxoSelectionService
import xyz.funkybit.core.utils.bitcoin.BitcoinInsufficientFundsException
import xyz.funkybit.core.utils.bitcoin.inSatsAsDecimalString
import xyz.funkybit.core.utils.toHex
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.testutils.isTestEnvRun
import xyz.funkybit.integrationtests.testutils.waitFor
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals

@ExtendWith(AppUnderTestRunner::class)
class UtxoSelectionTest {
    private val logger = KotlinLogging.logger {}

    companion object {

        val params = BitcoinClient.getParams()
        fun waitForTx(address: BitcoinAddress, txId: TxHash) {
            waitFor {
                MempoolSpaceClient.getTransactions(address, null).firstOrNull { it.txId == txId } != null
            }
        }
    }

    @Test
    fun testUtxoSelection() {
        Assumptions.assumeFalse(isTestEnvRun())

        // create a wallet
        val ecKey = ECKey()
        val address = BitcoinAddress.fromKey(params, ecKey)
        logger.debug { "address is $address" }

        // airdrop 3000 sats
        val txId = BitcoinClient.sendToAddress(address, BigInteger("3000"))

        waitForTx(address, txId)
        val expectedVout = BitcoinClient.getRawTransaction(txId).txOuts.first { it.value.compareTo(BigDecimal("0.00003000")) == 0 }.index

        // check the expected Utxo is returned
        var selectedUtxos = transaction {
            UtxoSelectionService.selectUtxos(address, BigInteger("1500"), BigInteger("1500"))
        }

        assertEquals(1, selectedUtxos.size)
        assertEquals(UtxoId.fromTxHashAndVout(txId, expectedVout), selectedUtxos[0].utxoId)
        assertEquals(BigInteger("3000"), selectedUtxos[0].amount)

        // check it fails if we request more that available
        assertThrows<BitcoinInsufficientFundsException> {
            transaction {
                UtxoSelectionService.selectUtxos(address, BigInteger("2000"), BigInteger("1500"))
            }
        }

        // airdrop some more
        val txId2 = BitcoinClient.sendToAddress(address, BigInteger("3100"))
        waitForTx(address, txId2)
        val expectedVout2 = BitcoinClient.getRawTransaction(txId2).txOuts.first { it.value.compareTo(BigDecimal("0.00003100")) == 0 }.index
        selectedUtxos = transaction {
            UtxoSelectionService.selectUtxos(address, BigInteger("3000"), BigInteger("1500"))
        }
        assertEquals(2, selectedUtxos.size)
        assertEquals(setOf(UtxoId.fromTxHashAndVout(txId, expectedVout), UtxoId.fromTxHashAndVout(txId2, expectedVout2)), selectedUtxos.map { it.utxoId }.toSet())
        assertEquals(BigInteger("6100"), selectedUtxos.sumOf { it.amount })

        val transferToAddress = BitcoinAddress.fromKey(params, ECKey())

        val transaction = buildTransferTx(ecKey, transferToAddress, BigInteger("3000"), address, BigInteger("1500"), selectedUtxos)
        val txId3 = BitcoinClient.sendRawTransaction(transaction.toHexString())
        assertEquals(transaction.txId.bytes.toHex(false), txId3.value)

        waitForTx(address, txId3)
        val tx = BitcoinClient.getRawTransaction(txId3)
        val changeVout = tx.txOuts.first { it.value.compareTo(BigDecimal("0.00001600")) == 0 }.index
        val transferVout = tx.txOuts.first { it.value.compareTo(BigDecimal("0.00003000")) == 0 }.index
        val unspentUtxos = transaction {
            UtxoSelectionService.refreshUnspentUtxos(address)
        }
        assertEquals(unspentUtxos.size, 1)
        assertEquals(UtxoId.fromTxHashAndVout(txId3, changeVout), unspentUtxos[0].utxoId)
        assertEquals(BigInteger("1600"), unspentUtxos[0].amount)

        val transferToUnspentUtxos = transaction {
            UtxoSelectionService.refreshUnspentUtxos(transferToAddress)
        }
        assertEquals(transferToUnspentUtxos.size, 1)
        assertEquals(UtxoId.fromTxHashAndVout(txId3, transferVout), transferToUnspentUtxos[0].utxoId)
        assertEquals(BigInteger("3000"), transferToUnspentUtxos[0].amount)

        BitcoinClient.mine(1)
    }

    @Test
    fun testUtxoSelectionPagination() {
        Assumptions.assumeFalse(isTestEnvRun())

        // create a wallet
        val ecKey = ECKey()
        val address = BitcoinAddress.fromKey(params, ecKey)
        logger.debug { "address is $address" }

        val txIds = (1..40).map {
            BitcoinClient.sendToAddressAndMine(address, BigInteger("3000"))
        }

        waitForTx(address, txIds.last())
        val initialUnspentUtxos = transaction {
            UtxoSelectionService.refreshUnspentUtxos(address)
        }
        assertEquals(40, initialUnspentUtxos.size)

        // transfer all the UTXOs
        val transferAmount = BigInteger("3000") * BigInteger("38")
        val feeAmount = BigInteger("4000")
        val selectedUtxos = transaction {
            UtxoSelectionService.selectUtxos(address, transferAmount, feeAmount)
        }
        val transferToAddress = BitcoinAddress.fromKey(params, ECKey())

        val transaction = buildTransferTx(ecKey, transferToAddress, transferAmount, address, feeAmount, selectedUtxos)
        val txId3 = BitcoinClient.sendRawTransaction(transaction.toHexString())

        BitcoinClient.mine(1)

        waitForTx(address, txId3)
        val tx = BitcoinClient.getRawTransaction(txId3)
        val changeVout = tx.txOuts.first { it.value.compareTo(BigDecimal("0.00002000")) == 0 }.index
        val transferVout = tx.txOuts.first { it.value.compareTo(BigDecimal(transferAmount.inSatsAsDecimalString())) == 0 }.index

        val unspentUtxos = transaction {
            UtxoSelectionService.refreshUnspentUtxos(address)
        }
        assertEquals(unspentUtxos.size, 1)
        assertEquals(UtxoId.fromTxHashAndVout(txId3, changeVout), unspentUtxos[0].utxoId)
        assertEquals(BigInteger("2000"), unspentUtxos[0].amount)

        val transferToUnspentUtxos = transaction {
            UtxoSelectionService.refreshUnspentUtxos(transferToAddress)
        }
        assertEquals(transferToUnspentUtxos.size, 1)
        assertEquals(UtxoId.fromTxHashAndVout(txId3, transferVout), transferToUnspentUtxos[0].utxoId)
        assertEquals(transferAmount, transferToUnspentUtxos[0].amount)
    }

    private fun buildTransferTx(ecKey: ECKey, toAddress: BitcoinAddress, amount: BigInteger, changeAddress: BitcoinAddress, feeAmount: BigInteger, utxos: List<UnspentUtxo>): Transaction {
        val rawTx = Transaction(params)
        rawTx.setVersion(2)
        rawTx.addOutput(TransactionOutput(params, rawTx, Coin.valueOf(amount.toLong()), toAddress.toBitcoinCoreAddress(params)))
        val changeAmount = utxos.sumOf { it.amount } - amount - feeAmount
        if (changeAmount > BigInteger.ZERO) {
            rawTx.addOutput(TransactionOutput(params, rawTx, Coin.valueOf(changeAmount.toLong()), changeAddress.toBitcoinCoreAddress(params)))
        }
        utxos.forEach {
            rawTx.addSignedInput(TransactionOutPoint(params, it.utxoId.vout(), Sha256Hash.wrap(it.utxoId.txId().value)), ScriptBuilder.createP2WPKHOutputScript(ecKey), Coin.valueOf(it.amount.toLong()), ecKey, Transaction.SigHash.SINGLE, true)
        }
        return rawTx
    }
}
