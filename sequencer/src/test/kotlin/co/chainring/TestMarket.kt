package co.chainring

import co.chainring.sequencer.core.FeeRate
import co.chainring.sequencer.core.FeeRates
import co.chainring.sequencer.core.Market
import co.chainring.sequencer.core.MarketId
import co.chainring.sequencer.core.WalletAddress
import co.chainring.sequencer.core.notionalPlusFee
import co.chainring.sequencer.core.toDecimalValue
import co.chainring.sequencer.core.toIntegerValue
import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.OrderDisposition
import co.chainring.sequencer.proto.order
import co.chainring.sequencer.proto.orderBatch
import co.chainring.testutils.toFundamentalUnits
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestMarket {
    private lateinit var market: Market

    @BeforeEach
    fun setup() {
        market = Market(
            id = MarketId("BTC/ETH"),
            tickSize = BigDecimal("0.05"),
            maxOrdersPerLevel = 100,
            baseDecimals = 18,
            quoteDecimals = 18,
            minFee = BigInteger.ZERO,
        )
    }

    private fun String.levelIx(market: Market): Int {
        return BigDecimal(this).divideToIntegralValue(market.tickSize).toInt()
    }

    @Test
    fun testBidValues() {
        validateBidAndOffer(-1, -1, -1, -1)

        addOrder(1L, Order.Type.LimitBuy, "1", "17.500")
        addOrder(2L, Order.Type.LimitBuy, "1", "17.500")
        validateBid(
            bestBidIx = "17.500".levelIx(market),
            minBidIx = "17.500".levelIx(market),
        )

        addOrder(3L, Order.Type.LimitBuy, "1", "17.450")
        addOrder(4L, Order.Type.LimitBuy, "1", "17.450")
        validateBid(
            bestBidIx = "17.500".levelIx(market),
            minBidIx = "17.450".levelIx(market),
        )

        addOrder(5L, Order.Type.LimitBuy, "1", "17.400")
        addOrder(6L, Order.Type.LimitBuy, "1", "17.400")
        validateBid(
            bestBidIx = "17.500".levelIx(market),
            minBidIx = "17.400".levelIx(market),
        )

        // cancel order 1, 3 and 5 - nothing should change
        cancelOrders(listOf(1L, 3L, 5L))
        validateBid(
            bestBidIx = "17.500".levelIx(market),
            minBidIx = "17.400".levelIx(market),
        )

        // cancel order 2, bestBid should change
        cancelOrders(listOf(2L))
        validateBid(
            bestBidIx = "17.450".levelIx(market),
            minBidIx = "17.400".levelIx(market),
        )

        // cancel order 6, minBidIx should change
        cancelOrders(listOf(6L))
        validateBid(
            bestBidIx = "17.450".levelIx(market),
            minBidIx = "17.450".levelIx(market),
        )

        // cancel order 4, minBidIx and bestBid should be back at initial values
        cancelOrders(listOf(4L))
        validateBidAndOffer(-1, -1, -1, -1)
    }

    @Test
    fun testOfferValues() {
        validateBidAndOffer(-1, -1, -1, -1)

        addOrder(1L, Order.Type.LimitSell, "1", "17.550")
        addOrder(2L, Order.Type.LimitSell, "1", "17.550")
        validateOffer(
            bestOfferIx = "17.550".levelIx(market),
            maxOfferIx = "17.550".levelIx(market),
        )

        addOrder(3L, Order.Type.LimitSell, "1", "17.600")
        addOrder(4L, Order.Type.LimitSell, "1", "17.600")
        validateOffer(
            bestOfferIx = "17.550".levelIx(market),
            maxOfferIx = "17.600".levelIx(market),
        )

        addOrder(5L, Order.Type.LimitSell, "1", "17.700")
        addOrder(6L, Order.Type.LimitSell, "1", "17.700")
        validateOffer(
            bestOfferIx = "17.550".levelIx(market),
            maxOfferIx = "17.700".levelIx(market),
        )

        // cancel order 1, 3 and 5 - nothing should change
        cancelOrders(listOf(1L, 3L, 5L))
        validateOffer(
            bestOfferIx = "17.550".levelIx(market),
            maxOfferIx = "17.700".levelIx(market),
        )

        // cancel order 2, bestOffer should change
        cancelOrders(listOf(2L))
        validateOffer(
            bestOfferIx = "17.600".levelIx(market),
            maxOfferIx = "17.700".levelIx(market),
        )

        // cancel order 6, maxOfferIx should change
        cancelOrders(listOf(6L))
        validateOffer(
            bestOfferIx = "17.600".levelIx(market),
            maxOfferIx = "17.600".levelIx(market),
        )

        // cancel order 4, should be back at initial values
        cancelOrders(listOf(4L))
        validateBidAndOffer(-1, -1, -1, -1)
    }

    @Test
    fun testOrderMatching() {
        validateBidAndOffer(-1, -1, -1, -1)

        addOrder(1L, Order.Type.LimitBuy, "5", "17.300")
        addOrder(2L, Order.Type.LimitBuy, "5", "17.500")
        addOrder(3L, Order.Type.LimitSell, "5", "17.550")
        addOrder(4L, Order.Type.LimitSell, "5", "17.700")
        validateBidAndOffer(
            maxOfferIx = "17.700".levelIx(market),
            bestOfferIx = "17.550".levelIx(market),
            bestBidIx = "17.500".levelIx(market),
            minBidIx = "17.300".levelIx(market),
        )

        // market sell - no levels exhausted
        addOrder(5L, Order.Type.MarketSell, "3", "0", OrderDisposition.Filled, counterOrderGuid = 2L)
        validateBidAndOffer(
            maxOfferIx = "17.700".levelIx(market),
            bestOfferIx = "17.550".levelIx(market),
            bestBidIx = "17.500".levelIx(market),
            minBidIx = "17.300".levelIx(market),
        )

        // market sell - level with best bid exhausted - should go to the next level with orders
        addOrder(6L, Order.Type.MarketSell, "2", "0", OrderDisposition.Filled, counterOrderGuid = 2L)
        validateBidAndOffer(
            bestBidIx = "17.300".levelIx(market),
            minBidIx = "17.300".levelIx(market),
            bestOfferIx = "17.550".levelIx(market),
            maxOfferIx = "17.700".levelIx(market),
        )

        // market buy - no levels exhausted
        addOrder(7L, Order.Type.MarketBuy, "3", "0", OrderDisposition.Filled, counterOrderGuid = 3L)
        validateBidAndOffer(
            bestBidIx = "17.300".levelIx(market),
            minBidIx = "17.300".levelIx(market),
            bestOfferIx = "17.550".levelIx(market),
            maxOfferIx = "17.700".levelIx(market),
        )

        // market buy - best offer exhausted - should go to the next level with orders
        addOrder(8L, Order.Type.MarketBuy, "2", "0", OrderDisposition.Filled, counterOrderGuid = 3L)
        validateBidAndOffer(
            bestBidIx = "17.300".levelIx(market),
            minBidIx = "17.300".levelIx(market),
            bestOfferIx = "17.700".levelIx(market),
            maxOfferIx = "17.700".levelIx(market),
        )

        // exhaust levels - should go back to initial values
        addOrder(9L, Order.Type.MarketSell, "5", "0", OrderDisposition.Filled, counterOrderGuid = 1L)
        addOrder(10L, Order.Type.MarketBuy, "5", "0", OrderDisposition.Filled, counterOrderGuid = 4L)

        validateBidAndOffer(-1, -1, -1, -1)
    }

    @Test
    fun testCrossingLimitSellOrders() {
        validateBidAndOffer(-1, -1, -1, -1)

        addOrder(1L, Order.Type.LimitBuy, "5", "17.300")
        addOrder(2L, Order.Type.LimitBuy, "5", "17.500")
        addOrder(3L, Order.Type.LimitSell, "5", "17.550")
        addOrder(4L, Order.Type.LimitSell, "5", "17.700")
        validateBidAndOffer(
            bestBidIx = "17.500".levelIx(market),
            minBidIx = "17.300".levelIx(market),
            bestOfferIx = "17.550".levelIx(market),
            maxOfferIx = "17.700".levelIx(market),
        )

        // limit sell that crosses - bestBid should be adjusted to next level
        addOrder(5L, Order.Type.LimitSell, "7", "17.450", OrderDisposition.PartiallyFilled, counterOrderGuid = 2L)
        validateBidAndOffer(
            bestBidIx = "17.300".levelIx(market),
            minBidIx = "17.300".levelIx(market),
            bestOfferIx = "17.450".levelIx(market),
            maxOfferIx = "17.700".levelIx(market),
        )

        // next market sell should match against limit buy
        addOrder(6L, Order.Type.MarketSell, "1", "0", OrderDisposition.Filled, counterOrderGuid = 1L)
        validateBidAndOffer(
            bestBidIx = "17.300".levelIx(market),
            minBidIx = "17.300".levelIx(market),
            bestOfferIx = "17.450".levelIx(market),
            maxOfferIx = "17.700".levelIx(market),
        )

        // next market buy should match against limit sell that crossed and bestOffer should change since we consumed that level
        addOrder(7L, Order.Type.MarketBuy, "2", "0", OrderDisposition.Filled, counterOrderGuid = 5L)
        validateBidAndOffer(
            bestBidIx = "17.300".levelIx(market),
            minBidIx = "17.300".levelIx(market),
            bestOfferIx = "17.550".levelIx(market),
            maxOfferIx = "17.700".levelIx(market),
        )

        // submit limit sell that crosses and exhausts all the buys, bidIx and bestBid should go back to initial values
        addOrder(8L, Order.Type.LimitSell, "6", "17.250", OrderDisposition.PartiallyFilled, counterOrderGuid = 1L)
        validateBidAndOffer(
            bestBidIx = -1,
            minBidIx = -1,
            bestOfferIx = "17.250".levelIx(market),
            maxOfferIx = "17.700".levelIx(market),
        )

        // market sell should be rejected since no buys
        addOrder(6L, Order.Type.MarketSell, "1", "0", OrderDisposition.Rejected)
    }

    @Test
    fun testCrossingEdgeCases() {
        validateBidAndOffer(-1, -1, -1, -1)

        addOrder(1L, Order.Type.LimitSell, "5", "50.000")
        addOrder(2L, Order.Type.LimitBuy, "5", "0.050")

        validateBidAndOffer(
            bestBidIx = "0.050".levelIx(market),
            minBidIx = "0.050".levelIx(market),
            bestOfferIx = "50.000".levelIx(market),
            maxOfferIx = "50.000".levelIx(market),
        )

        // limit buy that exactly consumes all of resting sell
        addOrder(3L, Order.Type.LimitBuy, "5", "50.000", OrderDisposition.Filled, counterOrderGuid = 1L)
        validateBidAndOffer(
            bestBidIx = "0.050".levelIx(market),
            minBidIx = "0.050".levelIx(market),
            bestOfferIx = -1,
            maxOfferIx = -1,
        )

        // put the LimitSell back
        addOrder(4L, Order.Type.LimitSell, "5", "50.000")
        validateBidAndOffer(
            bestBidIx = "0.050".levelIx(market),
            minBidIx = "0.050".levelIx(market),
            bestOfferIx = "50.000".levelIx(market),
            maxOfferIx = "50.000".levelIx(market),
        )

        // LimitBuy that consumes all of resting sell and stays on the book
        addOrder(5L, Order.Type.LimitBuy, "6", "50.000", OrderDisposition.PartiallyFilled, counterOrderGuid = 4L)
        validateBidAndOffer(
            bestBidIx = "50.000".levelIx(market),
            minBidIx = "0.050".levelIx(market),
            bestOfferIx = -1,
            maxOfferIx = -1,
        )
        // cancel that Limit Buy
        cancelOrders(listOf(5L))
        validateBidAndOffer(
            bestBidIx = "0.050".levelIx(market),
            minBidIx = "0.050".levelIx(market),
            bestOfferIx = -1,
            maxOfferIx = -1,
        )

        // add a LimitSell that exactly consumes resting buy
        addOrder(6L, Order.Type.LimitSell, "5", "0.050", OrderDisposition.Filled, counterOrderGuid = 2L)
        validateBidAndOffer(
            bestBidIx = -1,
            minBidIx = -1,
            bestOfferIx = -1,
            maxOfferIx = -1,
        )

        // put a LimitBuy back at the edge
        addOrder(7L, Order.Type.LimitBuy, "5", "0.050")
        validateBidAndOffer(
            bestBidIx = "0.050".levelIx(market),
            minBidIx = "0.050".levelIx(market),
            bestOfferIx = -1,
            maxOfferIx = -1,
        )

        // LimitSell that consumes all of resting buy and stays on the book
        addOrder(8L, Order.Type.LimitSell, "6", "0.050", OrderDisposition.PartiallyFilled, counterOrderGuid = 7L)
        validateBidAndOffer(
            bestBidIx = -1,
            minBidIx = -1,
            bestOfferIx = "0.050".levelIx(market),
            maxOfferIx = "0.050".levelIx(market),
        )
    }

    @Test
    fun testCrossingLimitBuyOrders() {
        validateBidAndOffer(-1, -1, -1, -1)

        addOrder(1L, Order.Type.LimitBuy, "5", "17.300")
        addOrder(2L, Order.Type.LimitBuy, "5", "17.500")
        addOrder(3L, Order.Type.LimitSell, "5", "17.550")
        addOrder(4L, Order.Type.LimitSell, "5", "17.700")
        validateBidAndOffer(
            bestBidIx = "17.500".levelIx(market),
            minBidIx = "17.300".levelIx(market),
            bestOfferIx = "17.550".levelIx(market),
            maxOfferIx = "17.700".levelIx(market),
        )

        // limit buy that crosses - best Bid and Offer should adjust
        addOrder(5L, Order.Type.LimitBuy, "7", "17.600", OrderDisposition.PartiallyFilled, counterOrderGuid = 3L)
        validateBidAndOffer(
            bestBidIx = "17.600".levelIx(market),
            minBidIx = "17.300".levelIx(market),
            bestOfferIx = "17.700".levelIx(market),
            maxOfferIx = "17.700".levelIx(market),
        )

        // next market buy should match against limit sell
        addOrder(6L, Order.Type.MarketBuy, "1", "0", OrderDisposition.Filled, counterOrderGuid = 4L)
        validateBidAndOffer(
            bestBidIx = "17.600".levelIx(market),
            minBidIx = "17.300".levelIx(market),
            bestOfferIx = "17.700".levelIx(market),
            maxOfferIx = "17.700".levelIx(market),
        )

        // next market sell should match against limit buy that crossed and bestBid should change since we consumed that level
        addOrder(7L, Order.Type.MarketSell, "2", "0", OrderDisposition.Filled, counterOrderGuid = 5L)
        validateBidAndOffer(
            bestBidIx = "17.500".levelIx(market),
            minBidIx = "17.300".levelIx(market),
            bestOfferIx = "17.700".levelIx(market),
            maxOfferIx = "17.700".levelIx(market),
        )

        // submit limit buy that crosses and exhausts all the sells, bestOffer and minOfferIx should go back to initial values
        addOrder(8L, Order.Type.LimitBuy, "6", "17.700", OrderDisposition.PartiallyFilled, counterOrderGuid = 4L)
        validateBidAndOffer(
            bestBidIx = "17.700".levelIx(market),
            minBidIx = "17.300".levelIx(market),
            bestOfferIx = -1,
            maxOfferIx = -1,
        )

        // market buy should be rejected since no sell
        addOrder(6L, Order.Type.MarketBuy, "1", "0", OrderDisposition.Rejected)
    }

    @Test
    fun testUpdateOrderCrossingLimitSellOrders() {
        validateBidAndOffer(-1, -1, -1, -1)

        addOrder(1L, Order.Type.LimitBuy, "5", "17.300")
        addOrder(2L, Order.Type.LimitBuy, "5", "17.500")
        addOrder(3L, Order.Type.LimitSell, "5", "17.550")
        addOrder(4L, Order.Type.LimitSell, "5", "17.700")
        validateBidAndOffer(
            bestBidIx = "17.500".levelIx(market),
            minBidIx = "17.300".levelIx(market),
            bestOfferIx = "17.550".levelIx(market),
            maxOfferIx = "17.700".levelIx(market),
        )

        // update limit sell that crosses - fully filled
        updateOrder(3L, Order.Type.LimitSell, "5", "17.450", OrderDisposition.Filled, counterOrderGuid = 2L)
        validateBidAndOffer(
            bestBidIx = "17.300".levelIx(market),
            minBidIx = "17.300".levelIx(market),
            bestOfferIx = "17.700".levelIx(market),
            maxOfferIx = "17.700".levelIx(market),
        )

        // add back similar orders
        addOrder(5L, Order.Type.LimitBuy, "5", "17.500")
        addOrder(6L, Order.Type.LimitSell, "5", "17.550")
        validateBidAndOffer(
            bestBidIx = "17.500".levelIx(market),
            minBidIx = "17.300".levelIx(market),
            bestOfferIx = "17.550".levelIx(market),
            maxOfferIx = "17.700".levelIx(market),
        )
        // update limit sell that crosses - partially filled
        updateOrder(6L, Order.Type.LimitSell, "7", "17.450", OrderDisposition.PartiallyFilled, counterOrderGuid = 5L)
        validateBidAndOffer(
            bestBidIx = "17.300".levelIx(market),
            minBidIx = "17.300".levelIx(market),
            bestOfferIx = "17.450".levelIx(market),
            maxOfferIx = "17.700".levelIx(market),
        )
    }

    @Test
    fun testUpdateOrderCrossingLimitBuyOrders() {
        validateBidAndOffer(-1, -1, -1, -1)

        addOrder(1L, Order.Type.LimitBuy, "5", "17.300")
        addOrder(2L, Order.Type.LimitBuy, "5", "17.500")
        addOrder(3L, Order.Type.LimitSell, "5", "17.550")
        addOrder(4L, Order.Type.LimitSell, "5", "17.700")
        validateBidAndOffer(
            bestBidIx = "17.500".levelIx(market),
            minBidIx = "17.300".levelIx(market),
            bestOfferIx = "17.550".levelIx(market),
            maxOfferIx = "17.700".levelIx(market),
        )

        // update limit buy that crosses - fully filled
        updateOrder(2L, Order.Type.LimitBuy, "5", "17.550", OrderDisposition.Filled, counterOrderGuid = 3L)
        validateBidAndOffer(
            bestBidIx = "17.300".levelIx(market),
            minBidIx = "17.300".levelIx(market),
            bestOfferIx = "17.700".levelIx(market),
            maxOfferIx = "17.700".levelIx(market),
        )

        // add back similar orders
        addOrder(5L, Order.Type.LimitBuy, "5", "17.500")
        addOrder(6L, Order.Type.LimitSell, "5", "17.550")
        validateBidAndOffer(
            bestBidIx = "17.500".levelIx(market),
            minBidIx = "17.300".levelIx(market),
            bestOfferIx = "17.550".levelIx(market),
            maxOfferIx = "17.700".levelIx(market),
        )
        // update limit sell that crosses - partially filled
        updateOrder(5L, Order.Type.LimitBuy, "7", "17.550", OrderDisposition.PartiallyFilled, counterOrderGuid = 6L)
        validateBidAndOffer(
            bestBidIx = "17.550".levelIx(market),
            minBidIx = "17.300".levelIx(market),
            bestOfferIx = "17.700".levelIx(market),
            maxOfferIx = "17.700".levelIx(market),
        )
    }

    private fun addOrder(guid: Long, orderType: Order.Type, amount: String, price: String, expectedDisposition: OrderDisposition = OrderDisposition.Accepted, counterOrderGuid: Long? = null) {
        val response = market.applyOrderBatch(
            orderBatch {
                this.marketId = market.id.value
                this.wallet = 1L
                this.ordersToAdd.add(
                    order {
                        this.guid = guid
                        this.type = orderType
                        this.amount = BigInteger(amount).toIntegerValue()
                        this.price = BigDecimal(price).toDecimalValue()
                    },
                )
            },
            FeeRates(FeeRate(1), FeeRate(1)),
        )
        if (response.createdTrades.isNotEmpty()) {
            if (orderType == Order.Type.MarketSell || orderType == Order.Type.LimitSell) {
                assertEquals(guid, response.createdTrades.first().sellOrderGuid)
                assertEquals(counterOrderGuid, response.createdTrades.first().buyOrderGuid)
            } else {
                assertEquals(guid, response.createdTrades.first().buyOrderGuid)
                assertEquals(counterOrderGuid, response.createdTrades.first().sellOrderGuid)
            }
        }
        assertEquals(
            expectedDisposition,
            response.ordersChanged.first { it.guid == guid }.disposition,
        )
    }

    @Test
    fun testPercentageCalculations() {
        addOrder(1L, Order.Type.LimitBuy, "50", "17.550")
        // buy 50% of limit of 20
        assertEquals(BigInteger("10"), market.calculateAmountForPercentageSell(WalletAddress(2L), BigInteger("20"), 50))
        // buy 100% of limit of 20
        assertEquals(BigInteger("20"), market.calculateAmountForPercentageSell(WalletAddress(2L), BigInteger("20"), 100))
        // buy 100% of limit of 60 (only 50 available)
        assertEquals(BigInteger("50"), market.calculateAmountForPercentageSell(WalletAddress(2L), BigInteger("60"), 100))
        addOrder(2L, Order.Type.LimitBuy, "50", "17.550")
        // buy 100% of limit of 60 - should get all since enough at different levels
        assertEquals(BigInteger("60"), market.calculateAmountForPercentageSell(WalletAddress(2L), BigInteger("60"), 100))

        // add some limit sells
        addOrder(3L, Order.Type.LimitSell, BigDecimal("20").toFundamentalUnits(market.baseDecimals).toString(), "20.000")
        addOrder(4L, Order.Type.LimitSell, BigDecimal("20").toFundamentalUnits(market.baseDecimals).toString(), "21.000")
        val feeRate = FeeRate.fromPercents(2.0)

        // this should be filled from first level
        var quoteLimit = BigDecimal("200").toFundamentalUnits(market.quoteDecimals)
        var expectedAmount = BigDecimal("9.803921568627450980").toFundamentalUnits(market.baseDecimals)
        val (amount, maxAvailable) = market.calculateAmountForPercentageBuy(WalletAddress(2L), quoteLimit, 100, feeRate.value.toBigInteger())
        assertEquals(expectedAmount, amount)
        assertEquals(quoteLimit, maxAvailable)
        assertTrue(quoteLimit > notionalPlusFee(expectedAmount, BigDecimal("20"), market.baseDecimals, market.quoteDecimals, feeRate))
        // just some tiny dust left.
        assertEquals(BigInteger("8"), quoteLimit - notionalPlusFee(expectedAmount, BigDecimal("20"), market.baseDecimals, market.quoteDecimals, feeRate))

        expectedAmount = BigDecimal("4.901960784313725490").toFundamentalUnits(market.baseDecimals)
        val (amount2, maxAvailable2) = market.calculateAmountForPercentageBuy(WalletAddress(2L), quoteLimit, 50, feeRate.value.toBigInteger())
        assertEquals(expectedAmount, amount2)
        assertNull(maxAvailable2)

        assertTrue(quoteLimit / BigInteger.TWO > notionalPlusFee(expectedAmount, BigDecimal("20"), market.baseDecimals, market.quoteDecimals, feeRate))
        // just some tiny dust left plus the other half.
        assertEquals(BigInteger("4"), quoteLimit / BigInteger.TWO - notionalPlusFee(expectedAmount, BigDecimal("20"), market.baseDecimals, market.quoteDecimals, feeRate))

        // this should require both levels
        quoteLimit = BigDecimal("500").toFundamentalUnits(market.quoteDecimals)
        expectedAmount = BigDecimal("24.295051353874883286").toFundamentalUnits(market.baseDecimals)
        val (amount3, maxAvailable3) = market.calculateAmountForPercentageBuy(WalletAddress(2L), quoteLimit, 100, feeRate.value.toBigInteger())
        assertEquals(expectedAmount, amount3)
        assertEquals(quoteLimit, maxAvailable3)
        val expectedNotional = notionalPlusFee(BigDecimal("20").toFundamentalUnits(market.baseDecimals), BigDecimal("20"), market.baseDecimals, market.quoteDecimals, feeRate) +
            notionalPlusFee(BigDecimal("4.295051353874883286").toFundamentalUnits(market.baseDecimals), BigDecimal("21"), market.baseDecimals, market.quoteDecimals, feeRate)
        assertTrue(quoteLimit > expectedNotional)
        // just some tiny dust left.
        assertEquals(BigInteger("14"), quoteLimit - expectedNotional)
    }

    @Test
    fun `testPercentageCalculations quote and base have different decimals`() {
        market = Market(
            id = MarketId("BTC/USDC"),
            tickSize = BigDecimal("10"),
            maxOrdersPerLevel = 100,
            baseDecimals = 18,
            quoteDecimals = 6,
        )
        addOrder(1L, Order.Type.LimitBuy, "50", "69000")
        // buy 50% of limit of 20
        assertEquals(BigInteger("10"), market.calculateAmountForPercentageSell(WalletAddress(2L), BigInteger("20"), 50))
        // buy 100% of limit of 20
        assertEquals(BigInteger("20"), market.calculateAmountForPercentageSell(WalletAddress(2L), BigInteger("20"), 100))
        // buy 100% of limit of 60 (only 50 available)
        assertEquals(BigInteger("50"), market.calculateAmountForPercentageSell(WalletAddress(2L), BigInteger("60"), 100))
        addOrder(2L, Order.Type.LimitBuy, "50", "69000")
        // buy 100% of limit of 60 - should get all since enough at different levels
        assertEquals(BigInteger("60"), market.calculateAmountForPercentageSell(WalletAddress(2L), BigInteger("60"), 100))

        // add some limit sells
        addOrder(3L, Order.Type.LimitSell, BigDecimal("20").toFundamentalUnits(market.baseDecimals).toString(), "70000.000")
        addOrder(4L, Order.Type.LimitSell, BigDecimal("20").toFundamentalUnits(market.baseDecimals).toString(), "71000.000")
        val feeRate = FeeRate.fromPercents(2.0)

        // this should be filled from first level
        var quoteLimit = BigDecimal("700000").toFundamentalUnits(market.quoteDecimals)
        var expectedAmount = BigDecimal("9.803921568614285714").toFundamentalUnits(market.baseDecimals)
        assertEquals(expectedAmount, market.calculateAmountForPercentageBuy(WalletAddress(2L), quoteLimit, 100, feeRate.value.toBigInteger()).first)
        assertTrue(quoteLimit > notionalPlusFee(expectedAmount, BigDecimal("70000"), market.baseDecimals, market.quoteDecimals, feeRate))
        // just some tiny dust left.
        assertEquals(BigInteger("2"), quoteLimit - notionalPlusFee(expectedAmount, BigDecimal("70000"), market.baseDecimals, market.quoteDecimals, feeRate))

        expectedAmount = BigDecimal("4.901960784300000000").toFundamentalUnits(market.baseDecimals)
        assertEquals(expectedAmount, market.calculateAmountForPercentageBuy(WalletAddress(2L), quoteLimit, 50, feeRate.value.toBigInteger()).first)
        assertTrue(quoteLimit / BigInteger.TWO > notionalPlusFee(expectedAmount, BigDecimal("70000"), market.baseDecimals, market.quoteDecimals, feeRate))
        // just some tiny dust left plus the other half.
        assertEquals(BigInteger("1"), quoteLimit / BigInteger.TWO - notionalPlusFee(expectedAmount, BigDecimal("70000"), market.baseDecimals, market.quoteDecimals, feeRate))

        // this should require both levels
        quoteLimit = BigDecimal("2000000").toFundamentalUnits(market.quoteDecimals)
        expectedAmount = BigDecimal("27.898370615845070422").toFundamentalUnits(market.baseDecimals)
        assertEquals(expectedAmount, market.calculateAmountForPercentageBuy(WalletAddress(2L), quoteLimit, 100, feeRate.value.toBigInteger()).first)
        val expectedNotional = notionalPlusFee(BigDecimal("20").toFundamentalUnits(market.baseDecimals), BigDecimal("70000"), market.baseDecimals, market.quoteDecimals, feeRate) +
            notionalPlusFee(BigDecimal("7.898370615845070422").toFundamentalUnits(market.baseDecimals), BigDecimal("71000"), market.baseDecimals, market.quoteDecimals, feeRate)
        assertTrue(quoteLimit > expectedNotional)
        // just some tiny dust left.
        assertEquals(BigInteger("2"), quoteLimit - expectedNotional)
    }

    @Test
    fun `test min fee`() {
        market = Market(
            id = MarketId("BTC/USDC"),
            tickSize = BigDecimal("10"),
            maxOrdersPerLevel = 100,
            baseDecimals = 8,
            quoteDecimals = 6,
            minFee = BigDecimal("0.02").toFundamentalUnits(6),
        )

        addOrder(1L, Order.Type.LimitBuy, "50", "69000", expectedDisposition = OrderDisposition.Rejected)
        addOrder(2L, Order.Type.LimitBuy, BigDecimal("2").toFundamentalUnits(market.baseDecimals).toString(), "69000", expectedDisposition = OrderDisposition.Accepted)
        addOrder(3L, Order.Type.LimitSell, "50", "70000.000", expectedDisposition = OrderDisposition.Rejected)
        addOrder(4L, Order.Type.LimitSell, BigDecimal("2").toFundamentalUnits(market.baseDecimals).toString(), "71000.000", expectedDisposition = OrderDisposition.Accepted)

        addOrder(5L, Order.Type.MarketBuy, "50", "0", expectedDisposition = OrderDisposition.Rejected)
        addOrder(6L, Order.Type.MarketBuy, BigDecimal("1").toFundamentalUnits(market.baseDecimals).toString(), "0", expectedDisposition = OrderDisposition.Filled, counterOrderGuid = 4)
        addOrder(7L, Order.Type.MarketSell, "50", "0", expectedDisposition = OrderDisposition.Rejected)
        addOrder(8L, Order.Type.MarketSell, BigDecimal("1").toFundamentalUnits(market.baseDecimals).toString(), "0", expectedDisposition = OrderDisposition.Filled, counterOrderGuid = 2)
    }

    private fun updateOrder(guid: Long, orderType: Order.Type, amount: String, price: String, expectedDisposition: OrderDisposition = OrderDisposition.Accepted, counterOrderGuid: Long? = null) {
        val response = market.applyOrderBatch(
            orderBatch {
                this.marketId = market.id.value
                this.wallet = 1L
                this.ordersToChange.add(
                    order {
                        this.guid = guid
                        this.type = orderType
                        this.amount = BigInteger(amount).toIntegerValue()
                        this.price = BigDecimal(price).toDecimalValue()
                    },
                )
            },
            FeeRates(FeeRate(1), FeeRate(1)),
        )
        if (response.createdTrades.isNotEmpty()) {
            if (orderType == Order.Type.MarketSell || orderType == Order.Type.LimitSell) {
                assertEquals(response.createdTrades.first().sellOrderGuid, guid)
                assertEquals(response.createdTrades.first().buyOrderGuid, counterOrderGuid)
            } else {
                assertEquals(response.createdTrades.first().buyOrderGuid, guid)
                assertEquals(response.createdTrades.first().sellOrderGuid, counterOrderGuid)
            }
        }
        assertEquals(
            expectedDisposition,
            response.ordersChanged.first { it.guid == guid }.disposition,
        )
    }

    private fun cancelOrders(guids: List<Long>) {
        assertEquals(
            setOf(OrderDisposition.Canceled),
            market.applyOrderBatch(
                orderBatch {
                    this.marketId = market.id.value
                    this.wallet = 1L
                    this.ordersToCancel.addAll(
                        guids.map {
                            co.chainring.sequencer.proto.cancelOrder {
                                this.guid = it
                            }
                        },
                    )
                },
                FeeRates(FeeRate(1), FeeRate(1)),
            ).ordersChanged.map { it.disposition }.toSet(),
        )
    }

    private fun validateBidAndOffer(bestBidIx: Int, minBidIx: Int, bestOfferIx: Int, maxOfferIx: Int) {
        validateBid(bestBidIx, minBidIx)
        validateOffer(bestOfferIx, maxOfferIx)
    }

    private fun validateBid(bestBidIx: Int, minBidIx: Int) {
        assertEquals(bestBidIx, market.bestBidIx)
        assertEquals(minBidIx, market.minBidIx)
    }

    private fun validateOffer(bestOfferIx: Int, maxOfferIx: Int) {
        assertEquals(bestOfferIx, market.bestOfferIx)
        assertEquals(maxOfferIx, market.maxOfferIx)
    }
}
