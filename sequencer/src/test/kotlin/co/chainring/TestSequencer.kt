package co.chainring

import co.chainring.sequencer.apps.SequencerApp
import co.chainring.sequencer.core.toBigDecimal
import co.chainring.sequencer.core.toBigInteger
import co.chainring.sequencer.core.toDecimalValue
import co.chainring.sequencer.core.toIntegerValue
import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.OrderDisposition
import co.chainring.sequencer.proto.SequencerRequest
import co.chainring.sequencer.proto.SequencerResponse
import co.chainring.sequencer.proto.market
import co.chainring.sequencer.proto.order
import co.chainring.sequencer.proto.orderBatch
import co.chainring.sequencer.proto.sequencerRequest
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID
import kotlin.random.Random

class TestSequencer {
    private fun addOrder(
        sequencer: SequencerApp,
        marketId: String,
        amount: Long,
        price: String?,
        wallet: Long,
        orderType: Order.Type,
    ) =
        sequencer.processRequest(
            sequencerRequest {
                this.guid = UUID.randomUUID().toString()
                this.type = SequencerRequest.Type.ApplyOrderBatch
                this.orderBatch = orderBatch {
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
                }
            },
        )

    @Test
    fun `Test sequencer`() = runTest {
        val sequencer = SequencerApp()

        val marketId = "BTC1/ETH1"
        createMarket(sequencer, marketId)
        // place an order and see that it gets accepted
        val response = addOrder(sequencer, marketId, 12345, "17.500", 123456789L, Order.Type.LimitBuy)
        assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)

        // place a sell order
        val response2 = addOrder(sequencer, marketId, 54321, "17.550", 123456789L, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response2.ordersChangedList.first().disposition)

        // place a market buy and see that it gets executed
        val response3 = addOrder(sequencer, marketId, 43210, null, 555111555L, Order.Type.MarketBuy)
        assertEquals(2, response3.ordersChangedCount)
        val takerOrder = response3.ordersChangedList[0]
        assertEquals(OrderDisposition.Filled, takerOrder.disposition)
        val makerOrder = response3.ordersChangedList[1]
        assertEquals(OrderDisposition.PartiallyFilled, makerOrder.disposition)
        assertEquals(response2.orderGuid(), makerOrder.guid)
        val trade = response3.tradesCreatedList.first()
        assertEquals("17.550".toBigDecimal().toDecimalValue(), trade.price)
        assertEquals(43210.toBigInteger().toIntegerValue(), trade.amount)
        assertEquals(makerOrder.guid, trade.sellGuid)
        assertEquals(takerOrder.guid, trade.buyGuid)

        // now try a market sell which can only be partially filled and see that it gets executed
        val response4 = addOrder(sequencer, marketId, 12346, null, 555111555L, Order.Type.MarketSell)
        assertEquals(2, response4.ordersChangedCount)
        val takerOrder2 = response4.ordersChangedList[0]
        assertEquals(OrderDisposition.PartiallyFilled, takerOrder2.disposition)
        val makerOrder2 = response4.ordersChangedList[1]
        assertEquals(OrderDisposition.Filled, makerOrder2.disposition)
        val trade2 = response4.tradesCreatedList.first()
        assertEquals("17.500".toBigDecimal().toDecimalValue(), trade2.price)
        assertEquals(12345.toBigInteger().toIntegerValue(), trade2.amount)
        assertEquals(makerOrder2.guid, trade2.buyGuid)
        assertEquals(takerOrder2.guid, trade2.sellGuid)
    }

    private fun SequencerResponse.orderGuid() = this.ordersChangedList.first().guid

    @Test
    fun `Test a market order that executes against multiple orders at multiple levels`() = runTest {
        val sequencer = SequencerApp()

        val marketId = "BTC2/ETH2"
        createMarket(sequencer, marketId)
        val lp1 = 123457689L
        val lp2 = 987654321L
        val tkr = 555555555L
        val sell1 = addOrder(sequencer, marketId, 1000, "17.550", lp1, Order.Type.LimitSell)
        val sell2 = addOrder(sequencer, marketId, 1000, "17.550", lp2, Order.Type.LimitSell)
        val sell3 = addOrder(sequencer, marketId, 10000, "17.600", lp1, Order.Type.LimitSell)
        val sell4 = addOrder(sequencer, marketId, 10000, "17.600", lp2, Order.Type.LimitSell)
        val sell5 = addOrder(sequencer, marketId, 20000, "17.700", lp1, Order.Type.LimitSell)
        val sell6 = addOrder(sequencer, marketId, 20000, "17.700", lp2, Order.Type.LimitSell)
        val response = addOrder(sequencer, marketId, 17000, null, tkr, Order.Type.MarketBuy)
        assertEquals(5, response.ordersChangedCount)
        assertEquals(OrderDisposition.Filled, response.ordersChangedList[0].disposition)
        assertEquals(OrderDisposition.Filled, response.ordersChangedList[1].disposition)
        assertEquals(OrderDisposition.Filled, response.ordersChangedList[2].disposition)
        assertEquals(OrderDisposition.Filled, response.ordersChangedList[3].disposition)
        assertEquals(OrderDisposition.PartiallyFilled, response.ordersChangedList[4].disposition)
        assertEquals(
            listOf(sell1.orderGuid(), sell2.orderGuid(), sell3.orderGuid(), sell4.orderGuid()),
            response.tradesCreatedList.map { it.sellGuid },
        )
        assertEquals(
            listOf(1000, 1000, 10000, 5000),
            response.tradesCreatedList.map { it.amount.toBigInteger().toInt() },
        )
        assertEquals(
            listOf("17.550", "17.550", "17.600", "17.600"),
            response.tradesCreatedList.map { it.price.toBigDecimal().toString() },
        )
        // place another market order to exhaust remaining limit orders
        val response2 = addOrder(sequencer, marketId, 45000, null, tkr, Order.Type.MarketBuy)
        assertEquals(4, response2.ordersChangedCount)
        assertEquals(OrderDisposition.Filled, response2.ordersChangedList[0].disposition)
        assertEquals(OrderDisposition.Filled, response2.ordersChangedList[1].disposition)
        assertEquals(OrderDisposition.Filled, response2.ordersChangedList[2].disposition)
        assertEquals(OrderDisposition.Filled, response2.ordersChangedList[3].disposition)
        assertEquals(
            listOf(sell4.orderGuid(), sell5.orderGuid(), sell6.orderGuid()),
            response2.tradesCreatedList.map { it.sellGuid },
        )
        assertEquals(
            listOf(5000, 20000, 20000),
            response2.tradesCreatedList.map { it.amount.toBigInteger().toInt() },
        )
        assertEquals(
            listOf("17.600", "17.700", "17.700"),
            response2.tradesCreatedList.map { it.price.toBigDecimal().toString() },
        )
    }

    private fun createMarket(sequencer: SequencerApp, marketId: String, tickSize: BigDecimal = "0.05".toBigDecimal(), marketPrice: BigDecimal = "17.525".toBigDecimal()) {
        val createMarketResponse = sequencer.processRequest(
            sequencerRequest {
                this.guid = UUID.randomUUID().toString()
                this.type = SequencerRequest.Type.AddMarket
                this.addMarket = market {
                    this.guid = UUID.randomUUID().toString()
                    this.marketId = marketId
                    this.tickSize = tickSize.toDecimalValue()
                    this.maxLevels = 1000
                    this.maxOrdersPerLevel = 1000
                    this.marketPrice = marketPrice.toDecimalValue()
                }
            },
        )
        assertEquals(1, createMarketResponse.marketsCreatedCount)
        val createdMarket = createMarketResponse.marketsCreatedList.first()
        assertEquals(marketId, createdMarket.marketId)
        assertEquals(tickSize, createdMarket.tickSize.toBigDecimal())
    }
}
