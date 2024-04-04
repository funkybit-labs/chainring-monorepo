package co.chainring

import co.chainring.sequencer.core.MarketId
import co.chainring.sequencer.core.toAsset
import co.chainring.sequencer.core.toBigDecimal
import co.chainring.sequencer.core.toBigInteger
import co.chainring.sequencer.core.toDecimalValue
import co.chainring.sequencer.core.toIntegerValue
import co.chainring.sequencer.core.toWalletAddress
import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.OrderDisposition
import co.chainring.sequencer.proto.SequencerResponse
import co.chainring.testutils.SequencerClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.math.pow
import kotlin.random.Random

class TestSequencer {

    private fun wei(eth: BigDecimal) = (eth * 10.0.pow(18).toBigDecimal()).toBigInteger()
    private fun sats(btc: BigDecimal) = (btc * 10.0.pow(8).toBigDecimal()).toBigInteger()

    @Test
    fun `Test basic order matching`() {
        val sequencer = SequencerClient()

        val marketId = MarketId("BTC1/ETH1")
        sequencer.createMarket(marketId)
        val maker = 123456789L.toWalletAddress()
        val taker = 555111555L.toWalletAddress()
        // maker deposits some of both assets -- 1 BTC, 1 ETH
        sequencer.deposit(maker, marketId.baseAsset(), sats(BigDecimal.ONE))
        sequencer.deposit(maker, marketId.quoteAsset(), wei(BigDecimal.ONE))
        // place an order and see that it gets accepted
        val response = sequencer.addOrder(marketId, sats(BigDecimal("0.00012345")).toLong(), "17.500", maker, Order.Type.LimitBuy)
        assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)

        // place a sell order
        val response2 = sequencer.addOrder(marketId, sats(BigDecimal("0.00054321")).toLong(), "17.550", maker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response2.ordersChangedList.first().disposition)

        // place a market buy and see that it gets executed
        sequencer.deposit(taker, marketId.quoteAsset(), wei(BigDecimal.ONE))
        val response3 = sequencer.addOrder(marketId, sats(BigDecimal("0.00043210")).toLong(), null, taker, Order.Type.MarketBuy)
        assertEquals(2, response3.ordersChangedCount)
        val takerOrder = response3.ordersChangedList[0]
        assertEquals(OrderDisposition.Filled, takerOrder.disposition)
        val makerOrder = response3.ordersChangedList[1]
        assertEquals(OrderDisposition.PartiallyFilled, makerOrder.disposition)
        assertEquals(response2.orderGuid(), makerOrder.guid)
        val trade = response3.tradesCreatedList.first()
        assertEquals("17.550".toBigDecimal().toDecimalValue(), trade.price)
        assertEquals(sats(BigDecimal("0.00043210")).toIntegerValue(), trade.amount)
        assertEquals(makerOrder.guid, trade.sellGuid)
        assertEquals(takerOrder.guid, trade.buyGuid)

        // each of the maker and taker should have two balance changed messages, one for each asset
        assertEquals(4, response3.balancesChangedCount)
        val makerBalanceChanges = response3.balancesChangedList.filter { it.wallet == maker.value }
        val takerBalanceChanges = response3.balancesChangedList.filter { it.wallet == taker.value }
        assertEquals(2, makerBalanceChanges.size)
        assertEquals(2, takerBalanceChanges.size)
        val makerBaseBalanceChange = makerBalanceChanges.find { it.asset == marketId.baseAsset().value }!!
        val makerQuoteBalanceChange = makerBalanceChanges.find { it.asset == marketId.quoteAsset().value }!!
        assertEquals(-sats(BigDecimal("0.00043210")), makerBaseBalanceChange.delta.toBigInteger())
        assertEquals(wei(BigDecimal("0.007583355")), makerQuoteBalanceChange.delta.toBigInteger())

        val takerBaseBalanceChange = takerBalanceChanges.find { it.asset == marketId.baseAsset().value }!!
        val takerQuoteBalanceChange = takerBalanceChanges.find { it.asset == marketId.quoteAsset().value }!!
        assertEquals(sats(BigDecimal("0.00043210")), takerBaseBalanceChange.delta.toBigInteger())
        assertEquals(-wei(BigDecimal("0.007583355")), takerQuoteBalanceChange.delta.toBigInteger())
        // balances now should be:
        //   maker BTC1 = 1 - 0.00043210 = 0.99956790
        //         ETH1 = 1 + .007583355 = 1.007583355
        //   taker BTC1 = 0.00043210
        //         ETH1 = 1 - .007583355 = 0.992416645

        // now try a market sell which can only be partially filled and see that it gets executed
        val response4 = sequencer.addOrder(marketId, sats(BigDecimal("0.00012346")).toLong(), null, taker, Order.Type.MarketSell)
        assertEquals(2, response4.ordersChangedCount)
        val takerOrder2 = response4.ordersChangedList[0]
        assertEquals(OrderDisposition.PartiallyFilled, takerOrder2.disposition)
        val makerOrder2 = response4.ordersChangedList[1]
        assertEquals(OrderDisposition.Filled, makerOrder2.disposition)
        val trade2 = response4.tradesCreatedList.first()
        assertEquals("17.500".toBigDecimal().toDecimalValue(), trade2.price)
        assertEquals(sats(BigDecimal("0.00012345")).toIntegerValue(), trade2.amount)
        assertEquals(makerOrder2.guid, trade2.buyGuid)
        assertEquals(takerOrder2.guid, trade2.sellGuid)
        // verify the remaining balances for maker and taker (withdraw a large amount - returned balance change will
        // indicate what the balance was)
        // expected balances:
        //
        //   maker BTC1 = 0.00956790 + .00012345 = 0.99969135
        //         ETH1 = 1.007583355 - 0.002160375 = 1.00542298
        //   taker BTC1 = 0.00043210 - 0.00012345 = 0.00030865
        //         ETH1 = 0.992416645 + 0.002160375 = 0.99457702
        sequencer.withdrawal(maker, marketId.baseAsset(), sats(BigDecimal.TEN), sats(BigDecimal("0.99969135")))
        sequencer.withdrawal(maker, marketId.quoteAsset(), wei(BigDecimal.TEN), wei(BigDecimal("1.00542298")))
        sequencer.withdrawal(taker, marketId.baseAsset(), sats(BigDecimal.TEN), sats(BigDecimal("0.00030865")))
        sequencer.withdrawal(taker, marketId.quoteAsset(), wei(BigDecimal.TEN), wei(BigDecimal("0.99457702")))
    }

    private fun SequencerResponse.orderGuid() = this.ordersChangedList.first().guid

    @Test
    fun `Test a market order that executes against multiple orders at multiple levels`() {
        val sequencer = SequencerClient()

        val marketId = MarketId("BTC2/ETH2")
        sequencer.createMarket(marketId)
        val lp1 = 123457689L.toWalletAddress()
        val lp2 = 987654321L.toWalletAddress()
        val tkr = 555555555L.toWalletAddress()
        val sell1 = sequencer.addOrder(marketId, 1000, "17.550", lp1, Order.Type.LimitSell)
        val sell2 = sequencer.addOrder(marketId, 1000, "17.550", lp2, Order.Type.LimitSell)
        val sell3 = sequencer.addOrder(marketId, 10000, "17.600", lp1, Order.Type.LimitSell)
        val sell4 = sequencer.addOrder(marketId, 10000, "17.600", lp2, Order.Type.LimitSell)
        val sell5 = sequencer.addOrder(marketId, 20000, "17.700", lp1, Order.Type.LimitSell)
        val sell6 = sequencer.addOrder(marketId, 20000, "17.700", lp2, Order.Type.LimitSell)
        val response = sequencer.addOrder(marketId, 17000, null, tkr, Order.Type.MarketBuy)
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
        val response2 = sequencer.addOrder(marketId, 45000, null, tkr, Order.Type.MarketBuy)
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

    @Test
    fun `test balances`() {
        val sequencer = SequencerClient()
        val rnd = Random(0)
        val walletAddress = rnd.nextLong().toWalletAddress()
        val asset = "ETH".toAsset()
        val amount = BigInteger.valueOf(1000)
        // do a deposit
        sequencer.deposit(walletAddress, asset, amount)
        // withdraw half
        sequencer.withdrawal(walletAddress, asset, amount / BigInteger.TWO)
        // request to withdraw amount, only half should be withdrawn
        sequencer.withdrawal(walletAddress, asset, amount, amount / BigInteger.TWO)
        // attempt to withdraw more does not return a balance change
        sequencer.withdrawal(walletAddress, asset, BigInteger.ONE, null)
        // attempt to withdraw from an unknown wallet or asset does not return a balance change
        sequencer.withdrawal(rnd.nextLong().toWalletAddress(), asset, BigInteger.ONE, null)
        sequencer.withdrawal(walletAddress, "PEPE".toAsset(), BigInteger.ONE, null)
        // can combine deposits and withdrawals in a batch - amount should be net
        sequencer.depositsAndWithdrawals(walletAddress, asset, listOf(BigInteger.TEN, BigInteger.ONE.negate()))
        // if it nets to 0, no balance change returned
        sequencer.depositsAndWithdrawals(walletAddress, asset, listOf(BigInteger.TEN.negate(), BigInteger.TEN), null)
    }
}
