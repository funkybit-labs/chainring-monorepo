package co.chainring

import co.chainring.sequencer.core.toBigDecimal
import co.chainring.sequencer.core.toBigInteger
import co.chainring.sequencer.core.toDecimalValue
import co.chainring.sequencer.core.toIntegerValue
import co.chainring.sequencer.proto.GatewayGrpcKt
import co.chainring.sequencer.proto.GatewayResponse
import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.OrderDisposition
import co.chainring.sequencer.proto.market
import co.chainring.sequencer.proto.order
import co.chainring.sequencer.proto.orderBatch
import co.chainring.testutils.AppUnderTestRunner
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.util.UUID
import kotlin.random.Random

@ExtendWith(AppUnderTestRunner::class)
class TestSequencer {
    private suspend fun GatewayGrpcKt.GatewayCoroutineStub.addOrder(
        marketId: String,
        amount: Long,
        price: String?,
        wallet: Long,
        orderType: Order.Type,
    ) =
        this.applyOrderBatch(
            orderBatch {
                this.marketId = marketId
                this.ordersToAdd.add(
                    order {
                        this.guid = Random.nextLong()
                        this.amount = amount.toBigInteger().toIntegerValue()
                        this.price = price?.toBigDecimal()?.toDecimalValue() ?: BigDecimal.ZERO.toDecimalValue()
                        this.wallet = wallet
                        this.type = orderType
                    },
                )
            },
        )

    @Test
    fun `Test sequencer`() = runTest {
        val channel = ManagedChannelBuilder.forAddress("localhost", 5338).usePlaintext().build()
        val stub = GatewayGrpcKt.GatewayCoroutineStub(channel)

        val marketId = "BTC1/ETH1"
        createMarket(stub, marketId)
        // place an order and see that it gets accepted
        val response = stub.addOrder(marketId, 12345, "17.500", 123456789L, Order.Type.LimitBuy)
        assertEquals(OrderDisposition.Accepted, response.sequencerResponse.ordersChangedList.first().disposition)

        // place another order and see that it gets the next sequence number
        val response2 = stub.addOrder(marketId, 54321, "17.550", 123456789L, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response2.sequencerResponse.ordersChangedList.first().disposition)
        assertEquals(response.sequencerResponse.sequence + 1, response2.sequencerResponse.sequence)

        // place a market buy and see that it gets executed
        val response3 = stub.addOrder(marketId, 43210, null, 555111555L, Order.Type.MarketBuy)
        assertEquals(OrderDisposition.Filled, response3.sequencerResponse.ordersChangedList.first().disposition)
        val trade = response3.sequencerResponse.tradesCreatedList.first()
        assertEquals("17.550".toBigDecimal().toDecimalValue(), trade.price)
        assertEquals(43210.toBigInteger().toIntegerValue(), trade.amount)
        assertEquals(response2.orderGuid(), trade.sellGuid)
        assertEquals(response3.orderGuid(), trade.buyGuid)

        // now try a market sell which can only be partially filled and see that it gets executed
        val response4 = stub.addOrder(marketId, 12346, null, 555111555L, Order.Type.MarketSell)
        assertEquals(OrderDisposition.PartiallyFilled, response4.sequencerResponse.ordersChangedList.first().disposition)
        val trade2 = response4.sequencerResponse.tradesCreatedList.first()
        assertEquals("17.500".toBigDecimal().toDecimalValue(), trade2.price)
        assertEquals(12345.toBigInteger().toIntegerValue(), trade2.amount)
        assertEquals(response.orderGuid(), trade2.buyGuid)
        assertEquals(response4.orderGuid(), trade2.sellGuid)
    }

    private fun GatewayResponse.orderGuid() = this.sequencerResponse.ordersChangedList.first().guid

    @Test
    fun `Test a market order that executes against multiple orders at multiple levels`() = runTest {
        val channel = ManagedChannelBuilder.forAddress("localhost", 5338).usePlaintext().build()
        val stub = GatewayGrpcKt.GatewayCoroutineStub(channel)

        val marketId = "BTC2/ETH2"
        createMarket(stub, marketId)
        val lp1 = 123457689L
        val lp2 = 987654321L
        val tkr = 555555555L
        val sell1 = stub.addOrder(marketId, 1000, "17.550", lp1, Order.Type.LimitSell)
        val sell2 = stub.addOrder(marketId, 1000, "17.550", lp2, Order.Type.LimitSell)
        val sell3 = stub.addOrder(marketId, 10000, "17.600", lp1, Order.Type.LimitSell)
        val sell4 = stub.addOrder(marketId, 10000, "17.600", lp2, Order.Type.LimitSell)
        val sell5 = stub.addOrder(marketId, 20000, "17.700", lp1, Order.Type.LimitSell)
        val sell6 = stub.addOrder(marketId, 20000, "17.700", lp2, Order.Type.LimitSell)
        val response = stub.addOrder(marketId, 17000, null, tkr, Order.Type.MarketBuy)
        assertEquals(OrderDisposition.Filled, response.sequencerResponse.ordersChangedList.first().disposition)
        assertEquals(
            listOf(sell1.orderGuid(), sell2.orderGuid(), sell3.orderGuid(), sell4.orderGuid()),
            response.sequencerResponse.tradesCreatedList.map { it.sellGuid },
        )
        assertEquals(
            listOf(1000, 1000, 10000, 5000),
            response.sequencerResponse.tradesCreatedList.map { it.amount.toBigInteger().toInt() },
        )
        assertEquals(
            listOf("17.550", "17.550", "17.600", "17.600"),
            response.sequencerResponse.tradesCreatedList.map { it.price.toBigDecimal().toString() },
        )
        // place another market order to exhaust remaining limit orders
        val response2 = stub.addOrder(marketId, 45000, null, tkr, Order.Type.MarketBuy)
        assertEquals(OrderDisposition.Filled, response2.sequencerResponse.ordersChangedList.first().disposition)
        assertEquals(
            listOf(sell4.orderGuid(), sell5.orderGuid(), sell6.orderGuid()),
            response2.sequencerResponse.tradesCreatedList.map { it.sellGuid },
        )
        assertEquals(
            listOf(5000, 20000, 20000),
            response2.sequencerResponse.tradesCreatedList.map { it.amount.toBigInteger().toInt() },
        )
        assertEquals(
            listOf("17.600", "17.700", "17.700"),
            response2.sequencerResponse.tradesCreatedList.map { it.price.toBigDecimal().toString() },
        )
    }

    private suspend fun createMarket(stub: GatewayGrpcKt.GatewayCoroutineStub, marketId: String, tickSize: BigDecimal = "0.05".toBigDecimal(), marketPrice: BigDecimal = "17.525".toBigDecimal()) {
        val createMarketResponse = stub.addMarket(
            market {
                this.guid = UUID.randomUUID().toString()
                this.marketId = marketId
                this.tickSize = tickSize.toDecimalValue()
                this.maxLevels = 1000
                this.maxOrdersPerLevel = 1000
                this.marketPrice = marketPrice.toDecimalValue()
            },
        )
        assertEquals(1, createMarketResponse.sequencerResponse.marketsCreatedCount)
        val createdMarket = createMarketResponse.sequencerResponse.marketsCreatedList.first()
        assertEquals(marketId, createdMarket.marketId)
        assertEquals(tickSize, createdMarket.tickSize.toBigDecimal())
    }
}
