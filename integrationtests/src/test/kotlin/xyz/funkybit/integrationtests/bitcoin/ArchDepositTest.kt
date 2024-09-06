package xyz.funkybit.integrationtests.bitcoin

import org.http4k.client.WebsocketClient
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import xyz.funkybit.apps.api.model.Deposit
import xyz.funkybit.core.blockchain.bitcoin.MempoolSpaceClient
import xyz.funkybit.core.model.bitcoin.ArchAccountState
import xyz.funkybit.core.model.db.ArchAccountBalanceIndexEntity
import xyz.funkybit.core.model.db.ArchAccountEntity
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.TxHash
import xyz.funkybit.core.services.UtxoSelectionService
import xyz.funkybit.core.utils.bitcoin.ArchUtils
import xyz.funkybit.integrationtests.bitcoin.ArchOnboardingTest.Companion.waitForProgramAccount
import xyz.funkybit.integrationtests.bitcoin.ArchOnboardingTest.Companion.waitForProgramStateAccount
import xyz.funkybit.integrationtests.bitcoin.ArchOnboardingTest.Companion.waitForTokenStateAccount
import xyz.funkybit.integrationtests.bitcoin.UtxoSelectionTest.Companion.waitForTx
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.testutils.isTestEnvRun
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
class ArchDepositTest {

    @BeforeEach
    fun waitForSetup() {
        Assumptions.assumeFalse(true)
        waitForProgramAccount()
        waitForProgramStateAccount()
        waitForTokenStateAccount()
    }

    @Test
    fun testArchDeposits() {
        Assumptions.assumeFalse(true)
        Assumptions.assumeFalse(isTestEnvRun())

        val apiClient = TestApiClient.withBitcoinWallet()
        val bitcoinWallet = BitcoinWallet(apiClient)
        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        val config = apiClient.getConfiguration()
        val btc = bitcoinWallet.nativeSymbol

        val airdropAmount = BigInteger("6000")
        waitForTx(bitcoinWallet.walletAddress, bitcoinWallet.airdropNative(airdropAmount))

        assertEquals(bitcoinWallet.getWalletNativeBalance(), airdropAmount)

        // now deposit to the exchange
        val totalDepositsAtExchange = MempoolSpaceClient.getBalance(bitcoinWallet.exchangeDepositAddress)
        val depositAmount = BigInteger("2000")
        val pendingBtcDeposit = bitcoinWallet.depositNative(depositAmount).deposit
        val depositTxId = TxHash(pendingBtcDeposit.txHash.value)
        waitForTx(bitcoinWallet.walletAddress, depositTxId)

        assertEquals(
            MempoolSpaceClient.getBalance(bitcoinWallet.exchangeDepositAddress),
            totalDepositsAtExchange + depositAmount.toLong(),
        )
        val changeAmount = bitcoinWallet.getChangeAmount(depositTxId).toBigInteger()
        assertEquals(
            MempoolSpaceClient.getBalance(bitcoinWallet.walletAddress),
            changeAmount.toLong(),
        )

        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance(AssetAmount(config.bitcoinChain!!.symbols.first(), depositAmount)),
            ),
        )

        val btcDeposit = apiClient.getDeposit(pendingBtcDeposit.id).deposit
        Assertions.assertEquals(Deposit.Status.Complete, btcDeposit.status)
        Assertions.assertEquals(
            listOf(btcDeposit),
            apiClient.listDeposits().deposits.filter { it.symbol.value == btc.name },
        )

        // now do a 2nd deposit
        val fee = airdropAmount - depositAmount - changeAmount
        val deposit2Amount = changeAmount - fee - BigInteger("1000")
        val pendingBtcDeposit2 = bitcoinWallet.depositNative(deposit2Amount).deposit
        val deposit2TxId = TxHash(pendingBtcDeposit2.txHash.value)
        waitForTx(bitcoinWallet.walletAddress, deposit2TxId)

        assertEquals(
            MempoolSpaceClient.getBalance(bitcoinWallet.exchangeDepositAddress),
            totalDepositsAtExchange + depositAmount.toLong() + deposit2Amount.toLong(),
        )

        // wait for both exchange and available balances to be updated
        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance(AssetAmount(config.bitcoinChain.symbols.first(), depositAmount + deposit2Amount)),
            ),
        )

        // now verify that the onchain balance is as expected
        transaction {
            val symbolEntity = SymbolEntity.forName(btc.name)
            val index = ArchAccountBalanceIndexEntity.findForWalletAddressAndSymbol(bitcoinWallet.walletAddress, symbolEntity)!!.addressIndex
            val tokenAccountPubKey = ArchAccountEntity.findTokenAccountForSymbol(symbolEntity)!!.rpcPubkey()
            // read balances from on chain and verify address/balance at specified index are as expected
            val tokenState = ArchUtils.getAccountState<ArchAccountState.Token>(tokenAccountPubKey)
            assertEquals(
                ArchAccountState.Balance(
                    bitcoinWallet.walletAddress.value,
                    (depositAmount + deposit2Amount).toLong().toULong(),
                ),
                tokenState.balances[index],
            )
        }

        // verify the UTXOs sent to the program are unspent and values match and can be selected
        transaction {
            val unspentUtxos = UtxoSelectionService.refreshUnspentUtxos(bitcoinWallet.exchangeDepositAddress)
            assertEquals(
                depositAmount,
                unspentUtxos.first { it.utxoId.txId() == depositTxId }.amount,
            )
            assertEquals(
                deposit2Amount,
                unspentUtxos.first { it.utxoId.txId() == deposit2TxId }.amount,
            )
        }
    }
}
