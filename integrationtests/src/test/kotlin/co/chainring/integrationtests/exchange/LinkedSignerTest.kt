package co.chainring.integrationtests.exchange

import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.WithdrawalStatus
import co.chainring.core.model.toEvmSignature
import co.chainring.core.utils.generateRandomBytes
import co.chainring.core.utils.toFundamentalUnits
import co.chainring.core.utils.toHex
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.testutils.OrderBaseTest
import co.chainring.integrationtests.testutils.waitFor
import co.chainring.integrationtests.testutils.waitForBalance
import co.chainring.integrationtests.testutils.waitForFinalizedWithdrawal
import co.chainring.integrationtests.utils.AssetAmount
import co.chainring.integrationtests.utils.ExpectedBalance
import co.chainring.integrationtests.utils.Faucet
import co.chainring.integrationtests.utils.TestApiClient
import co.chainring.integrationtests.utils.Wallet
import co.chainring.integrationtests.utils.assertBalancesMessageReceived
import co.chainring.integrationtests.utils.blocking
import co.chainring.integrationtests.utils.subscribeToBalances
import co.chainring.tasks.fixtures.toChainSymbol
import org.http4k.client.WebsocketClient
import org.junit.jupiter.api.extension.ExtendWith
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ExtendWith(AppUnderTestRunner::class)
class LinkedSignerTest : OrderBaseTest() {

    @Test
    fun `test linked signer`() {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)
        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        val config = apiClient.getConfiguration()
        assertEquals(config.chains.size, 2)

        val btc = config.chains[0].symbols.first { it.name == "BTC".toChainSymbol(config.chains[0].id) }
        val linkedSignerKeyPair = Keys.createEcKeyPair()
        val btcDepositAmount = AssetAmount(btc, "0.4")
        wallet.switchChain(config.chains[0].id)

        Faucet.fundAndMine(wallet.address, amount = BigDecimal("0.5").toFundamentalUnits(18), chainId = wallet.currentChainId)

        assertTrue(apiClient.tryListWithdrawals().isRight())
        // set a linked client in API but not on chain yet so backend/API does not know about it
        apiClient.linkedSignerEcKeyPair = linkedSignerKeyPair
        apiClient.switchChain(config.chains[0].id)
        // the api call should fail with a 401, since auth token signed with key not linked yet
        assertEquals(apiClient.tryListWithdrawals().leftOrNull()?.httpCode, 401)

        // put the key on chain, block processor should pick it and notify appropriate parties and auth should succeed.
        val digest = Hash.sha3(generateRandomBytes(32))
        val signature = Sign.signMessage(digest, linkedSignerKeyPair, false).let {
            (it.r + it.s + it.v).toHex().toEvmSignature()
        }
        val txReceipt = wallet.setLinkedSigner(Keys.getAddress(linkedSignerKeyPair), digest, signature)
        assertTrue(txReceipt.isStatusOK)
        // read the linked signer back from the contract and verify
        assertEquals(Keys.toChecksumAddress(Keys.getAddress(linkedSignerKeyPair)), wallet.getLinkedSigner(config.chains[0].id).value)
        waitFor(atMost = 20000) {
            apiClient.tryListWithdrawals().isRight() // should eventually succeed once linked signer tx is confirmed
        }

        wallet.depositAndMine(btcDepositAmount)
        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance(btcDepositAmount),
            ),
        )

        val btcWithdrawalAmount = AssetAmount(btc, "0.001")
        // invalid signature if we try to sign with signer not linked
        assertEquals(
            "Invalid signature",
            apiClient.tryCreateWithdrawal(
                wallet.signWithdraw(btc.name, btcWithdrawalAmount.inFundamentalUnits, linkedSignerEcKeyPair = Keys.createEcKeyPair()),
            ).leftOrNull()?.error?.displayMessage,
        )

        // used the linked signer and make sure it also settles on chain
        val pendingBtcWithdrawal = apiClient.createWithdrawal(wallet.signWithdraw(btc.name, btcWithdrawalAmount.inFundamentalUnits, linkedSignerEcKeyPair = linkedSignerKeyPair)).withdrawal
        assertEquals(WithdrawalStatus.Pending, pendingBtcWithdrawal.status)

        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance(
                    btc,
                    total = btcDepositAmount,
                    available = btcDepositAmount - btcWithdrawalAmount,
                ),
            ),
        )

        waitForFinalizedWithdrawal(pendingBtcWithdrawal.id)

        val btcWithdrawal = apiClient.getWithdrawal(pendingBtcWithdrawal.id).withdrawal
        assertEquals(WithdrawalStatus.Complete, btcWithdrawal.status)

        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance(btcDepositAmount - btcWithdrawalAmount),
            ),
        )

        // submit an order
        // should fail with a non-linked key
        assertEquals(
            "Invalid signature",
            apiClient.tryCreateLimitOrder(
                btcEthMarket,
                OrderSide.Buy,
                amount = BigDecimal("0.02"),
                price = BigDecimal("17"),
                wallet,
                linkedSignerEcKeyPair = Keys.createEcKeyPair(),
            ).leftOrNull()?.error?.displayMessage,
        )
        // should succeed if signed by wallet
        assertTrue(
            apiClient.tryCreateLimitOrder(
                btcEthMarket,
                OrderSide.Sell,
                amount = BigDecimal("0.02"),
                price = BigDecimal("17"),
                wallet,
            ).isRight(),
        )
        // should succeed if signed with the wallet's linked signer
        assertTrue(
            apiClient.tryCreateLimitOrder(
                btcEthMarket,
                OrderSide.Sell,
                amount = BigDecimal("0.02"),
                price = BigDecimal("17"),
                wallet,
                linkedSignerEcKeyPair = linkedSignerKeyPair,
            ).isRight(),
        )

        // now remove the linked signer and wait for api to start failing
        wallet.removeLinkedSigner()
        waitFor(atMost = 20000) {
            !apiClient.tryListWithdrawals().isRight()
        }
        // remove from api client and API call should pass now
        apiClient.linkedSignerEcKeyPair = null
        assertTrue(apiClient.tryListWithdrawals().isRight())

        // verify EIP712 payload cannot be signed with that key any more
        assertEquals(
            "Invalid signature",
            apiClient.tryCreateWithdrawal(
                wallet.signWithdraw(btc.name, btcWithdrawalAmount.inFundamentalUnits, linkedSignerEcKeyPair = linkedSignerKeyPair),
            ).leftOrNull()?.error?.displayMessage,
        )

        apiClient.cancelOpenOrders()
    }
}
