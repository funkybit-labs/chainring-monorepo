package xyz.funkybit.integrationtests.api

import org.bitcoinj.core.ECKey
import org.http4k.client.WebsocketClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.extension.ExtendWith
import xyz.funkybit.apps.api.model.Market
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
import xyz.funkybit.integrationtests.utils.assertMyLimitOrderCreatedMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyMarketOrderCreatedMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyOrderUpdatedMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyOrdersMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyTradesCreatedMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyTradesMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyTradesUpdatedMessageReceived
import xyz.funkybit.integrationtests.utils.blocking
import xyz.funkybit.integrationtests.utils.subscribeToBalances
import xyz.funkybit.integrationtests.utils.subscribeToMyOrders
import xyz.funkybit.integrationtests.utils.subscribeToMyTrades
import java.math.BigDecimal
import kotlin.test.Test

@ExtendWith(AppUnderTestRunner::class)
class WalletLinkingTest : OrderBaseTest() {
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

        // link bitcoin wallet
        val takerBitcoinKeyPair = WalletKeyPair.Bitcoin(ECKey())
        takerApiClientEvm.linkBitcoinWallet(takerBitcoinKeyPair)
        val takerApiClientBitcoin = TestApiClient(takerBitcoinKeyPair, chainId = ChainId(0u))

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
