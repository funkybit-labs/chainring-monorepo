package xyz.funkybit.core.bitcoin

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.bitcoinj.core.ECKey
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.funkybit.apps.api.model.BigDecimalJson
import xyz.funkybit.apps.ring.BitcoinBlockProcessor
import xyz.funkybit.core.blockchain.ContractType
import xyz.funkybit.core.blockchain.bitcoin.BitcoinClient
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.db.BitcoinUtxoAddressMonitorEntity
import xyz.funkybit.core.model.db.BitcoinUtxoEntity
import xyz.funkybit.core.model.db.BitcoinUtxoId
import xyz.funkybit.core.model.db.BitcoinUtxoStatus
import xyz.funkybit.core.model.db.BlockEntity
import xyz.funkybit.core.model.db.BlockHash
import xyz.funkybit.core.model.db.ChainEntity
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.DeployedSmartContractEntity
import xyz.funkybit.core.model.db.DepositEntity
import xyz.funkybit.core.model.rpc.BitcoinRpc
import xyz.funkybit.core.utils.generateHexString
import xyz.funkybit.testfixtures.DbTestHelpers.createChain
import xyz.funkybit.testfixtures.DbTestHelpers.createNativeSymbol
import xyz.funkybit.testfixtures.DbTestHelpers.createWallet
import xyz.funkybit.testutils.TestWithDb
import java.math.BigInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertEquals

class BitcoinBlockProcessorTest : TestWithDb() {

    private val bitcoinBlockProcessor = BitcoinBlockProcessor()
    private lateinit var chain: ChainEntity

    @BeforeTest
    fun setUp() {
        mockkObject(BitcoinClient)
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
    fun `test block processing`() {
        val programAddress = BitcoinAddress.fromKey(BitcoinClient.getParams(), ECKey())
        val walletAddress = BitcoinAddress.fromKey(BitcoinClient.getParams(), ECKey())
        val (txId1, txId2, txId3) = (0..2).map { generateHexString() }

        transaction {
            val block1 = BlockEntity.create(BlockHash("hash1"), BigInteger("1"), BlockHash("hash0"), chain.id.value)
            BitcoinUtxoAddressMonitorEntity.createIfNotExists(programAddress)
            BitcoinUtxoEntity.create(BitcoinUtxoId("$txId1:0"), programAddress, block1, 1L).status = BitcoinUtxoStatus.Reserved
            BitcoinUtxoEntity.create(BitcoinUtxoId("$txId2:1"), programAddress, block1, 1L)
            DeployedSmartContractEntity.create(ContractType.Exchange.name, chain.id.value, programAddress, programAddress, 1)
            createWallet(walletAddress)
        }
        val blockHeader = BitcoinRpc.Block(
            hash = "hash2",
            confirmations = 1,
            numberOfTx = 2,
            transactions = listOf(),
            time = 0L,
            medianTime = 1L,
            chainWork = "",
            nonce = 0L,
            bits = "bits",
            previousBlockhash = "hash1",
            nextBlockhash = null,
        )
        every { BitcoinClient.getBlockCount() } returns 2L
        every { BitcoinClient.getBlockHash(2L) } returns "hash2"
        every { BitcoinClient.getBlockHeader("hash2") } returns blockHeader
        every { BitcoinClient.getBlock("hash2") } returns blockHeader.copy(
            transactions = listOf(
                BitcoinRpc.Transaction(
                    txId = TxHash(txId3),
                    hash = "hash",
                    size = 32,
                    vsize = 2,
                    weight = 3,
                    txIns = listOf(
                        BitcoinRpc.TxIn(
                            txId = TxHash(txId1),
                            outIndex = 0,
                            scriptSig = null,
                        ),
                        BitcoinRpc.TxIn(
                            txId = TxHash(txId2),
                            outIndex = 1,
                            scriptSig = null,
                        ),
                    ),
                    txOuts = listOf(
                        BitcoinRpc.TxOut(
                            value = BigDecimalJson("0.00040000"),
                            index = 0,
                            scriptPubKey = BitcoinRpc.ScriptPubKey(
                                asm = "",
                                hex = "",
                                reqSigs = 0,
                                type = "",
                                addresses = null,
                                address = programAddress.value,
                            ),
                        ),
                        BitcoinRpc.TxOut(
                            value = BigDecimalJson("0.00050000"),
                            index = 1,
                            scriptPubKey = BitcoinRpc.ScriptPubKey(
                                asm = "",
                                hex = "",
                                reqSigs = 0,
                                type = "",
                                addresses = null,
                                address = BitcoinAddress.fromKey(BitcoinClient.getParams(), ECKey()).value,
                            ),
                        ),
                    ),
                    hex = "",
                    confirmations = 1,
                ),
            ),
        )
        every { BitcoinClient.getRawTransaction(TxHash(txId1)) } returns BitcoinRpc.Transaction(
            txId = TxHash(txId1),
            hash = "hash",
            size = 32,
            vsize = 2,
            weight = 3,
            txIns = listOf(),
            txOuts = listOf(
                BitcoinRpc.TxOut(
                    value = BigDecimalJson("0.00040000"),
                    index = 0,
                    scriptPubKey = BitcoinRpc.ScriptPubKey(
                        asm = "",
                        hex = "",
                        reqSigs = 0,
                        type = "",
                        addresses = null,
                        address = walletAddress.value,
                    ),
                ),
                BitcoinRpc.TxOut(
                    value = BigDecimalJson("0.00050000"),
                    index = 0,
                    scriptPubKey = BitcoinRpc.ScriptPubKey(
                        asm = "",
                        hex = "",
                        reqSigs = 0,
                        type = "",
                        addresses = null,
                        address = BitcoinAddress.fromKey(BitcoinClient.getParams(), ECKey()).value,
                    ),
                ),
            ),
            hex = "",
            confirmations = 1,
        )

        transaction {
            bitcoinBlockProcessor.processBlocks()
        }

        transaction {
            assertEquals(2, BlockEntity.all().count())
            val newBlock = BlockEntity[BlockHash("hash2")]
            assertEquals(newBlock.number, BigInteger("2"))
            assertEquals(newBlock.parentGuid, BlockHash("hash1"))
            assertEquals(BitcoinUtxoStatus.Spent, BitcoinUtxoEntity[BitcoinUtxoId("$txId1:0")].status)
            assertEquals(newBlock.guid, BitcoinUtxoEntity[BitcoinUtxoId("$txId1:0")].spentByBlockGuid)
            assertEquals(BitcoinUtxoStatus.Spent, BitcoinUtxoEntity[BitcoinUtxoId("$txId2:1")].status)
            assertEquals(newBlock.guid, BitcoinUtxoEntity[BitcoinUtxoId("$txId2:1")].spentByBlockGuid)

            assertEquals(BitcoinUtxoStatus.Unspent, BitcoinUtxoEntity[BitcoinUtxoId("$txId3:0")].status)
            assertEquals(newBlock.guid, BitcoinUtxoEntity[BitcoinUtxoId("$txId3:0")].createdByBlockGuid)
            assertNull(BitcoinUtxoEntity[BitcoinUtxoId("$txId3:0")].spentByBlockGuid)

            assertEquals(DepositEntity.count(), 1)
            assertEquals(DepositEntity.all().first().amount, BigInteger("40000"))
            assertEquals(DepositEntity.all().first().wallet.address, walletAddress)
            assertEquals(DepositEntity.all().first().blockNumber, BigInteger.TWO)
        }
    }

    @Test
    fun `test block rollback forking`() {
        val bitcoinAddress = BitcoinAddress.fromKey(BitcoinClient.getParams(), ECKey())
        transaction {
            val blocks = (1..8).map {
                BlockEntity.create(BlockHash("hash$it"), BigInteger("$it"), BlockHash("hash${it - 1}"), chain.id.value)
            }
            val block9 = BlockEntity.create(BlockHash("hash9-fork"), BigInteger("9"), BlockHash("hash8"), chain.id.value)
            val block10 = BlockEntity.create(BlockHash("hash10-fork"), BigInteger("10"), BlockHash("hash9-fork"), chain.id.value)
            BitcoinUtxoAddressMonitorEntity.createIfNotExists(bitcoinAddress)
            BitcoinUtxoEntity.create(BitcoinUtxoId("txId1:1"), bitcoinAddress, blocks[6], 1L)
            BitcoinUtxoEntity.create(BitcoinUtxoId("txId2:1"), bitcoinAddress, blocks[7], 1L)
            BitcoinUtxoEntity.create(BitcoinUtxoId("txId3:1"), bitcoinAddress, block9, 1L)
            BitcoinUtxoEntity.create(BitcoinUtxoId("txId4:1"), bitcoinAddress, block10, 1L)
            BitcoinUtxoEntity.spend(listOf(BitcoinUtxoId("txId1:1")), blocks[7])
            BitcoinUtxoEntity.spend(listOf(BitcoinUtxoId("txId2:1")), block9)
            BitcoinUtxoEntity.spend(listOf(BitcoinUtxoId("txId3:1")), block10)
        }
        (1..11).forEach {
            every { BitcoinClient.getBlockHash(it.toLong()) } returns "hash$it"
        }
        every { BitcoinClient.getBlockCount() } returns 11L
        every { BitcoinClient.getBlockHeader("hash11") } returns BitcoinRpc.Block(
            hash = "hash11",
            confirmations = 1,
            numberOfTx = 2,
            transactions = listOf(),
            time = 0L,
            medianTime = 1L,
            chainWork = "",
            nonce = 0L,
            bits = "bits",
            previousBlockhash = "hash10",
            nextBlockhash = null,
        )

        transaction {
            bitcoinBlockProcessor.processBlocks()
        }

        transaction {
            assertEquals(8, BlockEntity.all().count())
            assertEquals((1..8).map { BigInteger("$it") }.toSet(), BlockEntity.all().map { it.number }.toSet())
            // should still be spent since was not in forked blocks
            assertEquals(BitcoinUtxoStatus.Spent, BitcoinUtxoEntity[BitcoinUtxoId("txId1:1")].status)
            // should be unspent as was spent in the fork
            assertEquals(BitcoinUtxoStatus.Unspent, BitcoinUtxoEntity[BitcoinUtxoId("txId2:1")].status)
            assertNull(BitcoinUtxoEntity[BitcoinUtxoId("txId2:1")].spentByBlockGuid)
            // should be gone as were created in forked blocks
            assertNull(BitcoinUtxoEntity.findById(BitcoinUtxoId("txId3:1")))
            assertNull(BitcoinUtxoEntity.findById(BitcoinUtxoId("txId4:1")))
        }
    }
}
