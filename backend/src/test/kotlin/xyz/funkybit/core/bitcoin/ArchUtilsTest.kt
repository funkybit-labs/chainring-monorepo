package xyz.funkybit.core.bitcoin

import org.bitcoinj.core.ECKey
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.funkybit.core.blockchain.bitcoin.BitcoinClient
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.PubkeyAndIndex
import xyz.funkybit.core.model.Settlement
import xyz.funkybit.core.model.WalletAndSymbol
import xyz.funkybit.core.model.db.ArchAccountBalanceIndexEntity
import xyz.funkybit.core.model.db.ArchAccountBalanceIndexStatus
import xyz.funkybit.core.model.db.ArchAccountBalanceIndexTable
import xyz.funkybit.core.model.db.ArchAccountEntity
import xyz.funkybit.core.model.db.ArchAccountStatus
import xyz.funkybit.core.model.db.ArchAccountType
import xyz.funkybit.core.model.db.BitcoinUtxoId
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.WalletEntity
import xyz.funkybit.core.utils.bitcoin.ArchUtils
import xyz.funkybit.testfixtures.DbTestHelpers.createChain
import xyz.funkybit.testfixtures.DbTestHelpers.createNativeSymbol
import xyz.funkybit.testfixtures.DbTestHelpers.createWallet
import xyz.funkybit.testutils.TestWithDb
import java.math.BigInteger
import kotlin.test.assertEquals

class ArchUtilsTest : TestWithDb() {

    private lateinit var btc: SymbolEntity
    private lateinit var rune: SymbolEntity
    private lateinit var wallet1: WalletEntity
    private lateinit var wallet2: WalletEntity
    private lateinit var btcTokenAccount: ArchAccountEntity
    private lateinit var runeTokenAccount: ArchAccountEntity

    @BeforeEach
    fun setup() {
        transaction {
            val chain = createChain(ChainId(0UL), "test-chain")
            btc = createNativeSymbol("BTC", chain.id.value, decimals = 8U)
            rune = createNativeSymbol("RUNE", chain.id.value, decimals = 8U)
            wallet1 = createWallet(BitcoinAddress.fromKey(BitcoinClient.getParams(), ECKey()))
            wallet2 = createWallet(BitcoinAddress.fromKey(BitcoinClient.getParams(), ECKey()))
            btcTokenAccount = ArchAccountEntity.create(BitcoinUtxoId("hash:1"), ECKey(), ArchAccountType.TokenState, btc).also {
                it.status = ArchAccountStatus.Complete
            }
            runeTokenAccount = ArchAccountEntity.create(BitcoinUtxoId("hash:2"), ECKey(), ArchAccountType.TokenState, rune).also {
                it.status = ArchAccountStatus.Complete
            }
        }
    }

    @Test
    fun `test balance indexes for settlement`() {
        transaction {
            assertEquals(ArchAccountBalanceIndexEntity.count(), 0)
        }

        transaction {
            assertNull(
                ArchUtils.retrieveOrCreateBalanceIndexes(
                    getSettlementBatch(),
                ),
            )
        }

        transaction {
            assertEquals(ArchAccountBalanceIndexEntity.count(), 4)
            assertEquals(
                ArchAccountBalanceIndexEntity.count(ArchAccountBalanceIndexTable.status.eq(ArchAccountBalanceIndexStatus.Pending)),
                4,
            )
            assertEquals(
                ArchAccountBalanceIndexEntity.all().map { Pair(it.walletGuid.value, it.archAccountGuid.value) }.toSet(),
                setOf(
                    Pair(wallet1.id.value, btcTokenAccount.id.value),
                    Pair(wallet1.id.value, runeTokenAccount.id.value),
                    Pair(wallet2.id.value, btcTokenAccount.id.value),
                    Pair(wallet2.id.value, runeTokenAccount.id.value),
                ),
            )
        }

        // should return null as some are pending
        transaction {
            assertNull(
                ArchUtils.retrieveOrCreateBalanceIndexes(
                    getSettlementBatch(),
                ),
            )
        }

        // update to assigned
        transaction {
            assertEquals(ArchAccountBalanceIndexEntity.count(), 4)

            // assign indexes
            ArchAccountBalanceIndexEntity.findForWalletAddressAndSymbol(wallet1.address as BitcoinAddress, btc)?.let {
                it.addressIndex = 100
                it.status = ArchAccountBalanceIndexStatus.Assigned
            }
            ArchAccountBalanceIndexEntity.findForWalletAddressAndSymbol(wallet1.address as BitcoinAddress, rune)?.let {
                it.addressIndex = 101
                it.status = ArchAccountBalanceIndexStatus.Assigned
            }
            ArchAccountBalanceIndexEntity.findForWalletAddressAndSymbol(wallet2.address as BitcoinAddress, btc)?.let {
                it.addressIndex = 200
                it.status = ArchAccountBalanceIndexStatus.Assigned
            }
            ArchAccountBalanceIndexEntity.findForWalletAddressAndSymbol(wallet2.address as BitcoinAddress, rune)?.let {
                it.addressIndex = 201
                it.status = ArchAccountBalanceIndexStatus.Assigned
            }
        }

        // retrieve returns with the pubkey and indexes
        transaction {
            assertEquals(
                mapOf(
                    WalletAndSymbol(wallet1.id.value, btc.id.value) to PubkeyAndIndex(btcTokenAccount.rpcPubkey(), 100),
                    WalletAndSymbol(wallet1.id.value, rune.id.value) to PubkeyAndIndex(runeTokenAccount.rpcPubkey(), 101),
                    WalletAndSymbol(wallet2.id.value, btc.id.value) to PubkeyAndIndex(btcTokenAccount.rpcPubkey(), 200),
                    WalletAndSymbol(wallet2.id.value, rune.id.value) to PubkeyAndIndex(runeTokenAccount.rpcPubkey(), 201),
                ),
                ArchUtils.retrieveOrCreateBalanceIndexes(
                    getSettlementBatch(),
                ),
            )
        }
    }

    private fun getSettlementBatch() = Settlement.Batch(
        listOf(),
        listOf(),
        listOf(
            Settlement.TokenAdjustmentList(
                symbolId = btc.id.value,
                token = btc.name,
                increments = listOf(Settlement.Adjustment(wallet1.id.value, 0, BigInteger.ONE)),
                decrements = listOf(Settlement.Adjustment(wallet2.id.value, 1, BigInteger.ONE)),
                feeAmount = BigInteger.ZERO,
            ),
            Settlement.TokenAdjustmentList(
                symbolId = rune.id.value,
                token = rune.name,
                increments = listOf(Settlement.Adjustment(wallet1.id.value, 0, BigInteger.ONE)),
                decrements = listOf(Settlement.Adjustment(wallet2.id.value, 1, BigInteger.ONE)),
                feeAmount = BigInteger.ZERO,
            ),
        ),
    )
}
