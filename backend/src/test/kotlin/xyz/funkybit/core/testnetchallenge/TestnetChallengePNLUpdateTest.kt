package xyz.funkybit.core.testnetchallenge

import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.db.BalanceId
import xyz.funkybit.core.model.db.BalanceTable
import xyz.funkybit.core.model.db.BalanceType
import xyz.funkybit.core.model.db.ChainEntity
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.MarketEntity
import xyz.funkybit.core.model.db.NetworkType
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.TestnetChallengePNLEntity
import xyz.funkybit.core.model.db.TestnetChallengePNLType
import xyz.funkybit.core.model.db.WalletEntity
import xyz.funkybit.core.utils.toFundamentalUnits
import xyz.funkybit.testutils.TestWithDb
import java.math.BigDecimal
import java.math.BigInteger

class TestnetChallengePNLUpdateTest : TestWithDb() {
    @Test
    fun `test testnet challenge pnl updates`() {
        val now = Clock.System.now()
        // first set up some test data
        val user1 = transaction {
            val chainId = ChainId(1337UL)
            ChainEntity.create(chainId, "chain", "", "", "", NetworkType.Evm)
            val usdc = SymbolEntity.create("USDC", chainId, null, 6.toUByte(), "USDC", false, BigInteger.ZERO)
            val btc = SymbolEntity.create("BTC", chainId, null, 18.toUByte(), "BTC", false, BigInteger.ZERO)
            val eth = SymbolEntity.create("ETH", chainId, null, 18.toUByte(), "ETH", false, BigInteger.ZERO)
            MarketEntity.create(btc, usdc, BigDecimal.valueOf(10L), BigDecimal.valueOf(60000), "")
            MarketEntity.create(eth, usdc, BigDecimal.valueOf(10L), BigDecimal.valueOf(2500), "")
            val wallet1a = WalletEntity.getOrCreateWithUser(EvmAddress.generate()).first
            val user1 = wallet1a.user
            val wallet1b = WalletEntity.createForUser(user1, BitcoinAddress.canonicalize("btcaddress1"))
            val wallet2a = WalletEntity.getOrCreateWithUser(EvmAddress.generate()).first
            val user2 = wallet2a.user
            val wallet2b = WalletEntity.createForUser(user2, BitcoinAddress.canonicalize("btcaddress2"))

            TestnetChallengePNLEntity.initializeForUser(user1)

            val pnls = TestnetChallengePNLEntity.all().toList()
            assertEquals(3, pnls.size)
            assertEquals(TestnetChallengePNLType.entries.toSet(), pnls.map { it.type }.toSet())
            pnls.forEach { pnl ->
                assertEquals(BigDecimal(10000).setScale(18), pnl.initialBalance)
                assertEquals(BigDecimal(10000).setScale(18), pnl.currentBalance)
            }

            TestnetChallengePNLEntity.initializeForUser(user2)

            // 100 USDC, 1 BTC, 10 ETH for user 1
            listOf(100L to usdc, 1L to btc, 10L to eth).forEach { (amount, symbol) ->
                // add for both Exchange and Available (but only the Exchange should count)
                BalanceType.entries.forEach { balanceType ->
                    BalanceTable.insert {
                        it[guid] = BalanceId.generate()
                        it[type] = balanceType
                        it[balance] = BigDecimal.valueOf(amount).toFundamentalUnits(if (symbol == usdc) 6 else 18).toBigDecimal()
                        it[symbolGuid] = symbol.guid
                        it[walletGuid] = if (symbol == btc) wallet1b.guid else wallet1a.guid
                        it[createdAt] = now
                        it[createdBy] = ""
                    }
                }
            }
            // 50 USDC, 2 BTC, 5 ETH for user 2
            listOf(50L to usdc, 2L to btc, 5L to eth).forEach { (amount, symbol) ->
                BalanceTable.insert {
                    it[guid] = BalanceId.generate()
                    it[type] = BalanceType.Exchange
                    it[balance] = BigDecimal.valueOf(amount).toFundamentalUnits(if (symbol == usdc) 6 else 18).toBigDecimal()
                    it[symbolGuid] = symbol.guid
                    it[walletGuid] = if (symbol == btc) wallet2b.guid else wallet2a.guid
                    it[createdAt] = now
                    it[createdBy] = ""
                }
            }
            user1
        }
        transaction {
            TestnetChallengePNLEntity.updateAllBalances()

            val updatedPnls = TestnetChallengePNLEntity.all().toList()
            assertEquals(6, updatedPnls.size)
            val user1Pnls = updatedPnls.filter { it.user.guid == user1.guid }
            assertEquals(TestnetChallengePNLType.entries.toSet(), user1Pnls.map { it.type }.toSet())
            user1Pnls.forEach { pnl ->
                assertEquals(BigDecimal(10000).setScale(18), pnl.initialBalance)
                assertEquals(BigDecimal(100L + 60000L + 25000L).setScale(18), pnl.currentBalance)
            }
        }
    }
}
