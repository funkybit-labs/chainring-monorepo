package xyz.funkybit.core.model.db

import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.funkybit.core.model.Address
import xyz.funkybit.testfixtures.DbTestHelpers.createChain
import xyz.funkybit.testfixtures.DbTestHelpers.createSymbol
import xyz.funkybit.testutils.TestWithDb
import kotlin.random.Random
import kotlin.time.Duration.Companion.days

class FaucetDripTest : TestWithDb() {
    private lateinit var symbol: SymbolEntity

    @BeforeEach
    fun setup() {
        transaction {
            val chain = createChain(ChainId(123UL), "test-chain")
            symbol = createSymbol("TEST", chain.id.value, decimals = 18U)
        }
    }

    @Test
    fun `test faucet drip entity`() {
        val walletAddress = Address.generate()
        val ipAddress = Random.nextInt().toString()
        transaction {
            assertTrue(FaucetDripEntity.eligible(symbol, walletAddress, ipAddress))
            val faucetDrip = FaucetDripEntity.create(symbol, walletAddress, ipAddress)
            assertFalse(FaucetDripEntity.eligible(symbol, walletAddress, ipAddress))
            assertFalse(FaucetDripEntity.eligible(symbol, Address.generate(), ipAddress))
            assertFalse(FaucetDripEntity.eligible(symbol, walletAddress, Random.nextInt().toString()))
            faucetDrip.createdAt = Clock.System.now().minus(1.days)
            faucetDrip.flush()
            assertTrue(FaucetDripEntity.eligible(symbol, walletAddress, ipAddress))
        }
    }
}
