package xyz.funkybit.integrationtests.exchange

import org.http4k.client.WebsocketClient
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.extension.ExtendWith
import org.web3j.crypto.Hash
import org.web3j.crypto.Sign
import xyz.funkybit.core.model.db.OrderSide
import xyz.funkybit.core.model.db.WithdrawalStatus
import xyz.funkybit.core.model.toEvmSignature
import xyz.funkybit.core.utils.generateRandomBytes
import xyz.funkybit.core.utils.toFundamentalUnits
import xyz.funkybit.core.utils.toHex
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.testutils.OrderBaseTest
import xyz.funkybit.integrationtests.testutils.waitFor
import xyz.funkybit.integrationtests.testutils.waitForBalance
import xyz.funkybit.integrationtests.testutils.waitForFinalizedWithdrawal
import xyz.funkybit.integrationtests.utils.AssetAmount
import xyz.funkybit.integrationtests.utils.ExpectedBalance
import xyz.funkybit.integrationtests.utils.Faucet
import xyz.funkybit.integrationtests.utils.TestApiClient
import xyz.funkybit.integrationtests.utils.Wallet
import xyz.funkybit.integrationtests.utils.WalletKeyPair
import xyz.funkybit.integrationtests.utils.assertBalancesMessageReceived
import xyz.funkybit.integrationtests.utils.blocking
import xyz.funkybit.integrationtests.utils.subscribeToBalances
import xyz.funkybit.tasks.fixtures.toChainSymbol
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

        val chains = config.evmChains
        Assertions.assertEquals(chains.size, 2)

        val btc = chains[0].symbols.first { it.name == "BTC".toChainSymbol(chains[0].id) }
        val linkedSignerKeyPair = WalletKeyPair.EVM.generate()
        val linkedSignerAddress = linkedSignerKeyPair.address().canonicalize().toString()
        val btcDepositAmount = AssetAmount(btc, "0.4")
        wallet.switchChain(chains[0].id)

        Faucet.fundAndMine(wallet.evmAddress, amount = BigDecimal("0.5").toFundamentalUnits(18), chainId = wallet.currentChainId)

        assertTrue(apiClient.tryListWithdrawals().isRight())
        // set a linked client in API but not on chain yet so backend/API does not know about it
        apiClient.linkedSignerKeyPair = linkedSignerKeyPair
        apiClient.switchChain(chains[0].id)
        // the api call should fail with a 401, since auth token signed with key not linked yet
        assertEquals(apiClient.tryListWithdrawals().leftOrNull()?.httpCode, 401)

        // put the key on chain, block processor should pick it and notify appropriate parties and auth should succeed.
        val digest = Hash.sha3(generateRandomBytes(32))
        val signature = Sign.signMessage(digest, linkedSignerKeyPair.ecKeyPair, false).let {
            (it.r + it.s + it.v).toHex().toEvmSignature()
        }
        val txReceipt = wallet.setLinkedSigner(linkedSignerAddress, digest, signature)
        assertTrue(txReceipt.isStatusOK)
        // read the linked signer back from the contract and verify
        assertEquals(linkedSignerAddress, wallet.getLinkedSigner(chains[0].id).value)
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
                wallet.signWithdraw(btc.name, btcWithdrawalAmount.inFundamentalUnits, linkedSignerKeyPair = WalletKeyPair.EVM.generate()),
            ).leftOrNull()?.error?.displayMessage,
        )

        // used the linked signer and make sure it also settles on chain
        val pendingBtcWithdrawal = apiClient.createWithdrawal(wallet.signWithdraw(btc.name, btcWithdrawalAmount.inFundamentalUnits, linkedSignerKeyPair = linkedSignerKeyPair)).withdrawal
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

        waitForFinalizedWithdrawal(pendingBtcWithdrawal.id, WithdrawalStatus.Complete)

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
                linkedSignerKeyPair = WalletKeyPair.EVM.generate(),
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
                linkedSignerKeyPair = linkedSignerKeyPair,
            ).isRight(),
        )

        // now remove the linked signer and wait for api to start failing
        wallet.removeLinkedSigner()
        waitFor(atMost = 20000) {
            !apiClient.tryListWithdrawals().isRight()
        }
        // remove from api client and API call should pass now
        apiClient.linkedSignerKeyPair = null
        assertTrue(apiClient.tryListWithdrawals().isRight())

        // verify EIP712 payload cannot be signed with that key any more
        assertEquals(
            "Invalid signature",
            apiClient.tryCreateWithdrawal(
                wallet.signWithdraw(btc.name, btcWithdrawalAmount.inFundamentalUnits, linkedSignerKeyPair = linkedSignerKeyPair),
            ).leftOrNull()?.error?.displayMessage,
        )

        apiClient.cancelOpenOrders()
    }
}
