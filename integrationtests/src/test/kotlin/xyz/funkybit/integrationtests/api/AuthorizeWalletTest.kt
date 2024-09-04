package xyz.funkybit.integrationtests.api

import kotlinx.datetime.Clock
import org.bitcoinj.core.ECKey
import org.http4k.client.WebsocketClient
import org.junit.jupiter.api.extension.ExtendWith
import org.web3j.crypto.ECKeyPair
import xyz.funkybit.apps.api.model.ApiError
import xyz.funkybit.apps.api.model.Market
import xyz.funkybit.apps.api.model.ReasonCode
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.OrderSide
import xyz.funkybit.core.model.db.OrderStatus
import xyz.funkybit.core.model.db.WithdrawalStatus
import xyz.funkybit.core.utils.setScale
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.testutils.OrderBaseTest
import xyz.funkybit.integrationtests.testutils.waitForFinalizedWithdrawal
import xyz.funkybit.integrationtests.utils.AssetAmount
import xyz.funkybit.integrationtests.utils.TestApiClient
import xyz.funkybit.integrationtests.utils.WalletKeyPair
import xyz.funkybit.integrationtests.utils.assertAmount
import xyz.funkybit.integrationtests.utils.assertBalancesMessageReceived
import xyz.funkybit.integrationtests.utils.assertError
import xyz.funkybit.integrationtests.utils.assertMyLimitOrderCreatedMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyMarketOrderCreatedMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyOrderUpdatedMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyOrdersMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyTradesCreatedMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyTradesMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyTradesUpdatedMessageReceived
import xyz.funkybit.integrationtests.utils.blocking
import xyz.funkybit.integrationtests.utils.signAuthorizeBitcoinWalletRequest
import xyz.funkybit.integrationtests.utils.signAuthorizeEvmWalletRequest
import xyz.funkybit.integrationtests.utils.subscribeToBalances
import xyz.funkybit.integrationtests.utils.subscribeToMyOrders
import xyz.funkybit.integrationtests.utils.subscribeToMyTrades
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@ExtendWith(AppUnderTestRunner::class)
class AuthorizeWalletTest : OrderBaseTest() {

    @Test
    fun `evm wallet authorize bitcoin wallet`() {
        val evmKeyApiClient = TestApiClient.withEvmWallet()
        assertTrue { evmKeyApiClient.getAccountConfiguration().authorizedAddress.isEmpty() }

        val bitcoinKeyApiClient = TestApiClient.withBitcoinWallet()
        bitcoinKeyApiClient.authorizeWallet(
            apiRequest = signAuthorizeBitcoinWalletRequest(
                ecKeyPair = evmKeyApiClient.keyPair.asEcKeyPair(),
                address = evmKeyApiClient.address.asEvmAddress(),
                authorizedAddress = bitcoinKeyApiClient.address.asBitcoinAddress(),
            ),
        )

        assertEquals(listOf(evmKeyApiClient.address), bitcoinKeyApiClient.getAccountConfiguration().authorizedAddress)
        assertEquals(listOf(bitcoinKeyApiClient.address), evmKeyApiClient.getAccountConfiguration().authorizedAddress)
    }

    @Test
    fun `bitcoin wallet authorize evm wallet`() {
        val bitcoinKeyApiClient = TestApiClient.withBitcoinWallet()
        assertTrue { bitcoinKeyApiClient.getAccountConfiguration().authorizedAddress.isEmpty() }

        val evmKeyApiClient = TestApiClient.withEvmWallet()
        evmKeyApiClient.authorizeWallet(
            apiRequest = signAuthorizeEvmWalletRequest(
                ecKey = bitcoinKeyApiClient.keyPair.asECKey(),
                address = bitcoinKeyApiClient.address.asBitcoinAddress(),
                authorizedAddress = evmKeyApiClient.address.asEvmAddress(),
            ),
        )

        assertEquals(listOf(evmKeyApiClient.address.asEvmAddress()), bitcoinKeyApiClient.getAccountConfiguration().authorizedAddress)
        assertEquals(listOf(bitcoinKeyApiClient.address.asBitcoinAddress()), evmKeyApiClient.getAccountConfiguration().authorizedAddress)
    }

    @Test
    fun `already used address can't be authorized`() {
        val evmKeyApiClient = TestApiClient.withEvmWallet()
        assertTrue { evmKeyApiClient.getAccountConfiguration().authorizedAddress.isEmpty() }

        val bitcoinKeyApiClient = TestApiClient.withBitcoinWallet()
        assertTrue { bitcoinKeyApiClient.getAccountConfiguration().authorizedAddress.isEmpty() }

        // note: each wallet has been linked to a new user by sending an api request

        bitcoinKeyApiClient.tryAuthorizeWallet(
            apiRequest = signAuthorizeBitcoinWalletRequest(
                ecKeyPair = evmKeyApiClient.keyPair.asEcKeyPair(),
                address = evmKeyApiClient.address.asEvmAddress(),
                authorizedAddress = bitcoinKeyApiClient.address.asBitcoinAddress(),
            ),
        ).assertError(ApiError(ReasonCode.AuthorizeWallerError, "Authorized wallet address is already in use"))

        evmKeyApiClient.tryAuthorizeWallet(
            apiRequest = signAuthorizeEvmWalletRequest(
                ecKey = bitcoinKeyApiClient.keyPair.asECKey(),
                address = bitcoinKeyApiClient.address.asBitcoinAddress(),
                authorizedAddress = evmKeyApiClient.address.asEvmAddress(),
            ),
        ).assertError(ApiError(ReasonCode.AuthorizeWallerError, "Authorized wallet address is already in use"))
    }

    @Test
    fun `authorization error cases`() {
        val evmKeyApiClient = TestApiClient.withEvmWallet()
        assertTrue { evmKeyApiClient.getAccountConfiguration().authorizedAddress.isEmpty() }

        val bitcoinKeyApiClient = TestApiClient.withBitcoinWallet()

        // signature should be valid
        evmKeyApiClient.tryAuthorizeWallet(
            apiRequest = signAuthorizeEvmWalletRequest(
                ecKey = bitcoinKeyApiClient.keyPair.asECKey(),
                address = bitcoinKeyApiClient.address.asBitcoinAddress(),
                authorizedAddress = evmKeyApiClient.address.asEvmAddress(),
            ).copy(signature = "H7P1/r+gULX05tXwaJGfglZSL4sRhykAsgwQtpm92xRIPaGUnxQAhm1CZsTuQ8wh3w51f1uUVpxU2RUfJ3hq81I="),
        ).assertError(ApiError(ReasonCode.AuthorizeWallerError, "Authorization signature can't be verified"))
        bitcoinKeyApiClient.tryAuthorizeWallet(
            apiRequest = signAuthorizeBitcoinWalletRequest(
                ecKeyPair = evmKeyApiClient.keyPair.asEcKeyPair(),
                address = evmKeyApiClient.address.asEvmAddress(),
                authorizedAddress = bitcoinKeyApiClient.address.asBitcoinAddress(),
            ).copy(signature = "0x1cd66f580ec8f6fd37b2101849955c5a50d787092fe8a97e5c55bea6a24c1d47409e17450adaa617cc07907f56a4eb1f468467fcefe7808cbd63fbba935ee0201b"),
        ).assertError(ApiError(ReasonCode.AuthorizeWallerError, "Authorization signature can't be verified"))

        // authorization signature timestamp should be recent
        evmKeyApiClient.tryAuthorizeWallet(
            apiRequest = signAuthorizeEvmWalletRequest(
                ecKey = bitcoinKeyApiClient.keyPair.asECKey(),
                address = bitcoinKeyApiClient.address.asBitcoinAddress(),
                authorizedAddress = evmKeyApiClient.address.asEvmAddress(),
                timestamp = Clock.System.now() + 11.seconds,
            ),
        ).assertError(ApiError(ReasonCode.AuthorizeWallerError, "Authorization has expired or not valid yet"))
        evmKeyApiClient.tryAuthorizeWallet(
            apiRequest = signAuthorizeEvmWalletRequest(
                ecKey = bitcoinKeyApiClient.keyPair.asECKey(),
                address = bitcoinKeyApiClient.address.asBitcoinAddress(),
                authorizedAddress = evmKeyApiClient.address.asEvmAddress(),
                timestamp = Clock.System.now() - 5.minutes - 1.seconds,
            ),
        ).assertError(ApiError(ReasonCode.AuthorizeWallerError, "Authorization has expired or not valid yet"))

        // request principal should match authorized wallet
        evmKeyApiClient.tryAuthorizeWallet(
            apiRequest = signAuthorizeEvmWalletRequest(
                ecKey = bitcoinKeyApiClient.keyPair.asECKey(),
                address = bitcoinKeyApiClient.address.asBitcoinAddress(),
                authorizedAddress = EvmAddress.generate(),
            ),
        ).assertError(ApiError(ReasonCode.AuthorizeWallerError, "Invalid authorization"))
        bitcoinKeyApiClient.tryAuthorizeWallet(
            apiRequest = signAuthorizeBitcoinWalletRequest(
                ecKeyPair = evmKeyApiClient.keyPair.asEcKeyPair(),
                address = evmKeyApiClient.address.asEvmAddress(),
                authorizedAddress = WalletKeyPair.Bitcoin.generate().address(),
            ),
        ).assertError(ApiError(ReasonCode.AuthorizeWallerError, "Invalid authorization"))
    }

    @Test
    fun `client signed-in with Bitcoin wallet can see the same balances, deposits, withdrawals, orders and trades as when signed-in with EVM wallet`() {
        val market = btcEthMarket

        setupMakerAndPlaceLimitSellOrder(market, amount = BigDecimal("0.2"), price = BigDecimal("17.500"))
        val takerApiClientEvm = setupTakerPlaceMarketBuyOrderAndWithdraw(market, ethDepositAmount = BigDecimal("2"), btcBuyAmount = BigDecimal("0.1"))

        val takerBalances = takerApiClientEvm.getBalances().let {
            assertEquals(2, it.balances.size)

            assertEquals(btc.name, it.balances[0].symbol.value)
            assertAmount(AssetAmount(btc, "0.1"), it.balances[0].available)

            assertEquals(eth.name, it.balances[1].symbol.value)
            assertAmount(AssetAmount(eth, "0.215"), it.balances[1].available)

            it.balances
        }

        val takerDeposits = takerApiClientEvm.listDeposits().let {
            assertEquals(2, it.deposits.size)

            assertEquals(eth.name, it.deposits[0].symbol.value)
            assertAmount(AssetAmount(eth, "2"), it.deposits[0].amount)

            assertEquals(btc.name, it.deposits[1].symbol.value)
            assertAmount(AssetAmount(btc, "0.1"), it.deposits[1].amount)

            it.deposits
        }

        val takerWithdrawals = takerApiClientEvm.listWithdrawals().let {
            assertEquals(1, it.withdrawals.size)

            assertEquals(btc.name, it.withdrawals[0].symbol.value)
            assertAmount(AssetAmount(btc, "0.1"), it.withdrawals[0].amount)
            assertEquals(WithdrawalStatus.Complete, it.withdrawals[0].status)

            it.withdrawals
        }

        val takerOrders = takerApiClientEvm.listOrders().let {
            assertEquals(1, it.orders.size)

            assertEquals(market.id, it.orders[0].marketId)
            assertEquals(OrderSide.Buy, it.orders[0].side)
            assertAmount(AssetAmount(btc, "0.1"), it.orders[0].amount)
            assertEquals(OrderStatus.Filled, it.orders[0].status)

            it.orders
        }

        // authorize bitcoin wallet
        val takerBitcoinKeyPair = WalletKeyPair.Bitcoin(ECKey())
        val takerApiClientBitcoin = TestApiClient(takerBitcoinKeyPair, chainId = ChainId(0u))
        takerApiClientBitcoin.authorizeBitcoinWallet(takerApiClientEvm.keyPair as WalletKeyPair.EVM)

        // check that user sees same balances, deposits, withdrawals, orders and trades
        assertEquals(takerBalances, takerApiClientBitcoin.getBalances().balances)
        assertEquals(takerDeposits, takerApiClientBitcoin.listDeposits().deposits)
        assertEquals(takerWithdrawals, takerApiClientBitcoin.listWithdrawals().withdrawals)
        assertEquals(takerOrders, takerApiClientBitcoin.listOrders().orders)

        WebsocketClient.blocking(takerApiClientBitcoin.authToken).apply {
            subscribeToMyOrders()
            assertMyOrdersMessageReceived { msg ->
                assertEquals(takerOrders, msg.orders)
            }

            subscribeToMyTrades()
            assertMyTradesMessageReceived { msg ->
                assertEquals(takerOrders[0].id, msg.trades[0].orderId)
            }

            subscribeToBalances()
            assertBalancesMessageReceived { msg ->
                assertEquals(takerBalances, msg.balances)
            }

            close()
        }
    }

    private fun setupMakerAndPlaceLimitSellOrder(market: Market, amount: BigDecimal, price: BigDecimal) {
        val (apiClient, wallet, wsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(btc, amount * BigDecimal(4)),
            ),
            deposits = listOf(
                AssetAmount(btc, amount * BigDecimal(2)),
            ),
            subscribeToOrderBook = false,
            subscribeToLimits = false,
            subscribeToPrices = false,
        )

        val limitBuyOrderApiResponse = apiClient.createLimitOrder(market, OrderSide.Sell, amount, price, wallet)

        wsClient.assertMyLimitOrderCreatedMessageReceived(limitBuyOrderApiResponse)
        wsClient.close()
    }

    private fun setupTakerPlaceMarketBuyOrderAndWithdraw(market: Market, ethDepositAmount: BigDecimal, btcBuyAmount: BigDecimal): TestApiClient {
        val (apiClient, wallet, wsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(btc, "0.5"),
                AssetAmount(eth, ethDepositAmount),
            ),
            deposits = listOf(
                AssetAmount(btc, "0.1"),
                AssetAmount(eth, ethDepositAmount),
            ),
            subscribeToOrderBook = false,
            subscribeToLimits = false,
            subscribeToPrices = false,
        )

        val marketBuyOrderApiResponse = apiClient.createMarketOrder(market, OrderSide.Buy, btcBuyAmount, wallet)

        wsClient.apply {
            assertMyMarketOrderCreatedMessageReceived(marketBuyOrderApiResponse)
            assertMyTradesCreatedMessageReceived {}
            assertMyOrderUpdatedMessageReceived {}
            assertBalancesMessageReceived {}
        }

        val trade = getTradesForOrders(listOf(marketBuyOrderApiResponse.orderId)).first().also {
            assertAmount(AssetAmount(btc, btcBuyAmount.setScale(btc.decimals)), it.amount)
            assertEquals(apiClient.getOrder(marketBuyOrderApiResponse.orderId).executions.first().tradeId, it.id.value)
        }

        waitForSettlementToFinish(listOf(trade.id.value))
        wsClient.assertBalancesMessageReceived()
        wsClient.assertMyTradesUpdatedMessageReceived {}

        val btcWithdrawalAmount = AssetAmount(btc, btcBuyAmount)

        val pendingBtcWithdrawal = apiClient.createWithdrawal(wallet.signWithdraw(btc.name, btcWithdrawalAmount.inFundamentalUnits)).withdrawal
        assertEquals(WithdrawalStatus.Pending, pendingBtcWithdrawal.status)

        wsClient.assertBalancesMessageReceived()
        waitForFinalizedWithdrawal(pendingBtcWithdrawal.id, WithdrawalStatus.Complete)
        wsClient.assertBalancesMessageReceived()

        wsClient.close()

        return apiClient
    }
}

fun WalletKeyPair.asEcKeyPair(): ECKeyPair {
    return (this as WalletKeyPair.EVM).ecKeyPair
}

fun WalletKeyPair.asECKey(): ECKey {
    return (this as WalletKeyPair.Bitcoin).ecKey
}

fun Address.asEvmAddress(): EvmAddress {
    return this as EvmAddress
}

fun Address.asBitcoinAddress(): BitcoinAddress {
    return this as BitcoinAddress
}
