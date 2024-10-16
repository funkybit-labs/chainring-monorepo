package xyz.funkybit.integrationtests.bitcoin

import kotlinx.datetime.Clock
import org.http4k.client.WebsocketClient
import org.http4k.websocket.WsClient
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import xyz.funkybit.apps.api.model.CreateDepositApiRequest
import xyz.funkybit.apps.api.model.Deposit
import xyz.funkybit.apps.api.model.SymbolInfo
import xyz.funkybit.apps.api.model.Withdrawal
import xyz.funkybit.core.blockchain.bitcoin.MempoolSpaceClient
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.db.ArchAccountEntity
import xyz.funkybit.core.model.db.ArchAccountStatus
import xyz.funkybit.core.model.db.ArchAccountTable
import xyz.funkybit.core.model.db.BitcoinUtxoEntity
import xyz.funkybit.core.model.db.DepositEntity
import xyz.funkybit.core.model.db.DepositStatus
import xyz.funkybit.core.model.db.DepositTable
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.WithdrawalEntity
import xyz.funkybit.core.model.db.WithdrawalId
import xyz.funkybit.core.model.db.WithdrawalStatus
import xyz.funkybit.core.model.db.WithdrawalTable
import xyz.funkybit.core.services.UtxoManager
import xyz.funkybit.core.utils.bitcoin.ArchUtils
import xyz.funkybit.core.utils.sum
import xyz.funkybit.integrationtests.bitcoin.ArchOnboardingTest.Companion.waitForProgramAccount
import xyz.funkybit.integrationtests.bitcoin.ArchOnboardingTest.Companion.waitForProgramStateAccount
import xyz.funkybit.integrationtests.bitcoin.ArchOnboardingTest.Companion.waitForTokenStateAccount
import xyz.funkybit.integrationtests.bitcoin.UtxoSelectionTest.Companion.validateUtxoAndMempoolBalances
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.testutils.getFeeAccountBalanceOnArch
import xyz.funkybit.integrationtests.testutils.getWalletBalanceOnArch
import xyz.funkybit.integrationtests.testutils.isBitcoinDisabled
import xyz.funkybit.integrationtests.testutils.waitFor
import xyz.funkybit.integrationtests.testutils.waitForBalance
import xyz.funkybit.integrationtests.testutils.waitForTx
import xyz.funkybit.integrationtests.utils.AssetAmount
import xyz.funkybit.integrationtests.utils.BitcoinWallet
import xyz.funkybit.integrationtests.utils.ExpectedBalance
import xyz.funkybit.integrationtests.utils.TestApiClient
import xyz.funkybit.integrationtests.utils.assertAmount
import xyz.funkybit.integrationtests.utils.assertBalancesMessageReceived
import xyz.funkybit.integrationtests.utils.blocking
import xyz.funkybit.integrationtests.utils.subscribeToBalances
import xyz.funkybit.utils.BitcoinMiner
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days

@ExtendWith(AppUnderTestRunner::class)
class ArchDepositAndWithdrawalTest {

    @BeforeEach
    fun waitForSetup() {
        Assumptions.assumeFalse(isBitcoinDisabled())
        waitForProgramAccount()
        waitForProgramStateAccount()
        waitForTokenStateAccount()
        ArchUtils.walletsPerTokenAccountThreshold = 100_000
        BitcoinMiner.resume()
    }

    @Test
    fun testArchDepositsAndWithdrawals() {
        Assumptions.assumeFalse(isBitcoinDisabled())

        val airdropAmount = BigInteger("15000")
        val depositAmount = BigInteger("7000")

        val (apiClient, bitcoinWallet, wsClient) = setupAndDepositToWallet(airdropAmount, depositAmount)
        val btc = bitcoinWallet.nativeSymbol
        val wallet1BalanceAfterDeposit = getBalance(bitcoinWallet.walletAddress).toBigInteger()

        val exchangeBalanceAfterDeposit = getBalance(bitcoinWallet.exchangeDepositAddress)

        val startingFeeAccountBalance = getFeeAccountBalanceOnArch(btc)
        val withdrawAmount = BigInteger("3000")

        val pendingBtcWithdrawal = initiateWithdrawal(apiClient, bitcoinWallet, wsClient, btc, depositAmount, withdrawAmount)
        waitForWithdrawal(apiClient, bitcoinWallet, wsClient, btc, pendingBtcWithdrawal, depositAmount, withdrawAmount)

        val withdrawal = apiClient.getWithdrawal(pendingBtcWithdrawal.id).withdrawal
        waitForTx(bitcoinWallet.walletAddress, TxHash(withdrawal.txHash!!.value), bitcoinWallet.exchangeDepositAddress)
        assertEquals(
            startingFeeAccountBalance.inFundamentalUnits + withdrawal.fee,
            getFeeAccountBalanceOnArch(btc).inFundamentalUnits,
        )

        // the wallets local balance should be change from the deposit plus the withdrawal amount - withdrawal fee
        var expectedWalletBalance = wallet1BalanceAfterDeposit + withdrawAmount - withdrawal.fee
        assertEquals(
            getBalance(bitcoinWallet.walletAddress),
            expectedWalletBalance.toLong(),
        )

        // verify total the program's balance
        val expectedProgramBalance =
            exchangeBalanceAfterDeposit - withdrawAmount.toLong() + withdrawal.fee.toLong() -
                MempoolSpaceClient.getNetworkFeeForTx(withdrawal.txHash!!)

        assertEquals(
            expectedProgramBalance,
            getBalance(bitcoinWallet.exchangeDepositAddress),
        )

        // now do a 2nd deposit
        val deposit2Amount = BigInteger("2500")
        val pendingBtcDeposit2 = bitcoinWallet.depositNative(deposit2Amount).deposit
        val deposit2TxId = pendingBtcDeposit2.txHash
        waitForTx(bitcoinWallet.exchangeDepositAddress, deposit2TxId, bitcoinWallet.walletAddress)

        assertEquals(
            getBalance(bitcoinWallet.exchangeDepositAddress),
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
            depositAmount - withdrawAmount + deposit2Amount,
            bitcoinWallet.getExchangeBalance(btc).inFundamentalUnits,
        )

        assertEquals(
            expectedProgramBalance + deposit2Amount.toLong(),
            getBalance(bitcoinWallet.exchangeDepositAddress),
        )

        val deposit2NetworkFee = MempoolSpaceClient.getNetworkFeeForTx(deposit2TxId).toBigInteger()
        expectedWalletBalance = expectedWalletBalance - deposit2Amount - deposit2NetworkFee
        assertEquals(
            getBalance(bitcoinWallet.walletAddress),
            expectedWalletBalance.toLong(),
        )

        validateUtxoAndMempoolBalances(bitcoinWallet.walletAddress)
        validateUtxoAndMempoolBalances(bitcoinWallet.exchangeDepositAddress, true)
    }

    @Test
    fun `testArchDepositsAndWithdrawals - multiple token accounts`() {
        Assumptions.assumeFalse(isBitcoinDisabled())
        transaction {
            ArchAccountTable.deleteWhere {
                symbolGuid.isNotNull() and status.eq(ArchAccountStatus.Full)
            }
        }

        val airdropAmount = BigInteger("15000")
        val depositAmount = BigInteger("7000")

        ArchUtils.walletsPerTokenAccountThreshold = 1

        val (apiClient, bitcoinWallet, wsClient) = setupAndDepositToWallet(airdropAmount, depositAmount)
        val btc = bitcoinWallet.nativeSymbol
        val wallet1BalanceAfterDeposit = getBalance(bitcoinWallet.walletAddress).toBigInteger()
        val exchangeBalanceAfterDeposit = getBalance(bitcoinWallet.exchangeDepositAddress)

        transaction {
            assertEquals(
                ArchAccountStatus.Full,
                ArchAccountEntity.findTokenAccountsForSymbol(SymbolEntity.forName(btc.name)).first().status,
            )
        }
        // wait for a new one to be created
        waitForTokenStateAccount()

        transaction {
            assertEquals(2, ArchAccountEntity.findTokenAccountsForSymbol(SymbolEntity.forName(btc.name)).size)
        }
        ArchUtils.walletsPerTokenAccountThreshold = 100_000

        val (apiClient2, bitcoinWallet2, wsClient2) = setupAndDepositToWallet(airdropAmount, depositAmount)
        val wallet2BalanceAfterDeposit = getBalance(bitcoinWallet2.walletAddress).toBigInteger()

        assertEquals(
            getBalance(bitcoinWallet.exchangeDepositAddress),
            exchangeBalanceAfterDeposit + depositAmount.toLong(),
        )

        val startingFeeAccountBalance = getFeeAccountBalanceOnArch(btc)
        val withdrawAmount = BigInteger("3000")

        val pendingBtcWithdrawal = initiateWithdrawal(apiClient, bitcoinWallet, wsClient, btc, depositAmount, withdrawAmount)
        val pendingBtcWithdrawal2 = initiateWithdrawal(apiClient2, bitcoinWallet2, wsClient2, btc, depositAmount, withdrawAmount)

        waitForWithdrawal(apiClient, bitcoinWallet, wsClient, btc, pendingBtcWithdrawal, depositAmount, withdrawAmount)
        waitForWithdrawal(apiClient2, bitcoinWallet2, wsClient2, btc, pendingBtcWithdrawal2, depositAmount, withdrawAmount)

        val withdrawal = apiClient.getWithdrawal(pendingBtcWithdrawal.id).withdrawal
        val withdrawal2 = apiClient2.getWithdrawal(pendingBtcWithdrawal2.id).withdrawal
        waitForTx(bitcoinWallet.walletAddress, TxHash(withdrawal2.txHash!!.value), bitcoinWallet.exchangeDepositAddress)
        // should be in same batch
        assertEquals(withdrawal.txHash, withdrawal2.txHash)
        assertEquals(
            startingFeeAccountBalance.inFundamentalUnits + withdrawal.fee + withdrawal2.fee,
            getFeeAccountBalanceOnArch(btc).inFundamentalUnits,
        )

        // the wallets local balance should be change from the deposit plus the withdrawal amount - withdrawal fee
        val expectedWallet1Balance = wallet1BalanceAfterDeposit + withdrawAmount - withdrawal.fee
        assertEquals(
            getBalance(bitcoinWallet.walletAddress),
            expectedWallet1Balance.toLong(),
        )
        val expectedWallet2Balance = wallet2BalanceAfterDeposit + withdrawAmount - withdrawal2.fee
        assertEquals(
            getBalance(bitcoinWallet2.walletAddress),
            expectedWallet2Balance.toLong(),
        )

        // verify total the program's balance
        val expectedProgramBalance =
            exchangeBalanceAfterDeposit + depositAmount.toLong() - withdrawAmount.toLong() * 2 + withdrawal.fee.toLong() + withdrawal2.fee.toLong() -
                MempoolSpaceClient.getNetworkFeeForTx(withdrawal.txHash!!)

        assertEquals(
            expectedProgramBalance,
            getBalance(bitcoinWallet.exchangeDepositAddress),
        )

        validateUtxoAndMempoolBalances(bitcoinWallet.walletAddress)
        validateUtxoAndMempoolBalances(bitcoinWallet.exchangeDepositAddress, true)
    }

    @Test
    fun `testArchDepositsAndWithdrawal - multiple wallets`() {
        Assumptions.assumeFalse(isBitcoinDisabled())

        val airdropAmount = BigInteger("15000")
        val depositAmount = BigInteger("7000")

        val bitcoinWallets = (0..24).map {
            BitcoinWallet(TestApiClient.withBitcoinWallet())
        }

        val btc = bitcoinWallets.last().nativeSymbol
        val txIds = bitcoinWallets.map { it.airdropNative(airdropAmount) }
        waitForTx(bitcoinWallets.last().walletAddress, txIds.last())
        bitcoinWallets.forEachIndexed { index, wallet ->
            waitForTx(wallet.walletAddress, txIds[index])
            assertEquals(wallet.getWalletNativeBalance(), airdropAmount)
        }
        val exchangeDepositAddress = bitcoinWallets.first().exchangeDepositAddress
        transaction { UtxoManager.refreshUtxos(exchangeDepositAddress) }
        val startingProgramBalance = getBalance(exchangeDepositAddress)

        val pendingBtcDeposits = bitcoinWallets.map { wallet ->
            val depositTxHash = wallet.sendNativeDepositTx(depositAmount)
            val pendingBtcDeposit = wallet.apiClient.createDeposit(
                CreateDepositApiRequest(
                    symbol = Symbol(btc.name),
                    amount = depositAmount,
                    txHash = depositTxHash,
                ),
            ).deposit

            assertEquals(Deposit.Status.Pending, pendingBtcDeposit.status)
            assertEquals(depositAmount, pendingBtcDeposit.amount)
            pendingBtcDeposit
        }

        waitFor(atMost = 40000) {
            transaction {
                DepositEntity.find { DepositTable.status.eq(DepositStatus.Complete) }.count().toInt() == pendingBtcDeposits.count()
            }
        }

        bitcoinWallets.forEach { wallet ->
            val btcBalance = wallet.apiClient.getBalances().balances.first { it.symbol.value == btc.name }
            assertEquals(depositAmount, btcBalance.available)
            assertEquals(depositAmount, btcBalance.total)
            assertEquals(depositAmount, wallet.getExchangeBalance(btc).inFundamentalUnits)
        }

        pendingBtcDeposits.forEach {
            waitForTx(exchangeDepositAddress, it.txHash)
        }

        var expectedProgramBalance = startingProgramBalance + depositAmount.toLong() * pendingBtcDeposits.size
        assertEquals(expectedProgramBalance, getBalance(exchangeDepositAddress))

        val withdrawAmount = BigInteger("3000")
        val pendingBtcWithdrawals = bitcoinWallets.map { wallet ->
            wallet.apiClient.createWithdrawal(wallet.signWithdraw(btc.name, withdrawAmount)).withdrawal
        }
        waitFor(atMost = 90000) {
            transaction {
                WithdrawalEntity.find { WithdrawalTable.status.eq(WithdrawalStatus.Complete) }.count().toInt() == pendingBtcWithdrawals.count()
            }
        }

        val txHashes = bitcoinWallets.mapIndexedNotNull { index, wallet ->
            wallet.apiClient.getWithdrawal(pendingBtcWithdrawals[index].id).withdrawal.txHash?.also {
                waitForTx(bitcoinWallets[index].walletAddress, it, exchangeDepositAddress)
            }
        }.toSet()

        val totalWithdrawAmount = bitcoinWallets.mapIndexed { index, wallet ->
            val withdrawal = wallet.apiClient.getWithdrawal(pendingBtcWithdrawals[index].id).withdrawal
            withdrawal.amount - withdrawal.fee
        }.sum().toLong()

        val expectedAmount = depositAmount - withdrawAmount
        bitcoinWallets.forEach { wallet ->
            val btcBalance = wallet.apiClient.getBalances().balances.first { it.symbol.value == btc.name }
            assertEquals(expectedAmount, btcBalance.available)
            assertEquals(expectedAmount, btcBalance.total)
            assertEquals(expectedAmount, wallet.getExchangeBalance(btc).inFundamentalUnits)
        }

        val totalNetworkFee = txHashes.sumOf { MempoolSpaceClient.getNetworkFeeForTx(it) }
        expectedProgramBalance -= totalNetworkFee + totalWithdrawAmount

        assertEquals(expectedProgramBalance, getBalance(exchangeDepositAddress))
    }

    @Test
    fun `test on-chain deposit amount is the source of truth - Bitcoin`() {
        Assumptions.assumeFalse(isBitcoinDisabled())

        val apiClient = TestApiClient.withBitcoinWallet()
        val bitcoinWallet = BitcoinWallet(apiClient)
        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        val btc = bitcoinWallet.nativeSymbol
        val airdropAmount = AssetAmount(btc, BigInteger("15000"))
        val onChainDepositAmount = AssetAmount(btc, BigInteger("7000"))
        val apiChainDepositAmount = AssetAmount(btc, BigInteger("14000"))

        val airdropTxHash = bitcoinWallet.airdropNative(airdropAmount.inFundamentalUnits)
        waitForTx(bitcoinWallet.walletAddress, airdropTxHash)
        assertAmount(airdropAmount, bitcoinWallet.getWalletNativeBalance())

        val depositTxHash = bitcoinWallet.sendNativeDepositTx(onChainDepositAmount.inFundamentalUnits)
        val pendingBtcDeposit = apiClient.createDeposit(
            CreateDepositApiRequest(
                symbol = Symbol(btc.name),
                amount = apiChainDepositAmount.inFundamentalUnits,
                txHash = depositTxHash,
            ),
        ).deposit

        assertEquals(Deposit.Status.Pending, pendingBtcDeposit.status)
        assertAmount(apiChainDepositAmount, pendingBtcDeposit.amount)

        waitForTx(bitcoinWallet.exchangeDepositAddress, pendingBtcDeposit.txHash)

        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance(onChainDepositAmount),
            ),
        )

        val btcDeposit = apiClient.getDeposit(pendingBtcDeposit.id).deposit
        Assertions.assertEquals(Deposit.Status.Complete, btcDeposit.status)
        assertAmount(onChainDepositAmount, btcDeposit.amount)

        // also check that deposit is marked as failed when submitted transaction does not contain a transfer to exchange program
        val pendingInvalidBtcDeposit = apiClient.createDeposit(
            CreateDepositApiRequest(
                symbol = Symbol(btc.name),
                amount = apiChainDepositAmount.inFundamentalUnits,
                txHash = airdropTxHash,
            ),
        ).deposit
        assertEquals(Deposit.Status.Pending, pendingInvalidBtcDeposit.status)

        waitFor {
            apiClient.getDeposit(pendingInvalidBtcDeposit.id).deposit.status == Deposit.Status.Failed
        }

        validateUtxoAndMempoolBalances(bitcoinWallet.exchangeDepositAddress, true)
    }

    @Test
    fun `test withdrawal rollback`() {
        Assumptions.assumeFalse(isBitcoinDisabled())

        val airdropAmount = BigInteger("15000")
        val depositAmount = BigInteger("7000")

        val (apiClient, bitcoinWallet, wsClient) = setupAndDepositToWallet(airdropAmount, depositAmount)
        val btc = bitcoinWallet.nativeSymbol
        val exchangeUtxoBalanceAfterDeposit = getBalance(bitcoinWallet.exchangeDepositAddress)

        val startingFeeAccountBalance = getFeeAccountBalanceOnArch(btc)

        val withdrawAmount = BigInteger("3000")

        // should pause bitcoin block mining
        BitcoinMiner.pause()
        val pendingBtcWithdrawal = initiateWithdrawal(apiClient, bitcoinWallet, wsClient, btc, depositAmount, withdrawAmount)
        // wait for bitcoin tx to be filled in
        waitFor {
            transaction { WithdrawalEntity[pendingBtcWithdrawal.id].blockchainTransactionGuid != null }
        }
        val withdrawal = apiClient.getWithdrawal(pendingBtcWithdrawal.id).withdrawal
        assertEquals(WithdrawalStatus.Settling, withdrawal.status)
        // check fee account and wallet balances on arch
        assertEquals(
            startingFeeAccountBalance.inFundamentalUnits + withdrawal.fee,
            getFeeAccountBalanceOnArch(btc).inFundamentalUnits,
        )
        assertEquals(
            depositAmount - withdrawAmount,
            getWalletBalanceOnArch(btc, bitcoinWallet.walletAddress).inFundamentalUnits,
        )

        // check balances over API - the total won't be adjusted till we have 1 confirm
        val btcBalances = apiClient.getBalances().balances.first { it.symbol.value == btc.name }
        assertEquals(depositAmount - withdrawAmount, btcBalances.available)
        assertEquals(depositAmount, btcBalances.total)

        // adjust the hash and move updated at back - simulates tx being evicted from mempool
        transaction {
            WithdrawalEntity[pendingBtcWithdrawal.id].let {
                it.blockchainTransaction!!.txHash = TxHash.emptyHashArch
                it.updatedAt = Clock.System.now().minus(3.days)
            }
        }
        // Wait for rollback tx to be created
        waitFor {
            transaction { WithdrawalEntity[pendingBtcWithdrawal.id].archRollbackTransactionGuid != null }
        }

        // wait for withdrawal to go to failed
        waitFor {
            apiClient.getWithdrawal(pendingBtcWithdrawal.id).withdrawal.status == WithdrawalStatus.Failed
        }

        // make sure on chain amounts are rolled back
        assertEquals(
            startingFeeAccountBalance.inFundamentalUnits,
            getFeeAccountBalanceOnArch(btc).inFundamentalUnits,
        )
        assertEquals(
            depositAmount,
            getWalletBalanceOnArch(btc, bitcoinWallet.walletAddress).inFundamentalUnits,
        )

        // and available balance in sequencer should be rolled back
        waitFor {
            apiClient.getBalances().balances.first { it.symbol.value == btc.name }.available == depositAmount
        }

        // also check utxos
        assertEquals(exchangeUtxoBalanceAfterDeposit, getBalance(bitcoinWallet.exchangeDepositAddress))

        BitcoinMiner.resume()
    }

    private fun setupAndDepositToWallet(airdropAmount: BigInteger, depositAmount: BigInteger): Triple<TestApiClient, BitcoinWallet, WsClient> {
        val apiClient = TestApiClient.withBitcoinWallet()
        val bitcoinWallet = BitcoinWallet(apiClient)
        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        val btc = bitcoinWallet.nativeSymbol
        waitForTx(bitcoinWallet.walletAddress, bitcoinWallet.airdropNative(airdropAmount))
        assertEquals(bitcoinWallet.getWalletNativeBalance(), airdropAmount)

        // now deposit to the exchange
        val pendingBtcDeposit = bitcoinWallet.depositNative(depositAmount).deposit
        val depositTxId = pendingBtcDeposit.txHash
        waitForTx(bitcoinWallet.exchangeDepositAddress, depositTxId, bitcoinWallet.walletAddress)

        val depositNetworkFee = MempoolSpaceClient.getNetworkFeeForTx(depositTxId).toBigInteger()
        val expectedWalletBalance = airdropAmount - depositAmount - depositNetworkFee
        assertEquals(
            getBalance(bitcoinWallet.walletAddress),
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

        return Triple(apiClient, bitcoinWallet, wsClient)
    }

    private fun initiateWithdrawal(apiClient: TestApiClient, bitcoinWallet: BitcoinWallet, wsClient: WsClient, btc: SymbolInfo, depositAmount: BigInteger, withdrawAmount: BigInteger): Withdrawal {
        return apiClient.createWithdrawal(bitcoinWallet.signWithdraw(btc.name, withdrawAmount)).withdrawal.also { pendingBtcWithdrawal ->
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
        }
    }

    private fun waitForWithdrawal(apiClient: TestApiClient, bitcoinWallet: BitcoinWallet, wsClient: WsClient, btc: SymbolInfo, pendingBtcWithdrawal: Withdrawal, depositAmount: BigInteger, withdrawAmount: BigInteger) {
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
        waitForTx(bitcoinWallet.walletAddress, withdrawal.txHash!!)

        // verify the wallet and fee account balance at the exchange
        assertEquals(
            depositAmount - withdrawAmount,
            bitcoinWallet.getExchangeBalance(btc).inFundamentalUnits,
        )
    }

    private fun waitForCompletedWithdrawal(id: WithdrawalId, expectedStatus: WithdrawalStatus) {
        waitFor {
            transaction {
                WithdrawalEntity[id].status == expectedStatus
            }
        }
    }

    private fun getBalance(address: BitcoinAddress): Long {
        return transaction {
            BitcoinUtxoEntity.findUnspentTotal(address).toLong()
        }
    }
}
