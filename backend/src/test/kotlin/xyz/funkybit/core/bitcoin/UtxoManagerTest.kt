package xyz.funkybit.core.bitcoin

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.bitcoinj.core.ECKey
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import xyz.funkybit.core.blockchain.bitcoin.MempoolSpaceApi
import xyz.funkybit.core.blockchain.bitcoin.MempoolSpaceClient
import xyz.funkybit.core.blockchain.bitcoin.bitcoinConfig
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.db.BitcoinUtxoAddressMonitorEntity
import xyz.funkybit.core.model.db.BitcoinUtxoAddressMonitorId
import xyz.funkybit.core.model.db.BitcoinUtxoEntity
import xyz.funkybit.core.model.db.BitcoinUtxoId
import xyz.funkybit.core.model.db.BitcoinUtxoStatus
import xyz.funkybit.core.model.db.ChainEntity
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.DepositEntity
import xyz.funkybit.core.services.UtxoManager
import xyz.funkybit.core.utils.generateHexString
import xyz.funkybit.testfixtures.DbTestHelpers.createChain
import xyz.funkybit.testfixtures.DbTestHelpers.createNativeSymbol
import xyz.funkybit.testfixtures.DbTestHelpers.createWallet
import xyz.funkybit.testutils.TestWithDb
import java.math.BigInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UtxoManagerTest : TestWithDb() {

    private lateinit var chain: ChainEntity

    @BeforeTest
    fun setUp() {
        mockkObject(MempoolSpaceClient)
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @BeforeEach
    fun setup() {
        transaction {
            chain = createChain(ChainId(0UL), "test-chain")
            createNativeSymbol("BTC", chain.id.value, decimals = 8U)
        }
    }

    @Test
    fun `test handles program utxo properly`() {
        val programAddress = BitcoinAddress.fromKey(bitcoinConfig.params, ECKey())
        val walletAddress = BitcoinAddress.fromKey(bitcoinConfig.params, ECKey())
        val (txId1, txId2, txId3, txId4, txId5) = (0..5).map { TxHash(generateHexString().drop(1) + "${it + 1}") }
        val txId6 = TxHash(generateHexString().drop(1) + "6")

        transaction {
            BitcoinUtxoAddressMonitorEntity.createIfNotExists(programAddress, allowMempoolTxs = false, skipTxIds = listOf(txId1.value), isDepositAddress = true)
            BitcoinUtxoEntity.createIfNotExist(BitcoinUtxoId("${txId2.value}:0"), programAddress, 10000L)
            BitcoinUtxoEntity.createIfNotExist(BitcoinUtxoId("${txId3.value}:1"), programAddress, 12000L).status = BitcoinUtxoStatus.Reserved
            createWallet(walletAddress)
        }
        every { MempoolSpaceClient.getTransactions(programAddress, null) } returns listOf(
            // it returns mempool ones, followed by confirmed, most recent first
            MempoolSpaceApi.Transaction(
                txId = txId6,
                version = 2,
                size = 32,
                weight = 2,
                vins = listOf(
                    MempoolSpaceApi.VIn(
                        txId = txId3,
                        vout = 1,
                        prevOut = MempoolSpaceApi.VOut(
                            scriptPubKeyAddress = programAddress,
                            value = BigInteger("12000"),
                        ),
                    ),
                ),
                vouts = listOf(
                    MempoolSpaceApi.VOut(
                        scriptPubKeyAddress = walletAddress,
                        value = BigInteger("3000"),
                    ),
                    MempoolSpaceApi.VOut(
                        scriptPubKeyAddress = programAddress,
                        value = BigInteger("7000"),
                    ),
                ),
                status = MempoolSpaceApi.Status(
                    confirmed = false,
                    blockHeight = null,
                ),
            ),
            MempoolSpaceApi.Transaction(
                txId = txId5,
                version = 2,
                size = 32,
                weight = 2,
                vins = listOf(
                    MempoolSpaceApi.VIn(
                        txId = txId6,
                        vout = 0,
                        prevOut = MempoolSpaceApi.VOut(
                            scriptPubKeyAddress = walletAddress,
                            value = BigInteger("10000"),
                        ),
                    ),
                ),
                vouts = listOf(
                    MempoolSpaceApi.VOut(
                        scriptPubKeyAddress = walletAddress,
                        value = BigInteger("1500"),
                    ),
                    MempoolSpaceApi.VOut(
                        scriptPubKeyAddress = programAddress,
                        value = BigInteger("6500"),
                    ),
                ),
                status = MempoolSpaceApi.Status(
                    confirmed = true,
                    blockHeight = 7L,
                ),
            ),
            MempoolSpaceApi.Transaction(
                txId = txId4,
                version = 2,
                size = 32,
                weight = 2,
                vins = listOf(
                    MempoolSpaceApi.VIn(
                        txId = txId2,
                        vout = 0,
                        prevOut = MempoolSpaceApi.VOut(
                            scriptPubKeyAddress = programAddress,
                            value = BigInteger("10000"),
                        ),
                    ),
                ),
                vouts = listOf(
                    MempoolSpaceApi.VOut(
                        scriptPubKeyAddress = walletAddress,
                        value = BigInteger("1500"),
                    ),
                    MempoolSpaceApi.VOut(
                        scriptPubKeyAddress = programAddress,
                        value = BigInteger("6000"),
                    ),
                ),
                status = MempoolSpaceApi.Status(
                    confirmed = true,
                    blockHeight = 6L,
                ),
            ),
            MempoolSpaceApi.Transaction(
                txId = txId1,
                version = 2,
                size = 32,
                weight = 2,
                vins = listOf(),
                vouts = listOf(
                    MempoolSpaceApi.VOut(
                        scriptPubKeyAddress = programAddress,
                        value = BigInteger("1500"),
                    ),
                ),
                status = MempoolSpaceApi.Status(
                    confirmed = true,
                    blockHeight = 5L,
                ),
            ),
        )

        // after first page, ask for 2nd, giving last txId from previous page.
        every { MempoolSpaceClient.getTransactions(programAddress, txId1) } returns listOf()

        transaction {
            UtxoManager.refreshUtxos(programAddress)
        }

        transaction {
            assertEquals(BitcoinUtxoStatus.Spent, BitcoinUtxoEntity[BitcoinUtxoId("${txId2.value}:0")].status)
            assertEquals(txId4.value, BitcoinUtxoEntity[BitcoinUtxoId("${txId2.value}:0")].spentByTxId)

            assertEquals(BitcoinUtxoStatus.Reserved, BitcoinUtxoEntity[BitcoinUtxoId("${txId3.value}:1")].status)

            assertEquals(BitcoinUtxoStatus.Unspent, BitcoinUtxoEntity[BitcoinUtxoId("${txId4.value}:1")].status)
            assertEquals(BigInteger("6000"), BitcoinUtxoEntity[BitcoinUtxoId("${txId4.value}:1")].amount)

            assertEquals(BitcoinUtxoStatus.Unspent, BitcoinUtxoEntity[BitcoinUtxoId("${txId5.value}:1")].status)
            assertEquals(BigInteger("6500"), BitcoinUtxoEntity[BitcoinUtxoId("${txId5.value}:1")].amount)

            // should not know about txId since we specified to skip
            assertNull(BitcoinUtxoEntity.findById(BitcoinUtxoId("${txId1.value}:0")))

            // should not know about txId5 since its in mempool and not confirmed
            assertNull(BitcoinUtxoEntity.findById(BitcoinUtxoId("${txId6.value}:1")))

            assertEquals(BitcoinUtxoEntity.findUnspentTotal(programAddress), BigInteger("12500"))

            assertEquals(
                7,
                BitcoinUtxoAddressMonitorEntity.findById(BitcoinUtxoAddressMonitorId(programAddress.value))!!.lastSeenBlockHeight,
            )

            // check we found a deposit
            assertEquals(DepositEntity.count(), 1)
            assertEquals(DepositEntity.all().first().amount, BigInteger("6500"))
            assertEquals(DepositEntity.all().first().wallet.address, walletAddress)
            assertEquals(DepositEntity.all().first().blockNumber, BigInteger("7"))
        }
    }

    @Test
    fun `test handles fee payer utxo properly`() {
        val feePayerAddress = BitcoinAddress.fromKey(bitcoinConfig.params, ECKey())
        val walletAddress = BitcoinAddress.fromKey(bitcoinConfig.params, ECKey())
        val (txId1, txId2, txId3, txId4, txId5) = (0..4).map { TxHash(generateHexString().drop(1) + "${it + 1}") }

        transaction {
            BitcoinUtxoAddressMonitorEntity.createIfNotExists(feePayerAddress, allowMempoolTxs = true)
            BitcoinUtxoEntity.createIfNotExist(BitcoinUtxoId("${txId2.value}:0"), feePayerAddress, 10000L)
            BitcoinUtxoEntity.createIfNotExist(BitcoinUtxoId("${txId3.value}:1"), feePayerAddress, 12000L).status = BitcoinUtxoStatus.Reserved
            createWallet(walletAddress)
        }
        every { MempoolSpaceClient.getTransactions(feePayerAddress, null) } returns listOf(
            // ths is in the mempool so should be ignored
            MempoolSpaceApi.Transaction(
                txId = txId5,
                version = 2,
                size = 32,
                weight = 2,
                vins = listOf(
                    MempoolSpaceApi.VIn(
                        txId = txId3,
                        vout = 1,
                        prevOut = MempoolSpaceApi.VOut(
                            scriptPubKeyAddress = feePayerAddress,
                            value = BigInteger("12000"),
                        ),
                    ),
                ),
                vouts = listOf(
                    MempoolSpaceApi.VOut(
                        scriptPubKeyAddress = walletAddress,
                        value = BigInteger("3000"),
                    ),
                    MempoolSpaceApi.VOut(
                        scriptPubKeyAddress = feePayerAddress,
                        value = BigInteger("7000"),
                    ),
                ),
                status = MempoolSpaceApi.Status(
                    confirmed = false,
                    blockHeight = null,
                ),
            ),
            MempoolSpaceApi.Transaction(
                txId = txId4,
                version = 2,
                size = 32,
                weight = 2,
                vins = listOf(
                    MempoolSpaceApi.VIn(
                        txId = txId2,
                        vout = 0,
                        prevOut = MempoolSpaceApi.VOut(
                            scriptPubKeyAddress = feePayerAddress,
                            value = BigInteger("10000"),
                        ),
                    ),
                ),
                vouts = listOf(
                    MempoolSpaceApi.VOut(
                        scriptPubKeyAddress = walletAddress,
                        value = BigInteger("1500"),
                    ),
                    MempoolSpaceApi.VOut(
                        scriptPubKeyAddress = feePayerAddress,
                        value = BigInteger("6000"),
                    ),
                ),
                status = MempoolSpaceApi.Status(
                    confirmed = true,
                    blockHeight = 6L,
                ),
            ),
            MempoolSpaceApi.Transaction(
                txId = txId1,
                version = 2,
                size = 32,
                weight = 2,
                vins = listOf(),
                vouts = listOf(
                    MempoolSpaceApi.VOut(
                        scriptPubKeyAddress = feePayerAddress,
                        value = BigInteger("1500"),
                    ),
                ),
                status = MempoolSpaceApi.Status(
                    confirmed = true,
                    blockHeight = 5L,
                ),
            ),
        )

        every { MempoolSpaceClient.getTransactions(feePayerAddress, txId1) } returns listOf()

        transaction {
            UtxoManager.refreshUtxos(feePayerAddress)
        }

        transaction {
            assertEquals(BitcoinUtxoStatus.Spent, BitcoinUtxoEntity[BitcoinUtxoId("${txId2.value}:0")].status)
            assertEquals(txId4.value, BitcoinUtxoEntity[BitcoinUtxoId("${txId2.value}:0")].spentByTxId)

            assertEquals(BitcoinUtxoStatus.Spent, BitcoinUtxoEntity[BitcoinUtxoId("${txId3.value}:1")].status)
            assertEquals(txId5.value, BitcoinUtxoEntity[BitcoinUtxoId("${txId3.value}:1")].spentByTxId)

            assertEquals(BitcoinUtxoStatus.Unspent, BitcoinUtxoEntity[BitcoinUtxoId("${txId4.value}:1")].status)
            assertEquals(BigInteger("6000"), BitcoinUtxoEntity[BitcoinUtxoId("${txId4.value}:1")].amount)

            assertEquals(BitcoinUtxoStatus.Unspent, BitcoinUtxoEntity[BitcoinUtxoId("${txId5.value}:1")].status)
            assertEquals(BigInteger("7000"), BitcoinUtxoEntity[BitcoinUtxoId("${txId5.value}:1")].amount)

            assertEquals(BitcoinUtxoEntity.findUnspentTotal(feePayerAddress), BigInteger("14500"))
        }
    }
}
