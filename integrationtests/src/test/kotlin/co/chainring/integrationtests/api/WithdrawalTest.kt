package co.chainring.integrationtests.api

import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.ListWithdrawalsApiResponse
import co.chainring.apps.api.model.ReasonCode
import co.chainring.core.client.ws.blocking
import co.chainring.core.client.ws.subscribeToBalances
import co.chainring.core.evm.EIP712Transaction
import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.model.db.WithdrawalStatus
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.testutils.waitForBalance
import co.chainring.integrationtests.testutils.waitForFinalizedWithdrawal
import co.chainring.integrationtests.utils.AssetAmount
import co.chainring.integrationtests.utils.ExpectedBalance
import co.chainring.integrationtests.utils.Faucet
import co.chainring.integrationtests.utils.TestApiClient
import co.chainring.integrationtests.utils.Wallet
import co.chainring.integrationtests.utils.assertBalancesMessageReceived
import co.chainring.integrationtests.utils.assertError
import co.chainring.tasks.fixtures.toChainSymbol
import org.http4k.client.WebsocketClient
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.extension.ExtendWith
import org.web3j.utils.Numeric
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.Test

@ExtendWith(AppUnderTestRunner::class)
class WithdrawalTest {
    @Test
    fun `withdrawals multiple chains`() {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)
        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        val config = apiClient.getConfiguration()
        assertEquals(config.chains.size, 2)

        (0 until config.chains.size).forEach { index ->

            wallet.switchChain(config.chains[index].id)
            Faucet.fund(wallet.address, chainId = wallet.currentChainId)

            val btc = config.chains[index].symbols.first { it.name == "BTC".toChainSymbol(index) }
            val usdc = config.chains[index].symbols.first { it.name == "USDC".toChainSymbol(index) }
            val symbolFilterList = listOf(btc.name, usdc.name)

            // mint some USDC
            val usdcMintAmount = AssetAmount(usdc, "20")
            wallet.mintERC20(usdcMintAmount)

            assertEquals(wallet.getWalletBalance(usdc), usdcMintAmount)

            val walletStartingBtcBalance = wallet.getWalletBalance(btc)

            // deposit some BTC
            val btcDepositAmount = AssetAmount(btc, "0.001")
            val depositTxReceipt = wallet.deposit(btcDepositAmount)
            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(btcDepositAmount),
                ),
            )

            val depositGasCost = AssetAmount(btc, depositTxReceipt.gasUsed * Numeric.decodeQuantity(depositTxReceipt.effectiveGasPrice))
            assertEquals(walletStartingBtcBalance - btcDepositAmount - depositGasCost, wallet.getWalletBalance(btc))

            // deposit more BTC
            val depositTxReceipt2 = wallet.deposit(btcDepositAmount)
            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(btcDepositAmount * BigDecimal("2")),
                ),
            )

            val depositGasCost2 = AssetAmount(btc, depositTxReceipt2.gasUsed * Numeric.decodeQuantity(depositTxReceipt2.effectiveGasPrice))

            assertEquals(
                walletStartingBtcBalance - (btcDepositAmount * BigDecimal("2")) - depositGasCost - depositGasCost2,
                wallet.getWalletBalance(btc),
            )

            // deposit some USDC
            val usdcDepositAmount = AssetAmount(usdc, "15")
            wallet.deposit(usdcDepositAmount)
            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(btcDepositAmount * BigDecimal("2")),
                    ExpectedBalance(usdcDepositAmount),
                ),
            )
            assertEquals(usdcMintAmount - usdcDepositAmount, wallet.getWalletBalance(usdc))

            val walletBtcBalanceBeforeWithdrawals = wallet.getWalletBalance(btc)

            // withdraw some BTC
            val btcWithdrawalAmount = AssetAmount(btc, "0.001")

            val pendingBtcWithdrawal = apiClient.createWithdrawal(wallet.signWithdraw(btc.name, btcWithdrawalAmount.inFundamentalUnits)).withdrawal
            assertEquals(WithdrawalStatus.Pending, pendingBtcWithdrawal.status)
            assertEquals(listOf(pendingBtcWithdrawal), apiClient.listWithdrawals().withdrawals.filter { symbolFilterList.contains(it.symbol.value) })

            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(
                        btc,
                        total = btcDepositAmount * BigDecimal("2"),
                        available = btcDepositAmount * BigDecimal("2") - btcWithdrawalAmount,
                    ),
                    ExpectedBalance(usdcDepositAmount),
                ),
            )

            waitForFinalizedWithdrawal(pendingBtcWithdrawal.id)

            val btcWithdrawal = apiClient.getWithdrawal(pendingBtcWithdrawal.id).withdrawal
            assertEquals(WithdrawalStatus.Complete, btcWithdrawal.status)
            assertEquals(listOf(btcWithdrawal), apiClient.listWithdrawals().withdrawals.filter { symbolFilterList.contains(it.symbol.value) })

            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(btcDepositAmount * BigDecimal("2") - btcWithdrawalAmount),
                    ExpectedBalance(usdcDepositAmount),
                ),
            )
            assertEquals(
                walletBtcBalanceBeforeWithdrawals + btcWithdrawalAmount,
                wallet.getWalletBalance(btc),
            )

            // withdraw some USDC
            val usdcWithdrawalAmount = AssetAmount(usdc, "14")

            val pendingUsdcWithdrawal =
                apiClient.createWithdrawal(wallet.signWithdraw(usdc.name, usdcWithdrawalAmount.inFundamentalUnits)).withdrawal
            assertEquals(WithdrawalStatus.Pending, pendingUsdcWithdrawal.status)
            assertEquals(
                listOf(pendingUsdcWithdrawal, btcWithdrawal),
                apiClient.listWithdrawals().withdrawals.filter { symbolFilterList.contains(it.symbol.value) },
            )

            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(btcDepositAmount * BigDecimal("2") - btcWithdrawalAmount),
                    ExpectedBalance(
                        usdc,
                        total = usdcDepositAmount,
                        available = usdcDepositAmount - usdcWithdrawalAmount,
                    ),
                ),
            )

            waitForFinalizedWithdrawal(pendingUsdcWithdrawal.id)

            val usdcWithdrawal = apiClient.getWithdrawal(pendingUsdcWithdrawal.id).withdrawal
            assertEquals(WithdrawalStatus.Complete, usdcWithdrawal.status)
            assertEquals(listOf(usdcWithdrawal, btcWithdrawal), apiClient.listWithdrawals().withdrawals.filter { symbolFilterList.contains(it.symbol.value) })

            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(btcDepositAmount * BigDecimal("2") - btcWithdrawalAmount),
                    ExpectedBalance(usdcDepositAmount - usdcWithdrawalAmount),
                ),
            )
            assertEquals(
                usdcMintAmount - usdcDepositAmount + usdcWithdrawalAmount,
                wallet.getWalletBalance(usdc),
            )

            // when requested withdrawal amount > remaining amount, whatever is remaining is withdrawn
            val pendingUsdcWithdrawal2 = apiClient.createWithdrawal(
                wallet.signWithdraw(
                    usdc.name,
                    (usdcDepositAmount - usdcWithdrawalAmount).inFundamentalUnits + BigInteger.ONE,
                ),
            ).withdrawal
            assertEquals(WithdrawalStatus.Pending, pendingUsdcWithdrawal2.status)

            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(btcDepositAmount * BigDecimal("2") - btcWithdrawalAmount),
                    ExpectedBalance(
                        usdc,
                        total = usdcDepositAmount - usdcWithdrawalAmount,
                        available = AssetAmount(usdc, "0"),
                    ),
                ),
            )

            waitForFinalizedWithdrawal(pendingUsdcWithdrawal2.id)

            val usdcWithdrawal2 = apiClient.getWithdrawal(pendingUsdcWithdrawal2.id).withdrawal
            assertEquals(WithdrawalStatus.Complete, usdcWithdrawal2.status)

            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(btcDepositAmount * BigDecimal("2") - btcWithdrawalAmount),
                    ExpectedBalance(AssetAmount(usdc, "0")),
                ),
            )
        }
    }

    @Test
    fun `withdrawal errors`() {
        val apiClient = TestApiClient()
        val usdc = apiClient.getConfiguration().chains.flatMap { it.symbols }.first { it.name == "USDC" }

        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        val wallet = Wallet(apiClient)
        Faucet.fund(wallet.address)

        val amount = AssetAmount(usdc, "1000")
        wallet.mintERC20(amount * BigDecimal("2"))

        wallet.deposit(amount)
        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance(amount),
            ),
        )

        // invalid signature
        apiClient.tryCreateWithdrawal(
            wallet.signWithdraw(usdc.name, amount.inFundamentalUnits).copy(amount = BigInteger.TWO),
        ).assertError(
            ApiError(ReasonCode.SignatureNotValid, "Invalid signature"),
        )
    }

    @Test
    fun `test withdrawals are scoped to wallet`() {
        val apiClient1 = TestApiClient()
        val wallet1 = Wallet(apiClient1)
        val wsClient1 = WebsocketClient.blocking(apiClient1.authToken)
        wsClient1.subscribeToBalances()
        wsClient1.assertBalancesMessageReceived()

        val apiClient2 = TestApiClient()
        val wallet2 = Wallet(apiClient2)
        val wsClient2 = WebsocketClient.blocking(apiClient2.authToken)
        wsClient2.subscribeToBalances()
        wsClient2.assertBalancesMessageReceived()

        Faucet.fund(wallet1.address, chainId = wallet1.currentChainId)
        Faucet.fund(wallet2.address, chainId = wallet2.currentChainId)

        val btcSymbol = "BTC".toChainSymbol(0)

        val btcDeposit1Amount = wallet1.formatAmount("0.01", btcSymbol)
        val btcDeposit2Amount = wallet2.formatAmount("0.02", btcSymbol)

        wallet1.depositNative(btcDeposit1Amount)
        wallet2.depositNative(btcDeposit2Amount)

        waitForBalance(
            apiClient1,
            wsClient1,
            listOf(
                ExpectedBalance(btcSymbol, total = btcDeposit1Amount, available = btcDeposit1Amount),
            ),
        )
        waitForBalance(
            apiClient2,
            wsClient2,
            listOf(
                ExpectedBalance(btcSymbol, total = btcDeposit2Amount, available = btcDeposit2Amount),
            ),
        )
        val btcWithdrawal1Amount = wallet1.formatAmount("0.001", btcSymbol)
        val btcWithdrawal2Amount = wallet1.formatAmount("0.002", btcSymbol)

        val pendingBtcWithdrawal1 =
            apiClient1.createWithdrawal(wallet1.signWithdraw(btcSymbol, btcWithdrawal1Amount)).withdrawal
        val pendingBtcWithdrawal2 =
            apiClient2.createWithdrawal(wallet2.signWithdraw(btcSymbol, btcWithdrawal2Amount)).withdrawal
        assertEquals(listOf(pendingBtcWithdrawal1), apiClient1.listWithdrawals().withdrawals.filter { it.symbol.value == btcSymbol })
        assertEquals(listOf(pendingBtcWithdrawal2), apiClient2.listWithdrawals().withdrawals.filter { it.symbol.value == btcSymbol })
    }

    @Test
    fun `withdrawal blockchain failure`() {
        val apiClient = TestApiClient()
        val btc = apiClient.getConfiguration().chains.flatMap { it.symbols }.first { it.name == "BTC" }

        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        val wallet = Wallet(apiClient)
        Faucet.fund(wallet.address)

        // deposit some BTC
        val btcDepositAmount = AssetAmount(btc, "0.002")
        wallet.deposit(btcDepositAmount)
        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance(btcDepositAmount),
            ),
        )

        // withdraw some BTC
        val btcWithdrawalAmount = AssetAmount(btc, "0.001")

        val pendingBtcWithdrawal = apiClient.createWithdrawal(wallet.signWithdraw(btc.name, btcWithdrawalAmount.inFundamentalUnits)).withdrawal
        assertEquals(WithdrawalStatus.Pending, pendingBtcWithdrawal.status)
        assertEquals(ListWithdrawalsApiResponse(listOf(pendingBtcWithdrawal)), apiClient.listWithdrawals())

        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance(btc, total = btcDepositAmount, available = btcDepositAmount - btcWithdrawalAmount),
            ),
        )

        waitForFinalizedWithdrawal(pendingBtcWithdrawal.id)

        val btcWithdrawal = apiClient.getWithdrawal(pendingBtcWithdrawal.id).withdrawal
        assertEquals(WithdrawalStatus.Complete, btcWithdrawal.status)
        assertEquals(ListWithdrawalsApiResponse(listOf(btcWithdrawal)), apiClient.listWithdrawals())

        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance(btcDepositAmount - btcWithdrawalAmount),
            ),
        )

        // the only reason withdrawals should fail is a signature failure (which should not happen since
        // Exchange API service verifies signatures before sending on chain). To simulate this
        // and verify the sequencer rollbacks, we will modify the withdrawal signature of an already submitted tx and
        // resubmit it on chain - this should fail and trigger the rollback in the sequencer and
        // available balance should go back to what it was before.
        transaction {
            val withdrawal = WithdrawalEntity[pendingBtcWithdrawal.id]
            withdrawal.status = WithdrawalStatus.Sequenced
            // resign with a different nonce so signature should fail
            val signature = wallet.signWithdraw(btc.name, btcWithdrawalAmount.inFundamentalUnits).signature
            withdrawal.transactionData = (withdrawal.transactionData!! as EIP712Transaction.WithdrawTx).copy(
                signature = signature,
            )
        }

        waitForFinalizedWithdrawal(pendingBtcWithdrawal.id)

        val btcWithdrawal2 = apiClient.getWithdrawal(pendingBtcWithdrawal.id).withdrawal
        assertEquals(WithdrawalStatus.Failed, btcWithdrawal2.status)
        assertEquals("Invalid Signature", btcWithdrawal2.error)

        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance(btc, total = btcDepositAmount - btcWithdrawalAmount, available = btcDepositAmount),
            ),
        )
    }
}
