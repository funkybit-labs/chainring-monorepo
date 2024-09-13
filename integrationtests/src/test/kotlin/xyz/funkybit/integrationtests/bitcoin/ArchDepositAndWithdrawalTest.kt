package xyz.funkybit.integrationtests.bitcoin

import org.http4k.client.WebsocketClient
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import xyz.funkybit.apps.api.model.Deposit
import xyz.funkybit.apps.api.model.SymbolInfo
import xyz.funkybit.core.blockchain.bitcoin.BitcoinClient
import xyz.funkybit.core.blockchain.bitcoin.MempoolSpaceClient
import xyz.funkybit.core.model.bitcoin.ArchAccountState
import xyz.funkybit.core.model.db.ArchAccountBalanceIndexEntity
import xyz.funkybit.core.model.db.ArchAccountEntity
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.TxHash
import xyz.funkybit.core.model.db.WithdrawalEntity
import xyz.funkybit.core.model.db.WithdrawalId
import xyz.funkybit.core.model.db.WithdrawalStatus
import xyz.funkybit.core.utils.bitcoin.ArchUtils
import xyz.funkybit.integrationtests.bitcoin.ArchOnboardingTest.Companion.waitForProgramAccount
import xyz.funkybit.integrationtests.bitcoin.ArchOnboardingTest.Companion.waitForProgramStateAccount
import xyz.funkybit.integrationtests.bitcoin.ArchOnboardingTest.Companion.waitForTokenStateAccount
import xyz.funkybit.integrationtests.bitcoin.UtxoSelectionTest.Companion.waitForTx
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.testutils.isTestEnvRun
import xyz.funkybit.integrationtests.testutils.waitFor
import xyz.funkybit.integrationtests.testutils.waitForBalance
import xyz.funkybit.integrationtests.utils.AssetAmount
import xyz.funkybit.integrationtests.utils.BitcoinWallet
import xyz.funkybit.integrationtests.utils.ExpectedBalance
import xyz.funkybit.integrationtests.utils.TestApiClient
import xyz.funkybit.integrationtests.utils.assertBalancesMessageReceived
import xyz.funkybit.integrationtests.utils.blocking
import xyz.funkybit.integrationtests.utils.subscribeToBalances
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals

@ExtendWith(AppUnderTestRunner::class)
class ArchDepositAndWithdrawalTest {

    @BeforeEach
    fun waitForSetup() {
        Assumptions.assumeFalse(true)
        waitForProgramAccount()
        waitForProgramStateAccount()
        waitForTokenStateAccount()
    }

    @Test
    fun testArchDepositsAndWithdrawals() {
        Assumptions.assumeFalse(true)
        Assumptions.assumeFalse(isTestEnvRun())

        val apiClient = TestApiClient.withBitcoinWallet()
        val bitcoinWallet = BitcoinWallet(apiClient)
        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        val btc = bitcoinWallet.nativeSymbol

        val airdropAmount = BigInteger("12000")
        waitForTx(bitcoinWallet.walletAddress, bitcoinWallet.airdropNative(airdropAmount))

        assertEquals(bitcoinWallet.getWalletNativeBalance(), airdropAmount)

        // now deposit to the exchange
        val startingTotalDepositsAtExchange = MempoolSpaceClient.getBalance(bitcoinWallet.exchangeDepositAddress)
        val depositAmount = BigInteger("7000")
        val pendingBtcDeposit = bitcoinWallet.depositNative(depositAmount).deposit
        val depositTxId = TxHash(pendingBtcDeposit.txHash.value)
        waitForTx(bitcoinWallet.walletAddress, depositTxId)

        assertEquals(
            MempoolSpaceClient.getBalance(bitcoinWallet.exchangeDepositAddress),
            startingTotalDepositsAtExchange + depositAmount.toLong(),
        )

        val depositNetworkFee = BitcoinClient.getNetworkFeeForTx(depositTxId).toBigInteger()
        var expectedWalletBalance = airdropAmount - depositAmount - depositNetworkFee
        assertEquals(
            MempoolSpaceClient.getBalance(bitcoinWallet.walletAddress),
            expectedWalletBalance.toLong(),
        )

        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance(AssetAmount(bitcoinWallet.nativeSymbol, depositAmount)),
            ),
        )

        val btcDeposit = apiClient.getDeposit(pendingBtcDeposit.id).deposit
        Assertions.assertEquals(Deposit.Status.Complete, btcDeposit.status)
        Assertions.assertEquals(
            listOf(btcDeposit),
            apiClient.listDeposits().deposits.filter { it.symbol.value == btc.name },
        )

        val startingFeeAccountBalance = getFeeAccountBalanceOnArch(btc).balance
        val withdrawAmount = BigInteger("3000")
        val withdrawalApiRequest = bitcoinWallet.signWithdraw(btc.name, withdrawAmount)
        val pendingBtcWithdrawal = apiClient.createWithdrawal(withdrawalApiRequest).withdrawal

        Assertions.assertEquals(WithdrawalStatus.Pending, pendingBtcWithdrawal.status)
        Assertions.assertEquals(
            listOf(pendingBtcWithdrawal.id),
            apiClient.listWithdrawals().withdrawals.filter { it.symbol.value == btc.name }.map { it.id },
        )

        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance(
                    btc,
                    total = AssetAmount(bitcoinWallet.nativeSymbol, depositAmount),
                    available = AssetAmount(bitcoinWallet.nativeSymbol, depositAmount - withdrawAmount),
                ),
            ),
        )

        waitForCompletedWithdrawal(pendingBtcWithdrawal.id, WithdrawalStatus.Complete)

        val btcWithdrawal = apiClient.getWithdrawal(pendingBtcWithdrawal.id).withdrawal
        Assertions.assertEquals(WithdrawalStatus.Complete, btcWithdrawal.status)
        Assertions.assertEquals(
            listOf(btcWithdrawal.id),
            apiClient.listWithdrawals().withdrawals.filter { it.symbol.value == btc.name }.map { it.id },
        )
        Assertions.assertEquals(btc.withdrawalFee, btcWithdrawal.fee)

        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance(AssetAmount(bitcoinWallet.nativeSymbol, depositAmount - withdrawAmount)),
            ),
        )

        val withdrawal = apiClient.getWithdrawal(pendingBtcWithdrawal.id).withdrawal
        waitForTx(bitcoinWallet.walletAddress, TxHash(withdrawal.txHash!!.value))

        // verify the wallet and fee account balance at the exchange
        assertEquals(
            ArchAccountState.Balance(
                bitcoinWallet.walletAddress.value,
                (depositAmount - withdrawAmount).toLong().toULong(),
            ),
            getBalanceOnArch(bitcoinWallet, btc),
        )
        assertEquals(
            ArchAccountState.Balance(
                BitcoinClient.bitcoinConfig.feeCollectionAddress.value,
                startingFeeAccountBalance + withdrawal.fee.toLong().toULong(),
            ),
            getFeeAccountBalanceOnArch(btc),
        )

        // the wallets local balance should be change from the deposit plus the withdrawal amount - withdrawal fee
        expectedWalletBalance = expectedWalletBalance + withdrawAmount - withdrawal.fee
        assertEquals(
            MempoolSpaceClient.getBalance(bitcoinWallet.walletAddress),
            expectedWalletBalance.toLong(),
        )

        // verify total the program's balance
        val expectedProgramBalance =
            startingTotalDepositsAtExchange + depositAmount.toLong() - withdrawAmount.toLong() + withdrawal.fee.toLong() -
                BitcoinClient.getNetworkFeeForTx(TxHash(withdrawal.txHash!!.value))

        assertEquals(
            expectedProgramBalance,
            MempoolSpaceClient.getBalance(bitcoinWallet.exchangeDepositAddress),
        )

        // now do a 2nd deposit
        val deposit2Amount = BigInteger("2500")
        val pendingBtcDeposit2 = bitcoinWallet.depositNative(deposit2Amount).deposit
        val deposit2TxId = TxHash(pendingBtcDeposit2.txHash.value)
        waitForTx(bitcoinWallet.walletAddress, deposit2TxId)

        assertEquals(
            MempoolSpaceClient.getBalance(bitcoinWallet.exchangeDepositAddress),
            expectedProgramBalance + deposit2Amount.toLong(),
        )

        // wait for both exchange and available balances to be updated
        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance(AssetAmount(bitcoinWallet.nativeSymbol, depositAmount - withdrawAmount + deposit2Amount)),
            ),
        )

        // now verify that the onchain balance is as expected
        assertEquals(
            ArchAccountState.Balance(
                bitcoinWallet.walletAddress.value,
                (depositAmount - withdrawAmount + deposit2Amount).toLong().toULong(),
            ),
            getBalanceOnArch(bitcoinWallet, btc),
        )

        assertEquals(
            expectedProgramBalance + deposit2Amount.toLong(),
            MempoolSpaceClient.getBalance(bitcoinWallet.exchangeDepositAddress),
        )

        val deposit2NetworkFee = BitcoinClient.getNetworkFeeForTx(deposit2TxId).toBigInteger()
        expectedWalletBalance = expectedWalletBalance - deposit2Amount - deposit2NetworkFee
        assertEquals(
            MempoolSpaceClient.getBalance(bitcoinWallet.walletAddress),
            expectedWalletBalance.toLong(),
        )
    }

    private fun waitForCompletedWithdrawal(id: WithdrawalId, expectedStatus: WithdrawalStatus) {
        waitFor {
            transaction {
                WithdrawalEntity[id].status == expectedStatus
            }
        }
    }

    private fun getBalanceOnArch(bitcoinWallet: BitcoinWallet, symbol: SymbolInfo): ArchAccountState.Balance {
        return transaction {
            val symbolEntity = SymbolEntity.forName(symbol.name)
            val index = ArchAccountBalanceIndexEntity.findForWalletAddressAndSymbol(bitcoinWallet.walletAddress, symbolEntity)!!.addressIndex
            val tokenAccountPubKey = ArchAccountEntity.findTokenAccountForSymbol(symbolEntity)!!.rpcPubkey()
            val tokenState = ArchUtils.getAccountState<ArchAccountState.Token>(tokenAccountPubKey)
            tokenState.balances[index]
        }
    }

    private fun getFeeAccountBalanceOnArch(symbol: SymbolInfo): ArchAccountState.Balance {
        return transaction {
            val symbolEntity = SymbolEntity.forName(symbol.name)
            val tokenAccountPubKey = ArchAccountEntity.findTokenAccountForSymbol(symbolEntity)!!.rpcPubkey()
            val tokenState = ArchUtils.getAccountState<ArchAccountState.Token>(tokenAccountPubKey)
            tokenState.balances[0]
        }
    }
}
