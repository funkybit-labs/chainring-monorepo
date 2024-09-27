package xyz.funkybit.integrationtests.api

import kotlinx.datetime.Clock
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withAlias
import org.http4k.client.WebsocketClient
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.extension.ExtendWith
import org.web3j.utils.Numeric
import xyz.funkybit.apps.api.model.CreateDepositApiRequest
import xyz.funkybit.apps.api.model.Deposit
import xyz.funkybit.apps.ring.EvmDepositHandler
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.db.DepositEntity
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.testutils.isTestEnvRun
import xyz.funkybit.integrationtests.testutils.waitFor
import xyz.funkybit.integrationtests.testutils.waitForBalance
import xyz.funkybit.integrationtests.utils.AssetAmount
import xyz.funkybit.integrationtests.utils.ExpectedBalance
import xyz.funkybit.integrationtests.utils.Faucet
import xyz.funkybit.integrationtests.utils.TestApiClient
import xyz.funkybit.integrationtests.utils.Wallet
import xyz.funkybit.integrationtests.utils.assertAmount
import xyz.funkybit.integrationtests.utils.assertBalancesMessageReceived
import xyz.funkybit.integrationtests.utils.blocking
import xyz.funkybit.integrationtests.utils.subscribeToBalances
import xyz.funkybit.tasks.fixtures.toChainSymbol
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.minutes

@ExtendWith(AppUnderTestRunner::class)
class DepositTest {
    @Test
    fun `deposits multiple chains`() {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)
        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        val config = apiClient.getConfiguration()

        val chains = config.evmChains
        assertEquals(chains.size, 2)

        (0 until chains.size).forEach { index ->

            wallet.switchChain(chains[index].id)
            Faucet.fundAndMine(wallet.evmAddress, chainId = wallet.currentChainId)

            val btc = chains[index].symbols.first { it.name == "BTC".toChainSymbol(chains[index].id) }
            val usdc = chains[index].symbols.first { it.name == "USDC".toChainSymbol(chains[index].id) }
            val symbolFilterList = listOf(btc.name, usdc.name)

            // mint some USDC
            val usdcMintAmount = AssetAmount(usdc, "20")
            wallet.mintERC20AndMine(usdcMintAmount)

            assertEquals(wallet.getWalletBalance(usdc), usdcMintAmount)

            val walletStartingBtcBalance = wallet.getWalletBalance(btc)

            val btcDepositAmount = AssetAmount(btc, "0.001")
            val usdcDepositAmount = AssetAmount(usdc, "15")

            val btcDepositTxHash = wallet.sendDepositTx(btcDepositAmount)
            assertTrue(apiClient.listDeposits().deposits.none { it.symbol.value == btc.name })
            val pendingBtcDeposit = apiClient.createDeposit(CreateDepositApiRequest(Symbol(btc.name), btcDepositAmount.inFundamentalUnits, btcDepositTxHash)).deposit
            assertEquals(Deposit.Status.Pending, pendingBtcDeposit.status)

            assertEquals(listOf(pendingBtcDeposit), apiClient.listDeposits().deposits.filter { it.symbol.value == btc.name })

            // test idempotence
            assertEquals(pendingBtcDeposit, apiClient.createDeposit(CreateDepositApiRequest(Symbol(btc.name), btcDepositAmount.inFundamentalUnits, btcDepositTxHash)).deposit)
            wallet.currentEvmClient().mine(EvmDepositHandler.DEFAULT_NUM_CONFIRMATIONS)

            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(btcDepositAmount),
                ),
            )

            val btcDeposit = apiClient.getDeposit(pendingBtcDeposit.id).deposit
            assertEquals(Deposit.Status.Complete, btcDeposit.status)
            assertEquals(listOf(btcDeposit), apiClient.listDeposits().deposits.filter { it.symbol.value == btc.name })

            val depositGasCost = wallet.currentEvmClient().getTransactionReceipt(btcDepositTxHash).let { receipt ->
                assertNotNull(receipt)
                AssetAmount(btc, receipt.gasUsed * Numeric.decodeQuantity(receipt.effectiveGasPrice))
            }
            assertEquals(wallet.getWalletBalance(btc), walletStartingBtcBalance - btcDepositAmount - depositGasCost)

            // deposit some USDC
            val usdcDepositTxHash = wallet.sendDepositTx(usdcDepositAmount)

            assertTrue(apiClient.listDeposits().deposits.none { it.symbol.value == usdc.name })
            val pendingUsdcDeposit = apiClient.createDeposit(CreateDepositApiRequest(Symbol(usdc.name), usdcDepositAmount.inFundamentalUnits, usdcDepositTxHash)).deposit
            assertEquals(Deposit.Status.Pending, pendingBtcDeposit.status)

            assertEquals(listOf(pendingUsdcDeposit, btcDeposit), apiClient.listDeposits().deposits.filter { symbolFilterList.contains(it.symbol.value) })

            wallet.currentEvmClient().mine(EvmDepositHandler.DEFAULT_NUM_CONFIRMATIONS)

            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(btcDepositAmount),
                    ExpectedBalance(usdcDepositAmount),
                ),
            )

            val usdcDeposit = apiClient.getDeposit(pendingUsdcDeposit.id).deposit
            assertEquals(Deposit.Status.Complete, usdcDeposit.status)

            assertEquals(listOf(usdcDeposit, btcDeposit), apiClient.listDeposits().deposits.filter { symbolFilterList.contains(it.symbol.value) })

            assertEquals(wallet.getWalletBalance(usdc), usdcMintAmount - usdcDepositAmount)
        }
    }

    @Test
    fun `test deposits are scoped to user`() {
        val apiClient1 = TestApiClient()
        val wallet1 = Wallet(apiClient1)

        val apiClient2 = TestApiClient()
        val wallet2 = Wallet(apiClient2)

        Faucet.fundAndMine(wallet1.evmAddress, chainId = wallet1.currentChainId)
        Faucet.fundAndMine(wallet2.evmAddress, chainId = wallet2.currentChainId)

        val chain1 = apiClient1.getConfiguration().evmChains[0]
        val btc = chain1.symbols.first { it.name == "BTC:${chain1.id}" }

        val btcDeposit1Amount = AssetAmount(btc, "0.01")
        val btcDeposit2Amount = AssetAmount(btc, "0.02")

        val btcDeposit1TxHash = wallet1.sendNativeDepositTx(btcDeposit1Amount.inFundamentalUnits)
        val pendingBtcDeposit1 = apiClient1.createDeposit(CreateDepositApiRequest(Symbol(btc.name), btcDeposit1Amount.inFundamentalUnits, btcDeposit1TxHash)).deposit
        val btcDeposit2TxHash = wallet2.sendNativeDepositTx(btcDeposit2Amount.inFundamentalUnits)
        val pendingBtcDeposit2 = apiClient2.createDeposit(CreateDepositApiRequest(Symbol(btc.name), btcDeposit1Amount.inFundamentalUnits, btcDeposit2TxHash)).deposit
        assertEquals(listOf(pendingBtcDeposit1), apiClient1.listDeposits().deposits.filter { it.symbol.value == btc.name })
        assertEquals(listOf(pendingBtcDeposit2), apiClient2.listDeposits().deposits.filter { it.symbol.value == btc.name })
    }

    @Test
    fun `test on chain deposit detection`() {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)

        Faucet.fundAndMine(wallet.evmAddress, chainId = wallet.currentChainId)

        val chain = apiClient.getConfiguration().evmChains[0]
        val btc = chain.symbols.first { it.name == "BTC:${chain.id}" }
        val depositAmount = AssetAmount(btc, "0.01")

        val depositTxHash = TxHash(wallet.depositAndMine(depositAmount).transactionHash)

        await
            .withAlias("Waiting for deposit to be detected")
            .pollInSameThread()
            .pollDelay(Duration.ofMillis(100))
            .pollInterval(Duration.ofMillis(100))
            .atMost(Duration.ofMillis(10000L))
            .until {
                apiClient.listDeposits().deposits.firstOrNull {
                    it.txHash == depositTxHash && it.amount == depositAmount.inFundamentalUnits
                } != null
            }
    }

    @Test
    fun `test on chain deposit detection - forks are handled`() {
        // test is skipped in the test env
        Assumptions.assumeFalse(isTestEnvRun())

        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)

        Faucet.fundAndMine(wallet.evmAddress, chainId = wallet.currentChainId)

        val chain = apiClient.getConfiguration().evmChains[0]
        val btc = chain.symbols.first { it.name == "BTC:${chain.id}" }

        val chainClient = Faucet.evmClient(chain.id)
        val snapshotId = chainClient.snapshot().id

        val deposit1TxHash = wallet.sendNativeDepositTx(AssetAmount(btc, "0.01").inFundamentalUnits)
        chainClient.mine()

        await
            .withAlias("Waiting for deposit 1 to be detected")
            .pollInSameThread()
            .pollDelay(Duration.ofMillis(100))
            .pollInterval(Duration.ofMillis(100))
            .atMost(Duration.ofMillis(10000L))
            .until {
                apiClient.listDeposits().deposits.firstOrNull {
                    it.txHash == deposit1TxHash
                } != null
            }

        chainClient.revert(snapshotId)
        chainClient.mine()

        val deposit2TxHash = wallet.sendNativeDepositTx(AssetAmount(btc, "0.02").inFundamentalUnits)
        chainClient.mine(EvmDepositHandler.DEFAULT_NUM_CONFIRMATIONS)

        await
            .withAlias("Waiting for deposit 2 to be completed")
            .pollInSameThread()
            .pollDelay(Duration.ofMillis(100))
            .pollInterval(Duration.ofMillis(100))
            .atMost(Duration.ofMillis(10000L))
            .until {
                apiClient.listDeposits().deposits.firstOrNull {
                    it.txHash == deposit2TxHash && it.status == Deposit.Status.Complete
                } != null
            }

        // check that deposit 1 was marked as failed
        apiClient.listDeposits().deposits.first { it.txHash == deposit1TxHash }.also {
            assertEquals(Deposit.Status.Failed, it.status)
            assertEquals("Fork rollback", it.error)
        }
    }

    @Test
    fun `test deposit fails after timeout if no tx receipt found`() {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)

        Faucet.fundAndMine(wallet.evmAddress, chainId = wallet.currentChainId)

        val chain = apiClient.getConfiguration().evmChains[0]
        val btc = chain.symbols.first { it.name == "BTC:${chain.id}" }
        val amount = AssetAmount(btc, "0.01")

        val chainClient = Faucet.evmClient(chain.id)

        val depositTxHash = wallet.sendNativeDepositTx(amount.inFundamentalUnits)
        apiClient.createDeposit(CreateDepositApiRequest(Symbol(btc.name), amount.inFundamentalUnits, depositTxHash)).deposit.also {
            assertEquals(Deposit.Status.Pending, it.status)
        }
        chainClient.dropTransaction(depositTxHash)

        transaction {
            DepositEntity.findByTxHash(depositTxHash)!!.also {
                it.createdAt = Clock.System.now() - 11.minutes
            }
        }

        await
            .withAlias("Waiting for deposit to be marked as failed")
            .pollInSameThread()
            .pollDelay(Duration.ofMillis(100))
            .pollInterval(Duration.ofMillis(100))
            .atMost(Duration.ofMillis(10000L))
            .until {
                apiClient.listDeposits().deposits.firstOrNull {
                    it.txHash == depositTxHash && it.status == Deposit.Status.Failed
                } != null
            }

        assertEquals(depositTxHash, wallet.sendNativeDepositTx(amount.inFundamentalUnits))

        // verify that same deposit tx can be submitted again
        apiClient.createDeposit(CreateDepositApiRequest(Symbol(btc.name), amount.inFundamentalUnits, depositTxHash)).deposit.also {
            assertEquals(Deposit.Status.Pending, it.status)
        }
    }

    @Test
    fun `test on-chain deposit amount is the source of truth - EVM`() {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)
        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        Faucet.fundAndMine(wallet.evmAddress, chainId = wallet.currentChainId)

        val chain = apiClient.getConfiguration().evmChains[0]
        val nativeToken = chain.symbols.first { it.name == "BTC:${chain.id}" }
        val nativeOnChainDepositAmount = AssetAmount(nativeToken, "0.01")
        val nativeApiDepositAmount = AssetAmount(nativeToken, "0.02")

        val depositTxHash = wallet.sendDepositTx(nativeOnChainDepositAmount)

        val pendingDeposit = apiClient.createDeposit(CreateDepositApiRequest(Symbol(nativeToken.name), nativeApiDepositAmount.inFundamentalUnits, depositTxHash)).deposit
        assertEquals(Deposit.Status.Pending, pendingDeposit.status)
        assertAmount(nativeApiDepositAmount, pendingDeposit.amount)

        wallet.currentEvmClient().mine(EvmDepositHandler.DEFAULT_NUM_CONFIRMATIONS)

        waitForBalance(apiClient, wsClient, listOf(ExpectedBalance(nativeOnChainDepositAmount)))

        val nativeDeposit = apiClient.getDeposit(pendingDeposit.id).deposit
        assertEquals(Deposit.Status.Complete, nativeDeposit.status)
        assertAmount(nativeOnChainDepositAmount, nativeDeposit.amount)

        val erc20Token = chain.symbols.first { it.name == "USDC:${chain.id}" }
        val erc20OnChainDepositAmount = AssetAmount(erc20Token, "10")
        val erc20ApiDepositAmount = AssetAmount(erc20Token, "20")
        val mintTxHash = wallet.mintERC20AndMine(erc20OnChainDepositAmount).transactionHash.let(::TxHash)

        val erc20DepositTxHash = wallet.sendDepositTx(erc20OnChainDepositAmount)

        val pendingErc20Deposit = apiClient.createDeposit(CreateDepositApiRequest(Symbol(erc20Token.name), erc20ApiDepositAmount.inFundamentalUnits, erc20DepositTxHash)).deposit
        assertEquals(Deposit.Status.Pending, pendingErc20Deposit.status)
        assertAmount(erc20ApiDepositAmount, pendingErc20Deposit.amount)

        wallet.currentEvmClient().mine(EvmDepositHandler.DEFAULT_NUM_CONFIRMATIONS)

        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance(nativeOnChainDepositAmount),
                ExpectedBalance(erc20OnChainDepositAmount),
            ),
        )

        val erc20Deposit = apiClient.getDeposit(pendingErc20Deposit.id).deposit
        assertEquals(Deposit.Status.Complete, erc20Deposit.status)
        assertAmount(erc20OnChainDepositAmount, erc20Deposit.amount)

        // also check that deposit is marked as failed when submitted transaction does not contain a transfer to exchange contract
        val invalidTxHash = mintTxHash
        val pendingInvalidDeposit = apiClient.createDeposit(CreateDepositApiRequest(Symbol(erc20Token.name), erc20ApiDepositAmount.inFundamentalUnits, invalidTxHash)).deposit
        assertEquals(Deposit.Status.Pending, pendingInvalidDeposit.status)
        assertAmount(erc20ApiDepositAmount, pendingInvalidDeposit.amount)

        wallet.currentEvmClient().mine(EvmDepositHandler.DEFAULT_NUM_CONFIRMATIONS)

        waitFor {
            apiClient.getDeposit(pendingInvalidDeposit.id).deposit.status == Deposit.Status.Failed
        }
    }
}
