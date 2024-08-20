package xyz.funkybit.integrationtests.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.extension.ExtendWith
import xyz.funkybit.apps.api.model.Order
import xyz.funkybit.core.model.db.OrderSide
import xyz.funkybit.core.model.db.WithdrawalStatus
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.testutils.OrderBaseTest
import xyz.funkybit.integrationtests.testutils.waitForFinalizedWithdrawal
import xyz.funkybit.integrationtests.utils.AssetAmount
import xyz.funkybit.integrationtests.utils.assertBalancesMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyLimitOrderCreatedMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyOrderUpdatedMessageReceived
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

@ExtendWith(AppUnderTestRunner::class)
class OrderAutoReductionTest : OrderBaseTest() {
    @Test
    fun `order auto-reduction is reflected in API`() {
        val market = btcEthMarket

        val (makerApiClient, makerWallet, makerWsClient) = setupTrader(
            market.id,
            airdrops = listOf(
                AssetAmount(btc, "0.5"),
                AssetAmount(usdc, "2"),
            ),
            deposits = listOf(
                AssetAmount(btc, "0.2"),
                AssetAmount(usdc, "2"),
            ),
            subscribeToOrderBook = false,
            subscribeToLimits = false,
            subscribeToPrices = false,
        )

        // place an order and see that it gets accepted
        val limitBuyOrderApiResponse = makerApiClient.createLimitOrder(
            market,
            OrderSide.Sell,
            amount = BigDecimal("0.2"),
            price = BigDecimal("17.500"),
            makerWallet,
        )
        makerWsClient.assertMyLimitOrderCreatedMessageReceived(limitBuyOrderApiResponse)

        val btcWithdrawalAmount = AssetAmount(btc, "0.1")

        val pendingBtcWithdrawal = makerApiClient.createWithdrawal(makerWallet.signWithdraw(btc.name, btcWithdrawalAmount.inFundamentalUnits)).withdrawal
        assertEquals(WithdrawalStatus.Pending, pendingBtcWithdrawal.status)

        makerWsClient.assertMyOrderUpdatedMessageReceived { msg ->
            msg.order.also { order ->
                assertEquals(limitBuyOrderApiResponse.orderId, order.id)
                assertIs<Order.Limit>(order)
                assertTrue(order.autoReduced)
                assertEquals(AssetAmount(btc, "0.2").inFundamentalUnits, order.originalAmount)
                assertEquals(AssetAmount(btc, "0.1").inFundamentalUnits, order.amount)
            }
        }

        makerApiClient.getOrder(limitBuyOrderApiResponse.orderId).also { order ->
            assertIs<Order.Limit>(order)
            assertTrue(order.autoReduced)
            assertEquals(AssetAmount(btc, "0.2").inFundamentalUnits, order.originalAmount)
            assertEquals(AssetAmount(btc, "0.1").inFundamentalUnits, order.amount)
        }

        makerWsClient.assertBalancesMessageReceived()
        waitForFinalizedWithdrawal(pendingBtcWithdrawal.id, WithdrawalStatus.Complete)
        makerWsClient.assertBalancesMessageReceived()

        makerWsClient.close()
    }
}
