package co.chainring

import co.chainring.sequencer.core.MarketId
import co.chainring.sequencer.core.WalletAddress
import co.chainring.sequencer.core.toAsset
import co.chainring.sequencer.core.toBigDecimal
import co.chainring.sequencer.core.toBigInteger
import co.chainring.sequencer.core.toDecimalValue
import co.chainring.sequencer.core.toIntegerValue
import co.chainring.sequencer.core.toOrderGuid
import co.chainring.sequencer.core.toWalletAddress
import co.chainring.sequencer.proto.BalanceChange
import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.OrderChangeRejected
import co.chainring.sequencer.proto.OrderDisposition
import co.chainring.sequencer.proto.SequencerError
import co.chainring.sequencer.proto.SequencerResponse
import co.chainring.sequencer.proto.newQuantityOrNull
import co.chainring.testutils.SequencerClient
import co.chainring.testutils.inSats
import co.chainring.testutils.inWei
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.math.pow
import kotlin.random.Random

class TestSequencer {

    @Test
    fun `Test basic order matching`() {
        val sequencer = SequencerClient()
        val marketId = MarketId("BTC1/ETH1")
        sequencer.createMarket(marketId)
        sequencer.setFeeRates(makerFeeRatInBps = 100, takerFeeRateInBps = 200)

        val maker = 123456789L.toWalletAddress()
        val taker = 555111555L.toWalletAddress()
        // maker deposits some of both assets -- 1 BTC, 1 ETH
        sequencer.deposit(maker, marketId.baseAsset(), BigDecimal.ONE.inSats())
        sequencer.deposit(maker, marketId.quoteAsset(), BigDecimal.ONE.inWei())
        // place an order and see that it gets accepted
        val response = sequencer.addOrder(marketId, BigDecimal("0.00012345").inSats(), "17.500", maker, Order.Type.LimitBuy)
        assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)

        // place a sell order
        val response2 = sequencer.addOrder(marketId, BigDecimal("0.00054321").inSats(), "17.550", maker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response2.ordersChangedList.first().disposition)

        // place a market buy and see that it gets executed
        sequencer.deposit(taker, marketId.quoteAsset(), BigDecimal.ONE.inWei())
        val response3 = sequencer.addOrder(marketId, BigDecimal("0.00043210").inSats(), null, taker, Order.Type.MarketBuy)
        assertEquals(2, response3.ordersChangedCount)
        val takerOrder = response3.ordersChangedList[0]
        assertEquals(OrderDisposition.Filled, takerOrder.disposition)
        val makerOrder = response3.ordersChangedList[1]
        assertEquals(OrderDisposition.PartiallyFilled, makerOrder.disposition)
        assertEquals(response2.orderGuid(), makerOrder.guid)
        val trade = response3.tradesCreatedList.first()
        assertEquals("17.550".toBigDecimal().toDecimalValue(), trade.price)
        assertEquals(BigDecimal("0.00043210").inSats().toIntegerValue(), trade.amount)
        assertEquals(makerOrder.guid, trade.sellOrderGuid)
        assertEquals(takerOrder.guid, trade.buyOrderGuid)
        assertEquals(BigDecimal("0.00015166710").inWei(), trade.buyerFee.toBigInteger())
        assertEquals(BigDecimal("0.00007583355").inWei(), trade.sellerFee.toBigInteger())

        // each of the maker and taker should have two balance changed messages, one for each asset
        assertEquals(4, response3.balancesChangedCount)
        val makerBalanceChanges = response3.balancesChangedList.filter { it.wallet == maker.value }
        val takerBalanceChanges = response3.balancesChangedList.filter { it.wallet == taker.value }
        assertEquals(2, makerBalanceChanges.size)
        assertEquals(2, takerBalanceChanges.size)
        val makerBaseBalanceChange = makerBalanceChanges.find { it.asset == marketId.baseAsset().value }!!
        val makerQuoteBalanceChange = makerBalanceChanges.find { it.asset == marketId.quoteAsset().value }!!
        assertEquals(-BigDecimal("0.00043210").inSats(), makerBaseBalanceChange.delta.toBigInteger())
        assertEquals(BigDecimal("0.00750752145").inWei(), makerQuoteBalanceChange.delta.toBigInteger())

        val takerBaseBalanceChange = takerBalanceChanges.find { it.asset == marketId.baseAsset().value }!!
        val takerQuoteBalanceChange = takerBalanceChanges.find { it.asset == marketId.quoteAsset().value }!!
        assertEquals(BigDecimal("0.00043210").inSats(), takerBaseBalanceChange.delta.toBigInteger())
        assertEquals(-BigDecimal("0.0077350221").inWei(), takerQuoteBalanceChange.delta.toBigInteger())
        // balances now should be:
        //   maker BTC1 = 1 - 0.00043210 = 0.99956790
        //         ETH1 = 1 + .00750752145 = 1.00750752145
        //   taker BTC1 = 0.00043210
        //         ETH1 = 1 - .0077350221 = 0.9922649779

        // now try a market sell which can only be partially filled and see that it gets executed
        val response4 = sequencer.addOrder(marketId, BigDecimal("0.00012346").inSats(), null, taker, Order.Type.MarketSell)
        assertEquals(2, response4.ordersChangedCount)
        val takerOrder2 = response4.ordersChangedList[0]
        assertEquals(OrderDisposition.PartiallyFilled, takerOrder2.disposition)
        val makerOrder2 = response4.ordersChangedList[1]
        assertEquals(OrderDisposition.Filled, makerOrder2.disposition)
        val trade2 = response4.tradesCreatedList.first()
        assertEquals("17.500".toBigDecimal().toDecimalValue(), trade2.price)
        assertEquals(BigDecimal("0.00012345").inSats().toIntegerValue(), trade2.amount)
        assertEquals(makerOrder2.guid, trade2.buyOrderGuid)
        assertEquals(takerOrder2.guid, trade2.sellOrderGuid)
        assertEquals(BigDecimal("0.00002160375").inWei(), trade2.buyerFee.toBigInteger())
        assertEquals(BigDecimal("0.00004320750").inWei(), trade2.sellerFee.toBigInteger())
        // verify the remaining balances for maker and taker (withdraw a large amount - returned balance change will
        // indicate what the balance was)
        // expected balances:
        //
        //   maker BTC1 = 0.00956790 + .00012345 = 0.99969135
        //         ETH1 = 1.00750752145 - 0.00218197875 = 1.0053255427
        //   taker BTC1 = 0.00043210 - 0.00012345 = 0.00030865
        //         ETH1 = 0.9922649779 + 0.0021171675 = 0.9943821454
        sequencer.withdrawal(maker, marketId.baseAsset(), BigDecimal.TEN.inSats(), BigDecimal("0.99969135").inSats())
        sequencer.withdrawal(maker, marketId.quoteAsset(), BigDecimal.TEN.inWei(), BigDecimal("1.0053255427").inWei())
        sequencer.withdrawal(taker, marketId.baseAsset(), BigDecimal.TEN.inSats(), BigDecimal("0.00030865").inSats())
        sequencer.withdrawal(taker, marketId.quoteAsset(), BigDecimal.TEN.inWei(), BigDecimal("0.9943821454").inWei())
    }

    private fun SequencerResponse.orderGuid() = this.ordersChangedList.first().guid

    @Test
    fun `Test a market order that executes against multiple orders at multiple levels`() {
        val sequencer = SequencerClient()
        val marketId = MarketId("BTC2/ETH2")
        sequencer.createMarket(marketId)
        sequencer.setFeeRates(makerFeeRatInBps = 100, takerFeeRateInBps = 200)

        val lp1 = 123457689L.toWalletAddress()
        val lp2 = 987654321L.toWalletAddress()
        val tkr = 555555555L.toWalletAddress()
        sequencer.deposit(lp1, marketId.baseAsset(), BigInteger.valueOf(31000))
        sequencer.deposit(lp2, marketId.baseAsset(), BigInteger.valueOf(31000))
        val sell1 = sequencer.addOrder(marketId, BigInteger.valueOf(1000), "17.550", lp1, Order.Type.LimitSell)
        val sell2 = sequencer.addOrder(marketId, BigInteger.valueOf(1000), "17.550", lp2, Order.Type.LimitSell)
        val sell3 = sequencer.addOrder(marketId, BigInteger.valueOf(10000), "17.600", lp1, Order.Type.LimitSell)
        val sell4 = sequencer.addOrder(marketId, BigInteger.valueOf(10000), "17.600", lp2, Order.Type.LimitSell)
        val sell5 = sequencer.addOrder(marketId, BigInteger.valueOf(20000), "17.700", lp1, Order.Type.LimitSell)
        val sell6 = sequencer.addOrder(marketId, BigInteger.valueOf(20000), "17.700", lp2, Order.Type.LimitSell)
        // clearing price would be (2000 * 17.55 + 15000 * 17.6) / 17000 = 17.594
        // notional is 17000 * 17.594 * 10^10 = 2990980000000000, fee would be notional * 0,02 = 59819600000000
        sequencer.deposit(tkr, marketId.quoteAsset(), BigInteger("2990980000000000") + BigInteger("59819600000000"))
        val response = sequencer.addOrder(marketId, BigInteger.valueOf(17000), null, tkr, Order.Type.MarketBuy)
        assertEquals(5, response.ordersChangedCount)
        assertEquals(OrderDisposition.Filled, response.ordersChangedList[0].disposition)
        assertEquals(OrderDisposition.Filled, response.ordersChangedList[1].disposition)
        assertEquals(OrderDisposition.Filled, response.ordersChangedList[2].disposition)
        assertEquals(OrderDisposition.Filled, response.ordersChangedList[3].disposition)
        response.ordersChangedList[4].apply {
            assertEquals(OrderDisposition.PartiallyFilled, disposition)
            assertEquals(BigInteger.valueOf(5000), this.newQuantityOrNull?.toBigInteger())
        }

        assertEquals(4, response.tradesCreatedList.size)
        response.tradesCreatedList[0].apply {
            assertEquals(sell1.orderGuid(), this.sellOrderGuid)
            assertEquals(1000, this.amount.toBigInteger().toInt())
            assertEquals("17.550", this.price.toBigDecimal().toString())
            assertEquals(BigDecimal("0.000003510").inWei(), this.buyerFee.toBigInteger())
            assertEquals(BigDecimal("0.000001755").inWei(), this.sellerFee.toBigInteger())
        }
        response.tradesCreatedList[1].apply {
            assertEquals(sell2.orderGuid(), this.sellOrderGuid)
            assertEquals(1000, this.amount.toBigInteger().toInt())
            assertEquals("17.550", this.price.toBigDecimal().toString())
            assertEquals(BigDecimal("0.000003510").inWei(), this.buyerFee.toBigInteger())
            assertEquals(BigDecimal("0.000001755").inWei(), this.sellerFee.toBigInteger())
        }
        response.tradesCreatedList[2].apply {
            assertEquals(sell3.orderGuid(), this.sellOrderGuid)
            assertEquals(10000, this.amount.toBigInteger().toInt())
            assertEquals("17.600", this.price.toBigDecimal().toString())
            assertEquals(BigDecimal("0.0000352").inWei(), this.buyerFee.toBigInteger())
            assertEquals(BigDecimal("0.0000176").inWei(), this.sellerFee.toBigInteger())
        }
        response.tradesCreatedList[3].apply {
            assertEquals(sell4.orderGuid(), this.sellOrderGuid)
            assertEquals(5000, this.amount.toBigInteger().toInt())
            assertEquals("17.600", this.price.toBigDecimal().toString())
            assertEquals(BigDecimal("0.0000176").inWei(), this.buyerFee.toBigInteger())
            assertEquals(BigDecimal("0.0000088").inWei(), this.sellerFee.toBigInteger())
        }

        // place another market order to exhaust remaining limit orders
        // clearing price would be (5000 * 17.6 + 40000 * 17.7) / 45000 = 17.689
        // notional is 45000 * 17.689 * 10^10, fee would be notional * 0,02 = 159201000000000
        sequencer.deposit(tkr, marketId.quoteAsset(), BigInteger("7960050000000000") + BigInteger("159201000000000"))
        val response2 = sequencer.addOrder(marketId, BigInteger.valueOf(45000), null, tkr, Order.Type.MarketBuy)
        assertEquals(4, response2.ordersChangedCount)
        assertEquals(OrderDisposition.Filled, response2.ordersChangedList[0].disposition)
        assertEquals(OrderDisposition.Filled, response2.ordersChangedList[1].disposition)
        assertEquals(OrderDisposition.Filled, response2.ordersChangedList[2].disposition)
        assertEquals(OrderDisposition.Filled, response2.ordersChangedList[3].disposition)

        assertEquals(3, response2.tradesCreatedList.size)
        response2.tradesCreatedList[0].apply {
            assertEquals(sell4.orderGuid(), this.sellOrderGuid)
            assertEquals(5000, this.amount.toBigInteger().toInt())
            assertEquals("17.600", this.price.toBigDecimal().toString())
            assertEquals(BigDecimal("0.0000176").inWei(), this.buyerFee.toBigInteger())
            assertEquals(BigDecimal("0.0000088").inWei(), this.sellerFee.toBigInteger())
        }
        response2.tradesCreatedList[1].apply {
            assertEquals(sell5.orderGuid(), this.sellOrderGuid)
            assertEquals(20000, this.amount.toBigInteger().toInt())
            assertEquals("17.700", this.price.toBigDecimal().toString())
            assertEquals(BigDecimal("0.0000708").inWei(), this.buyerFee.toBigInteger())
            assertEquals(BigDecimal("0.0000354").inWei(), this.sellerFee.toBigInteger())
        }
        response2.tradesCreatedList[2].apply {
            assertEquals(sell6.orderGuid(), this.sellOrderGuid)
            assertEquals(20000, this.amount.toBigInteger().toInt())
            assertEquals("17.700", this.price.toBigDecimal().toString())
            assertEquals(BigDecimal("0.0000708").inWei(), this.buyerFee.toBigInteger())
            assertEquals(BigDecimal("0.0000354").inWei(), this.sellerFee.toBigInteger())
        }
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

    @Test
    fun `test limit checking on orders`() {
        val sequencer = SequencerClient()
        sequencer.setFeeRates(makerFeeRatInBps = 100, takerFeeRateInBps = 200)
        val marketId1 = MarketId("BTC3/ETH3")
        sequencer.createMarket(marketId1)

        val rnd = Random(0)

        val maker = rnd.nextLong().toWalletAddress()
        // cannot place a buy or sell limit order without any deposits
        assertEquals(SequencerError.ExceedsLimit, sequencer.addOrder(marketId1, BigInteger.valueOf(1000), "17.55", maker, Order.Type.LimitSell).error)
        assertEquals(SequencerError.ExceedsLimit, sequencer.addOrder(marketId1, BigInteger.valueOf(1000), "17.50", maker, Order.Type.LimitBuy).error)

        // deposit some base and can sell
        sequencer.deposit(maker, marketId1.baseAsset(), BigInteger.valueOf(1000))
        val response = sequencer.addOrder(marketId1, BigInteger.valueOf(1000), "17.55", maker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)

        // deposit some quote, but still can't buy because of the fee
        sequencer.deposit(maker, marketId1.quoteAsset(), BigDecimal.valueOf(17.50 * 1000 * 10.0.pow(10)).toBigInteger())
        assertEquals(SequencerError.ExceedsLimit, sequencer.addOrder(marketId1, BigInteger.valueOf(1000), "17.50", maker, Order.Type.LimitBuy).error)

        // can buy after depositing more quote to cover the fee
        sequencer.deposit(maker, marketId1.quoteAsset(), BigDecimal.valueOf(17.50 * 10 * 10.0.pow(10)).toBigInteger())
        val response2 = sequencer.addOrder(marketId1, BigInteger.valueOf(1000), "17.50", maker, Order.Type.LimitBuy)
        assertEquals(OrderDisposition.Accepted, response2.ordersChangedList.first().disposition)

        // but now that we've exhausted our balance we can't add more
        assertEquals(SequencerError.ExceedsLimit, sequencer.addOrder(marketId1, BigInteger.ONE, "17.55", maker, Order.Type.LimitSell).error)
        assertEquals(SequencerError.ExceedsLimit, sequencer.addOrder(marketId1, BigInteger.ONE, "0.05", maker, Order.Type.LimitBuy).error)

        // but we can reuse the same liquidity in another market
        val marketId2 = MarketId("ETH3/USDC3")
        sequencer.createMarket(marketId2, baseDecimals = 18, quoteDecimals = 6)
        val response3 = sequencer.addOrder(marketId2, BigInteger.valueOf(1000), "17.50", maker, Order.Type.LimitBuy)
        assertEquals(OrderDisposition.Accepted, response3.ordersChangedList.first().disposition)

        // if we deposit some more we can add another order
        sequencer.deposit(maker, marketId1.baseAsset(), BigInteger.TEN)
        val response4 = sequencer.addOrder(marketId1, BigInteger.TEN, "17.60", maker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response4.ordersChangedList.first().disposition)

        // but not more
        assertEquals(SequencerError.ExceedsLimit, sequencer.addOrder(marketId1, BigInteger.ONE, "17.55", maker, Order.Type.LimitSell).error)

        // unless a trade increases the balance
        val taker = rnd.nextLong().toWalletAddress()
        sequencer.deposit(taker, marketId1.baseAsset(), BigInteger.TEN)
        val response5 = sequencer.addOrder(marketId1, BigInteger.TEN, null, taker, Order.Type.MarketSell)
        assertEquals(OrderDisposition.Filled, response5.ordersChangedList.first().disposition)

        val response6 = sequencer.addOrder(marketId1, BigInteger.ONE, "17.60", maker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response6.ordersChangedList.first().disposition)
    }

    @Test
    fun `test LimitBuy order can cross the market`() {
        val sequencer = SequencerClient()
        sequencer.setFeeRates(makerFeeRatInBps = 100, takerFeeRateInBps = 200)
        val marketId = MarketId("BTC8/ETH8")
        sequencer.createMarket(marketId)

        val rnd = Random(0)

        val maker = rnd.nextLong().toWalletAddress()
        // deposit and prepare liquidity
        sequencer.deposit(maker, marketId.baseAsset(), BigInteger.valueOf(2000))
        val response = sequencer.addOrder(marketId, BigInteger.valueOf(2000), "17.55", maker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)

        // prepare second maker
        val crossingTheMarketMaker = rnd.nextLong().toWalletAddress()
        sequencer.deposit(crossingTheMarketMaker, marketId.quoteAsset(), BigDecimal.valueOf(18.00 * 3000 * 10.0.pow(10)).toBigInteger())
        // deposit extra for the fees
        sequencer.deposit(crossingTheMarketMaker, marketId.quoteAsset(), (BigDecimal("0.000003510") * BigDecimal(2)).inWei())

        // limit order can cross the market and be filled immediately
        val response2 = sequencer.addOrder(marketId, BigInteger.valueOf(1000), "18.00", crossingTheMarketMaker, Order.Type.LimitBuy)
        val r2TakerOrder = response2.ordersChangedList.first()
        val r2MakerOrder = response2.ordersChangedList.last()
        val r2trade = response2.tradesCreatedList.first()
        assertEquals(OrderDisposition.Filled, r2TakerOrder.disposition)
        assertEquals(OrderDisposition.PartiallyFilled, r2MakerOrder.disposition)
        assertEquals("17.550".toBigDecimal().toDecimalValue(), r2trade.price)
        assertEquals(BigDecimal("0.00001").inSats().toIntegerValue(), r2trade.amount)
        assertEquals(r2MakerOrder.guid, r2trade.sellOrderGuid)
        assertEquals(BigDecimal("0.000001755").inWei(), r2trade.sellerFee.toBigInteger())
        assertEquals(r2TakerOrder.guid, r2trade.buyOrderGuid)
        assertEquals(BigDecimal("0.000003510").inWei(), r2trade.buyerFee.toBigInteger())
        verifyBalanceChanges(
            marketId = marketId,
            balancesChangedList = response2.balancesChangedList,
            makerWallet = maker,
            takerWallet = crossingTheMarketMaker,
            expectedMakerBaseBalanceChange = -BigDecimal("0.00001").inSats(),
            expectedMakerQuoteBalanceChange = BigDecimal("0.000173745").inWei(),
            expectedTakerBaseBalanceChange = BigDecimal("0.00001").inSats(),
            expectedTakerQuoteBalanceChange = -BigDecimal("0.00017901").inWei(),
        )

        // or filled partially with remaining limit amount stays on the book
        val response3 = sequencer.addOrder(marketId, BigInteger.valueOf(2000), "18.00", crossingTheMarketMaker, Order.Type.LimitBuy)
        val r3TakerOrder = response3.ordersChangedList.first()
        val r3MakerOrder = response3.ordersChangedList.last()
        assertEquals(OrderDisposition.PartiallyFilled, r3TakerOrder.disposition)
        assertEquals(OrderDisposition.Filled, r3MakerOrder.disposition)
        val r3trade = response3.tradesCreatedList.first()
        assertEquals("17.550".toBigDecimal().toDecimalValue(), r3trade.price)
        assertEquals(BigDecimal("0.00001").inSats().toIntegerValue(), r3trade.amount)
        assertEquals(r3MakerOrder.guid, r3trade.sellOrderGuid)
        assertEquals(BigDecimal("0.000001755").inWei(), r3trade.sellerFee.toBigInteger())
        assertEquals(r3TakerOrder.guid, r3trade.buyOrderGuid)
        assertEquals(BigDecimal("0.000003510").inWei(), r3trade.buyerFee.toBigInteger())
        verifyBalanceChanges(
            marketId = marketId,
            balancesChangedList = response2.balancesChangedList,
            makerWallet = maker,
            takerWallet = crossingTheMarketMaker,
            expectedMakerBaseBalanceChange = -BigDecimal("0.00001").inSats(),
            expectedMakerQuoteBalanceChange = BigDecimal("0.000173745").inWei(),
            expectedTakerBaseBalanceChange = BigDecimal("0.00001").inSats(),
            expectedTakerQuoteBalanceChange = -BigDecimal("0.00017901").inWei(),
        )
    }

    @Test
    fun `test LimitBuy order can cross the market filling LimitSell orders at multiple levels until limit price`() {
        val sequencer = SequencerClient()
        sequencer.setFeeRates(makerFeeRatInBps = 100, takerFeeRateInBps = 200)
        val marketId = MarketId("BTC9/ETH9")
        sequencer.createMarket(marketId)

        val rnd = Random(0)

        val maker = rnd.nextLong().toWalletAddress()
        // deposit and prepare liquidity
        sequencer.deposit(maker, marketId.baseAsset(), BigInteger.valueOf(2000))
        val sell1 = sequencer.addOrder(marketId, BigInteger.valueOf(100), "17.55", maker, Order.Type.LimitSell)
        val sell2 = sequencer.addOrder(marketId, BigInteger.valueOf(100), "18.00", maker, Order.Type.LimitSell)
        val sell3 = sequencer.addOrder(marketId, BigInteger.valueOf(100), "18.50", maker, Order.Type.LimitSell)
        sequencer.addOrder(marketId, BigInteger.valueOf(100), "19.00", maker, Order.Type.LimitSell)
        sequencer.addOrder(marketId, BigInteger.valueOf(100), "19.50", maker, Order.Type.LimitSell)

        // prepare second maker
        val crossingTheMarketMaker = rnd.nextLong().toWalletAddress()
        sequencer.deposit(crossingTheMarketMaker, marketId.quoteAsset(), BigDecimal.valueOf(18.2101 * 500 * 10.0.pow(10)).toBigInteger())
        assertEquals(SequencerError.ExceedsLimit, sequencer.addOrder(marketId, BigInteger.valueOf(500), "18.50", crossingTheMarketMaker, Order.Type.LimitBuy).error)

        val expectedBuyerFeesForFilledAmount = (BigDecimal("0.0000003510") + BigDecimal("0.00000036") + BigDecimal("0.000000370")).inWei()
        val expectedBuyerFeesForRemainingAmount = BigDecimal.valueOf(19.00 * 200 * 10.0.pow(10)).toBigInteger()

        // limit check passes on lower deposited amount due to partial filling by market price
        sequencer.deposit(
            crossingTheMarketMaker,
            marketId.quoteAsset(),
            BigDecimal.valueOf(0.0001 * 500 * 10.0.pow(10)).toBigInteger() + expectedBuyerFeesForFilledAmount + expectedBuyerFeesForRemainingAmount,
        )

        // limit order is partially filled until limit price is reached
        val response2 = sequencer.addOrder(marketId, BigInteger.valueOf(500), "18.50", crossingTheMarketMaker, Order.Type.LimitBuy)
        assertEquals(4, response2.ordersChangedCount)
        assertEquals(OrderDisposition.PartiallyFilled, response2.ordersChangedList[0].disposition)
        assertEquals(OrderDisposition.Filled, response2.ordersChangedList[1].disposition)
        assertEquals(OrderDisposition.Filled, response2.ordersChangedList[2].disposition)
        assertEquals(OrderDisposition.Filled, response2.ordersChangedList[3].disposition)

        assertEquals(3, response2.tradesCreatedList.size)
        response2.tradesCreatedList[0].apply {
            assertEquals(sell1.orderGuid(), this.sellOrderGuid)
            assertEquals(response2.orderGuid(), this.buyOrderGuid)
            assertEquals(100, this.amount.toBigInteger().toInt())
            assertEquals("17.550", this.price.toBigDecimal().toString())
            assertEquals(BigDecimal("0.0000001755").inWei(), this.sellerFee.toBigInteger())
            assertEquals(BigDecimal("0.0000003510").inWei(), this.buyerFee.toBigInteger())
        }
        response2.tradesCreatedList[1].apply {
            assertEquals(sell2.orderGuid(), this.sellOrderGuid)
            assertEquals(response2.orderGuid(), this.buyOrderGuid)
            assertEquals(100, this.amount.toBigInteger().toInt())
            assertEquals("18.000", this.price.toBigDecimal().toString())
            assertEquals(BigDecimal("0.00000018").inWei(), this.sellerFee.toBigInteger())
            assertEquals(BigDecimal("0.00000036").inWei(), this.buyerFee.toBigInteger())
        }
        response2.tradesCreatedList[2].apply {
            assertEquals(sell3.orderGuid(), this.sellOrderGuid)
            assertEquals(response2.orderGuid(), this.buyOrderGuid)
            assertEquals(100, this.amount.toBigInteger().toInt())
            assertEquals("18.500", this.price.toBigDecimal().toString())
            assertEquals(BigDecimal("0.000000185").inWei(), this.sellerFee.toBigInteger())
            assertEquals(BigDecimal("0.000000370").inWei(), this.buyerFee.toBigInteger())
        }

        verifyBalanceChanges(
            marketId = marketId,
            balancesChangedList = response2.balancesChangedList,
            makerWallet = maker,
            takerWallet = crossingTheMarketMaker,
            expectedMakerBaseBalanceChange = -BigDecimal("0.000003").inSats(),
            expectedMakerQuoteBalanceChange = BigDecimal("0.0000535095").inWei(),
            expectedTakerBaseBalanceChange = BigDecimal("0.000003").inSats(),
            expectedTakerQuoteBalanceChange = -(BigDecimal("0.00005405").inWei() + expectedBuyerFeesForFilledAmount),
        )
    }

    @Test
    fun `test LimitSell order can cross the market`() {
        val sequencer = SequencerClient()
        sequencer.setFeeRates(makerFeeRatInBps = 100, takerFeeRateInBps = 200)
        val marketId = MarketId("BTC10/ETH10")
        sequencer.createMarket(marketId)

        val rnd = Random(0)

        val maker = rnd.nextLong().toWalletAddress()
        // deposit and prepare liquidity
        sequencer.deposit(maker, marketId.quoteAsset(), BigDecimal.valueOf(17.50 * 2000 * 10.0.pow(10)).toBigInteger())
        // deposit extra for the fees
        sequencer.deposit(maker, marketId.quoteAsset(), (BigDecimal("0.00001750") * BigDecimal(2)).inWei())
        val response1 = sequencer.addOrder(marketId, BigInteger.valueOf(2000), "17.50", maker, Order.Type.LimitBuy)
        assertEquals(OrderDisposition.Accepted, response1.ordersChangedList.first().disposition)

        // prepare second maker
        val crossingTheMarketMaker = rnd.nextLong().toWalletAddress()
        sequencer.deposit(crossingTheMarketMaker, marketId.baseAsset(), BigInteger.valueOf(3000))

        // limit order can cross the market and be filled immediately
        val response2 = sequencer.addOrder(marketId, BigInteger.valueOf(1000), "17.00", crossingTheMarketMaker, Order.Type.LimitSell)
        assertEquals(2, response2.ordersChangedList.size)
        val r2TakerOrder = response2.ordersChangedList.first()
        val r2MakerOrder = response2.ordersChangedList.last()
        val r2trade = response2.tradesCreatedList.first()
        assertEquals(OrderDisposition.Filled, r2TakerOrder.disposition)
        assertEquals(OrderDisposition.PartiallyFilled, r2MakerOrder.disposition)
        assertEquals("17.500".toBigDecimal().toDecimalValue(), r2trade.price)
        assertEquals(BigDecimal("0.00001").inSats().toIntegerValue(), r2trade.amount)
        assertEquals(r2MakerOrder.guid, r2trade.buyOrderGuid)
        assertEquals(BigDecimal("0.000001750").inWei(), r2trade.buyerFee.toBigInteger())
        assertEquals(r2TakerOrder.guid, r2trade.sellOrderGuid)
        assertEquals(BigDecimal("0.000003500").inWei(), r2trade.sellerFee.toBigInteger())
        verifyBalanceChanges(
            marketId = marketId,
            balancesChangedList = response2.balancesChangedList,
            makerWallet = maker,
            takerWallet = crossingTheMarketMaker,
            expectedMakerBaseBalanceChange = BigDecimal("0.00001").inSats(),
            expectedMakerQuoteBalanceChange = -BigDecimal("0.00017675").inWei(),
            expectedTakerBaseBalanceChange = -BigDecimal("0.00001").inSats(),
            expectedTakerQuoteBalanceChange = BigDecimal("0.000171500").inWei(),
        )

        // or filled partially with remaining limit amount stays on the book
        val response3 = sequencer.addOrder(marketId, BigInteger.valueOf(2000), "17.00", crossingTheMarketMaker, Order.Type.LimitSell)
        assertEquals(2, response3.ordersChangedList.size)
        val r3TakerOrder = response3.ordersChangedList.first()
        val r3MakerOrder = response3.ordersChangedList.last()
        assertEquals(OrderDisposition.PartiallyFilled, r3TakerOrder.disposition)
        assertEquals(OrderDisposition.Filled, r3MakerOrder.disposition)
        val r3trade = response3.tradesCreatedList.first()
        assertEquals("17.500".toBigDecimal().toDecimalValue(), r3trade.price)
        assertEquals(BigDecimal("0.00001").inSats().toIntegerValue(), r3trade.amount)
        assertEquals(r3MakerOrder.guid, r3trade.buyOrderGuid)
        assertEquals(BigDecimal("0.000001750").inWei(), r3trade.buyerFee.toBigInteger())
        assertEquals(r3TakerOrder.guid, r3trade.sellOrderGuid)
        assertEquals(BigDecimal("0.000003500").inWei(), r3trade.sellerFee.toBigInteger())
        verifyBalanceChanges(
            marketId = marketId,
            balancesChangedList = response2.balancesChangedList,
            makerWallet = maker,
            takerWallet = crossingTheMarketMaker,
            expectedMakerBaseBalanceChange = BigDecimal("0.00001").inSats(),
            expectedMakerQuoteBalanceChange = -BigDecimal("0.00017675").inWei(),
            expectedTakerBaseBalanceChange = -BigDecimal("0.00001").inSats(),
            expectedTakerQuoteBalanceChange = BigDecimal("0.000171500").inWei(),
        )
    }

    @Test
    fun `test LimitSell order can cross the market filling LimitBuy orders at multiple levels until limit price`() {
        val sequencer = SequencerClient()
        sequencer.setFeeRates(makerFeeRatInBps = 100, takerFeeRateInBps = 200)
        val marketId = MarketId("BTC11/ETH11")
        sequencer.createMarket(marketId)

        val rnd = Random(0)

        val maker = rnd.nextLong().toWalletAddress()
        // deposit and prepare liquidity
        sequencer.deposit(maker, marketId.quoteAsset(), BigDecimal.valueOf(17.50 * 2000 * 10.0.pow(10)).toBigInteger())
        val buy1 = sequencer.addOrder(marketId, BigInteger.valueOf(100), "17.50", maker, Order.Type.LimitBuy)
        val buy2 = sequencer.addOrder(marketId, BigInteger.valueOf(100), "17.00", maker, Order.Type.LimitBuy)
        val buy3 = sequencer.addOrder(marketId, BigInteger.valueOf(100), "16.50", maker, Order.Type.LimitBuy)
        sequencer.addOrder(marketId, BigInteger.valueOf(100), "16.00", maker, Order.Type.LimitBuy)
        sequencer.addOrder(marketId, BigInteger.valueOf(100), "15.50", maker, Order.Type.LimitBuy)

        // prepare second maker
        val crossingTheMarketMaker = rnd.nextLong().toWalletAddress()
        sequencer.deposit(crossingTheMarketMaker, marketId.baseAsset(), BigInteger.valueOf(3000))

        // limit order is partially filled until price is reached
        val response2 = sequencer.addOrder(marketId, BigInteger.valueOf(500), "16.50", crossingTheMarketMaker, Order.Type.LimitSell)
        assertEquals(4, response2.ordersChangedCount)
        assertEquals(OrderDisposition.PartiallyFilled, response2.ordersChangedList[0].disposition)
        assertEquals(OrderDisposition.Filled, response2.ordersChangedList[1].disposition)
        assertEquals(OrderDisposition.Filled, response2.ordersChangedList[2].disposition)
        assertEquals(OrderDisposition.Filled, response2.ordersChangedList[3].disposition)

        assertEquals(3, response2.tradesCreatedList.size)
        response2.tradesCreatedList[0].apply {
            assertEquals(buy1.orderGuid(), this.buyOrderGuid)
            assertEquals(response2.orderGuid(), this.sellOrderGuid)
            assertEquals(100, this.amount.toBigInteger().toInt())
            assertEquals("17.500", this.price.toBigDecimal().toString())
            assertEquals(BigDecimal("0.0000003500").inWei(), this.sellerFee.toBigInteger())
            assertEquals(BigDecimal("0.0000001750").inWei(), this.buyerFee.toBigInteger())
        }
        response2.tradesCreatedList[1].apply {
            assertEquals(buy2.orderGuid(), this.buyOrderGuid)
            assertEquals(response2.orderGuid(), this.sellOrderGuid)
            assertEquals(100, this.amount.toBigInteger().toInt())
            assertEquals("17.000", this.price.toBigDecimal().toString())
            assertEquals(BigDecimal("0.0000003400").inWei(), this.sellerFee.toBigInteger())
            assertEquals(BigDecimal("0.0000001700").inWei(), this.buyerFee.toBigInteger())
        }
        response2.tradesCreatedList[2].apply {
            assertEquals(buy3.orderGuid(), this.buyOrderGuid)
            assertEquals(response2.orderGuid(), this.sellOrderGuid)
            assertEquals(100, this.amount.toBigInteger().toInt())
            assertEquals("16.500", this.price.toBigDecimal().toString())
            assertEquals(BigDecimal("0.0000003300").inWei(), this.sellerFee.toBigInteger())
            assertEquals(BigDecimal("0.0000001650").inWei(), this.buyerFee.toBigInteger())
        }

        verifyBalanceChanges(
            marketId = marketId,
            balancesChangedList = response2.balancesChangedList,
            makerWallet = maker,
            takerWallet = crossingTheMarketMaker,
            expectedMakerBaseBalanceChange = BigDecimal("0.000003").inSats(),
            expectedMakerQuoteBalanceChange = -BigDecimal("0.00005151").inWei(),
            expectedTakerBaseBalanceChange = -BigDecimal("0.000003").inSats(),
            expectedTakerQuoteBalanceChange = BigDecimal("0.000049980").inWei(),
        )
    }

    @Test
    fun `test order cancel`() {
        val sequencer = SequencerClient()
        sequencer.setFeeRates(makerFeeRatInBps = 100, takerFeeRateInBps = 200)
        val marketId = MarketId("BTC4/ETH4")
        sequencer.createMarket(marketId)

        val rnd = Random(0)

        val maker = rnd.nextLong().toWalletAddress()
        sequencer.deposit(maker, marketId.baseAsset(), BigInteger.valueOf(1000))
        val response = sequencer.addOrder(marketId, BigInteger.valueOf(1000), "17.55", maker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
        val response2 = sequencer.cancelOrder(marketId, response.orderGuid(), maker)
        assertEquals(OrderDisposition.Canceled, response2.ordersChangedList.first().disposition)
        // try canceling an order which has been partially filled
        val response3 = sequencer.addOrder(marketId, BigInteger.valueOf(1000), "17.55", maker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response3.ordersChangedList.first().disposition)

        val taker = rnd.nextLong().toWalletAddress()
        sequencer.deposit(taker, marketId.quoteAsset(), BigDecimal.ONE.inWei())
        val response4 = sequencer.addOrder(marketId, BigInteger.valueOf(500), null, taker, Order.Type.MarketBuy)
        assertEquals(OrderDisposition.Filled, response4.ordersChangedList.first().disposition)

        // have taker try to cancel maker order
        val response5 = sequencer.cancelOrder(marketId, response3.orderGuid(), taker)
        assertEquals(0, response5.ordersChangedList.size)
        assertEquals(1, response5.ordersChangeRejectedList.size)
        assertEquals(OrderChangeRejected.Reason.NotForWallet, response5.ordersChangeRejectedList.first().reason)

        val response6 = sequencer.cancelOrder(marketId, response3.orderGuid(), maker)
        assertEquals(OrderDisposition.Canceled, response6.ordersChangedList.first().disposition)

        // cancel an invalid order
        val response7 = sequencer.cancelOrder(marketId, response3.orderGuid() + 2, maker)
        assertEquals(0, response7.ordersChangedList.size)
        assertEquals(1, response7.ordersChangeRejectedList.size)
        assertEquals(OrderChangeRejected.Reason.DoesNotExist, response7.ordersChangeRejectedList.first().reason)
    }

    @Test
    fun `test order change`() {
        val sequencer = SequencerClient()
        sequencer.setFeeRates(makerFeeRatInBps = 100, takerFeeRateInBps = 200)
        val marketId = MarketId("BTC5/ETH5")
        sequencer.createMarket(marketId)

        val rnd = Random(0)

        val maker = rnd.nextLong().toWalletAddress()
        sequencer.deposit(maker, marketId.baseAsset(), BigInteger.valueOf(1000))
        val response = sequencer.addOrder(marketId, BigInteger.valueOf(1000), "17.55", maker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)

        // reduce amount
        val response2 = sequencer.changeOrder(marketId, response.orderGuid().toOrderGuid(), 999L, "17.55", maker)
        assertEquals(OrderDisposition.Accepted, response2.ordersChangedList.first().disposition)

        // cannot increase amount beyond 1000L since there is not enough collateral
        assertEquals(SequencerError.ExceedsLimit, sequencer.changeOrder(marketId, response.orderGuid().toOrderGuid(), 1001L, "17.55", maker).error)

        // but can change price
        val response3 = sequencer.changeOrder(marketId, response.orderGuid().toOrderGuid(), 999L, "17.60", maker)
        assertEquals(OrderDisposition.Accepted, response3.ordersChangedList.first().disposition)

        // can also add a new order for 1
        val response4 = sequencer.addOrder(marketId, BigInteger.ONE, "17.55", maker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response4.ordersChangedList.first().disposition)

        // can change the price to cross the market, disposition is 'Accepted' since there is no liquidity yet available in the market
        val response5 = sequencer.changeOrder(marketId, response.orderGuid().toOrderGuid(), 999L, "15.50", maker)
        assertEquals(OrderDisposition.Accepted, response5.ordersChangedList.first().disposition)

        // check for a limit buy
        sequencer.deposit(maker, marketId.quoteAsset(), BigDecimal("10.1").inWei())
        val response6 = sequencer.addOrder(marketId, BigDecimal.ONE.inSats(), "10.00", maker, Order.Type.LimitBuy)
        assertEquals(OrderDisposition.Accepted, response6.ordersChangedList.first().disposition)

        // cannot increase amount since we have consumed all the collateral
        assertEquals(
            SequencerError.ExceedsLimit,
            sequencer.changeOrder(
                marketId,
                response6.orderGuid().toOrderGuid(),
                BigDecimal.ONE.inSats().toLong() + 1,
                "10.00",
                maker,
            ).error,
        )

        // also cannot increase price
        assertEquals(
            SequencerError.ExceedsLimit,
            sequencer.changeOrder(
                marketId,
                response6.orderGuid().toOrderGuid(),
                BigDecimal.ONE.inSats().toLong(),
                "10.05",
                maker,
            ).error,
        )

        // but can decrease amount or decrease price
        val response7 = sequencer.changeOrder(marketId, response6.orderGuid().toOrderGuid(), BigDecimal.ONE.inSats().toLong() - 1, "10.00", maker)
        assertEquals(OrderDisposition.Accepted, response7.ordersChangedList.first().disposition)

        val response8 = sequencer.changeOrder(marketId, response6.orderGuid().toOrderGuid(), BigDecimal.ONE.inSats().toLong(), "9.95", maker)
        assertEquals(OrderDisposition.Accepted, response8.ordersChangedList.first().disposition)

        // different wallet try to update an order
        val taker = rnd.nextLong().toWalletAddress()
        val response9 = sequencer.changeOrder(marketId, response6.orderGuid().toOrderGuid(), BigDecimal.ONE.inSats().toLong() - 1, "9.95", taker)
        assertEquals(0, response9.ordersChangedList.size)
        assertEquals(1, response9.ordersChangeRejectedList.size)
        assertEquals(OrderChangeRejected.Reason.NotForWallet, response9.ordersChangeRejectedList.first().reason)

        // update an invalid order.
        val response10 = sequencer.changeOrder(marketId, (response6.orderGuid() + 1).toOrderGuid(), BigDecimal.ONE.inSats().toLong() - 1, "9.95", maker)
        assertEquals(0, response10.ordersChangedList.size)
        assertEquals(1, response10.ordersChangeRejectedList.size)
        assertEquals(OrderChangeRejected.Reason.DoesNotExist, response10.ordersChangeRejectedList.first().reason)
    }

    @Test
    fun `test order change when new price crosses the market`() {
        val sequencer = SequencerClient()
        sequencer.setFeeRates(makerFeeRatInBps = 100, takerFeeRateInBps = 200)
        val marketId = MarketId("BTC12/ETH12")
        sequencer.createMarket(marketId)

        val rnd = Random(0)

        // onboard maker and prepare market
        val maker = rnd.nextLong().toWalletAddress()
        sequencer.deposit(maker, marketId.baseAsset(), BigInteger.valueOf(2000))
        sequencer.deposit(maker, marketId.quoteAsset(), BigDecimal.valueOf(17.50 * 2000 * 10.0.pow(10)).toBigInteger())
        // deposit extra for the fees
        sequencer.deposit(maker, marketId.quoteAsset(), (BigDecimal("0.000001750") + BigDecimal("0.000001755")).inWei())
        val m1sell2 = sequencer.addOrder(marketId, BigInteger.valueOf(1000), "18.00", maker, Order.Type.LimitSell)
        val m1sell1 = sequencer.addOrder(marketId, BigInteger.valueOf(1000), "17.55", maker, Order.Type.LimitSell)
        val m1buy1 = sequencer.addOrder(marketId, BigInteger.valueOf(1000), "17.50", maker, Order.Type.LimitBuy)
        val m1buy2 = sequencer.addOrder(marketId, BigInteger.valueOf(1000), "17.25", maker, Order.Type.LimitBuy)

        // onboard another maker
        val anotherMaker = rnd.nextLong().toWalletAddress()
        sequencer.deposit(anotherMaker, marketId.baseAsset(), BigInteger.valueOf(2000))
        sequencer.deposit(anotherMaker, marketId.quoteAsset(), BigDecimal.valueOf(17.7 * 2000 * 10.0.pow(10)).toBigInteger())
        // deposit extra for the fees
        sequencer.deposit(maker, marketId.quoteAsset(), (BigDecimal("0.000003500") + BigDecimal("0.000003510")).inWei())
        val m2sell1 = sequencer.addOrder(marketId, BigInteger.valueOf(2000), "18.00", anotherMaker, Order.Type.LimitSell)
        val m2buy1 = sequencer.addOrder(marketId, BigInteger.valueOf(2000), "17.00", anotherMaker, Order.Type.LimitBuy)

        // verify setup is successful
        listOf(m1buy1, m1buy2, m1sell1, m1sell2, m2buy1, m2sell1).forEach { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
        }

        // update limit sell order to cross the market. Results in immediate partial execution.
        val m2sell1ChangeResponse = sequencer.changeOrder(marketId, m2sell1.orderGuid().toOrderGuid(), 2000, "17.30", anotherMaker)
        assertEquals(OrderDisposition.PartiallyFilled, m2sell1ChangeResponse.ordersChangedList.first().disposition)
        assertEquals(2, m2sell1ChangeResponse.ordersChangedCount)
        assertEquals(OrderDisposition.PartiallyFilled, m2sell1ChangeResponse.ordersChangedList[0].disposition)
        assertEquals(OrderDisposition.Filled, m2sell1ChangeResponse.ordersChangedList[1].disposition)
        assertEquals(1, m2sell1ChangeResponse.tradesCreatedCount)

        m2sell1ChangeResponse.tradesCreatedList[0].apply {
            assertEquals(m1buy1.orderGuid(), this.buyOrderGuid)
            assertEquals(m2sell1ChangeResponse.orderGuid(), this.sellOrderGuid)
            assertEquals(1000, this.amount.toBigInteger().toInt())
            assertEquals("17.500", this.price.toBigDecimal().toString())
            assertEquals(BigDecimal("0.000003500").inWei(), this.sellerFee.toBigInteger())
            assertEquals(BigDecimal("0.000001750").inWei(), this.buyerFee.toBigInteger())
        }
        verifyBalanceChanges(
            marketId = marketId,
            balancesChangedList = m2sell1ChangeResponse.balancesChangedList,
            makerWallet = maker,
            takerWallet = anotherMaker,
            expectedMakerBaseBalanceChange = BigDecimal("0.00001").inSats(),
            expectedMakerQuoteBalanceChange = -BigDecimal("0.00017675").inWei(),
            expectedTakerBaseBalanceChange = -BigDecimal("0.00001").inSats(),
            expectedTakerQuoteBalanceChange = BigDecimal("0.000171500").inWei(),
        )

        // now cancel own order (limit sell 17.30) to avoid matching
        sequencer.cancelOrder(marketId, m2sell1ChangeResponse.orderGuid(), anotherMaker)

        // update limit buy order to cross the market (immediate partial execution)
        val m2buy1ChangeResponse = sequencer.changeOrder(marketId, m2buy1.orderGuid().toOrderGuid(), 2000, "17.75", anotherMaker)
        assertEquals(2, m2buy1ChangeResponse.ordersChangedCount)
        assertEquals(OrderDisposition.PartiallyFilled, m2buy1ChangeResponse.ordersChangedList[0].disposition)
        assertEquals(OrderDisposition.Filled, m2buy1ChangeResponse.ordersChangedList[1].disposition)
        assertEquals(1, m2buy1ChangeResponse.tradesCreatedCount)
        m2buy1ChangeResponse.tradesCreatedList[0].apply {
            assertEquals(m1sell1.orderGuid(), this.sellOrderGuid)
            assertEquals(m2buy1ChangeResponse.orderGuid(), this.buyOrderGuid)
            assertEquals(1000, this.amount.toBigInteger().toInt())
            assertEquals("17.550", this.price.toBigDecimal().toString())
            assertEquals(BigDecimal("0.000001755").inWei(), this.sellerFee.toBigInteger())
            assertEquals(BigDecimal("0.000003510").inWei(), this.buyerFee.toBigInteger())
        }
        verifyBalanceChanges(
            marketId = marketId,
            balancesChangedList = m2buy1ChangeResponse.balancesChangedList,
            makerWallet = maker,
            takerWallet = anotherMaker,
            expectedMakerBaseBalanceChange = -BigDecimal("0.00001").inSats(),
            expectedMakerQuoteBalanceChange = BigDecimal("0.000173745").inWei(),
            expectedTakerBaseBalanceChange = BigDecimal("0.00001").inSats(),
            expectedTakerQuoteBalanceChange = -BigDecimal("0.00017901").inWei(),
        )
    }

    @Test
    fun `test auto-reduce from trades`() {
        val sequencer = SequencerClient()
        sequencer.setFeeRates(makerFeeRatInBps = 100, takerFeeRateInBps = 200)

        val marketId1 = MarketId("BTC6/ETH6")
        sequencer.createMarket(marketId1, tickSize = BigDecimal.ONE, marketPrice = BigDecimal.valueOf(10.5), baseDecimals = 8, quoteDecimals = 18)

        val marketId2 = MarketId("ETH6/USDC6")
        sequencer.createMarket(marketId2, tickSize = BigDecimal.ONE, marketPrice = BigDecimal.valueOf(9.5), baseDecimals = 18, quoteDecimals = 6)

        val marketId3 = MarketId("XXX6/ETH6")
        sequencer.createMarket(marketId3, tickSize = BigDecimal.ONE, marketPrice = BigDecimal.valueOf(20.5), baseDecimals = 1, quoteDecimals = 18)

        val rnd = Random(0)

        val maker = rnd.nextLong().toWalletAddress()

        // maker deposits 10.1 ETH
        sequencer.deposit(maker, marketId1.quoteAsset(), BigDecimal("10.1").inWei())

        // maker adds a bid in market1 using all 10.1 eth
        val response1 = sequencer.addOrder(marketId1, BigDecimal.ONE.inSats(), "10", maker, Order.Type.LimitBuy)
        assertEquals(OrderDisposition.Accepted, response1.ordersChangedList.first().disposition)

        // maker adds an offer in market2 using all 10.1 eth
        val response2 = sequencer.addOrder(marketId2, BigDecimal.TEN.inWei(), "10", maker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response2.ordersChangedList.first().disposition)

        // maker also adds a bid in market3 using all 10.1 eth
        val response3 = sequencer.addOrder(marketId3, BigInteger.valueOf(5), "20", maker, Order.Type.LimitBuy)
        assertEquals(OrderDisposition.Accepted, response3.ordersChangedList.first().disposition)

        // now add a taker who will hit the market1 bid selling 0.6 BTC, this would consume 6 ETH + 0,06 ETH fee from maker
        val taker = rnd.nextLong().toWalletAddress()
        sequencer.deposit(taker, marketId1.baseAsset(), BigDecimal.valueOf(0.6).inSats())
        val response4 = sequencer.addOrder(marketId1, BigDecimal.valueOf(0.6).inSats(), null, taker, Order.Type.MarketSell)
        assertEquals(OrderDisposition.Filled, response4.ordersChangedList.first().disposition)

        // the maker's offer in market2 should be auto-reduced
        val reducedOffer = response4.ordersChangedList.find { it.guid == response2.orderGuid() }!!
        assertEquals(OrderDisposition.AutoReduced, reducedOffer.disposition)
        assertEquals(BigDecimal("4.04").inWei(), reducedOffer.newQuantity.toBigInteger())

        // also the maker's bid in market3 should be auto-reduced
        val reducedBid = response4.ordersChangedList.find { it.guid == response3.orderGuid() }!!
        assertEquals(OrderDisposition.AutoReduced, reducedBid.disposition)
        assertEquals(BigInteger.valueOf(2), reducedBid.newQuantity.toBigInteger())
    }

    @Test
    fun `test auto-reduce from withdrawals`() {
        val sequencer = SequencerClient()
        sequencer.setFeeRates(makerFeeRatInBps = 100, takerFeeRateInBps = 200)
        val marketId = MarketId("BTC7/ETH7")
        sequencer.createMarket(marketId)

        val rnd = Random(0)

        val maker = rnd.nextLong().toWalletAddress()
        // maker deposits 10 BTC
        sequencer.deposit(maker, marketId.baseAsset(), BigDecimal.TEN.inSats())
        // maker adds two offers combined which use all 10 BTC
        val response1 = sequencer.addOrder(marketId, BigDecimal(4).inSats(), "17.75", maker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response1.ordersChangedList.first().disposition)
        val response2 = sequencer.addOrder(marketId, BigDecimal(6).inSats(), "18.00", maker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response2.ordersChangedList.first().disposition)

        // now maker withdraws 7 BTC
        val response3 = sequencer.withdrawal(maker, marketId.baseAsset(), BigDecimal(7).inSats())
        val reducedOffer1 = response3.ordersChangedList.find { it.guid == response1.orderGuid() }!!
        assertEquals(OrderDisposition.AutoReduced, reducedOffer1.disposition)
        assertEquals(BigDecimal(3).inSats(), reducedOffer1.newQuantity.toBigInteger())
        val reducedOffer2 = response3.ordersChangedList.find { it.guid == response2.orderGuid() }!!
        assertEquals(OrderDisposition.AutoReduced, reducedOffer2.disposition)
        assertEquals(BigInteger.ZERO, reducedOffer2.newQuantity.toBigInteger())
    }

    @Test
    fun `fee rate change does not affect existing orders in the book`() {
        val sequencer = SequencerClient()
        // set maker fee rate to 1% and taker fee rate to 2%
        sequencer.setFeeRates(makerFeeRatInBps = 100, takerFeeRateInBps = 200)
        val marketId = MarketId("BTC8/ETH8")
        sequencer.createMarket(marketId)

        val rnd = Random(0)

        val maker = rnd.nextLong().toWalletAddress()
        val taker = rnd.nextLong().toWalletAddress()

        sequencer.deposit(maker, marketId.baseAsset(), BigDecimal("10").inSats())
        sequencer.deposit(taker, marketId.quoteAsset(), BigDecimal("200").inWei())

        val sellOrder1Guid = sequencer.addOrder(marketId, BigDecimal(5).inSats(), "10.00", maker, Order.Type.LimitSell).let { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.ordersChangedList.first().guid
        }

        // increase maker fee rate to 2% and taker fee rate to 4%
        sequencer.setFeeRates(makerFeeRatInBps = 200, takerFeeRateInBps = 400)

        val sellOrder2Guid = sequencer.addOrder(marketId, BigDecimal(5).inSats(), "10.00", maker, Order.Type.LimitSell).let { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.ordersChangedList.first().guid
        }

        sequencer.addOrder(marketId, BigDecimal(10).inSats(), null, taker, Order.Type.MarketBuy).apply {
            assertEquals(3, this.ordersChangedList.size)
            assertEquals(OrderDisposition.Filled, this.ordersChangedList[0].disposition)
            assertEquals(OrderDisposition.Filled, this.ordersChangedList[1].disposition)
            assertEquals(OrderDisposition.Filled, this.ordersChangedList[2].disposition)

            assertEquals(2, this.tradesCreatedList.size)
            this.tradesCreatedList[0].apply {
                assertEquals(sellOrder1Guid, this.sellOrderGuid)
                assertEquals(BigDecimal(5).inSats(), this.amount.toBigInteger())
                assertEquals("10.000", this.price.toBigDecimal().toString())
                // maker's fee is 1% since first order was created before fee rate increase
                assertEquals(BigDecimal("0.5").inWei(), this.sellerFee.toBigInteger())
                // taker's fee is 4%
                assertEquals(BigDecimal("2.0").inWei(), this.buyerFee.toBigInteger())
            }
            this.tradesCreatedList[1].apply {
                assertEquals(sellOrder2Guid, this.sellOrderGuid)
                assertEquals(BigDecimal(5).inSats(), this.amount.toBigInteger())
                assertEquals("10.000", this.price.toBigDecimal().toString())
                // maker's fee is 2% since second order was created after fee rate increase
                assertEquals(BigDecimal("1.0").inWei(), this.sellerFee.toBigInteger())
                // taker's fee is 4%
                assertEquals(BigDecimal("2.0").inWei(), this.buyerFee.toBigInteger())
            }
        }
    }

    @Test
    fun `test failed withdrawals`() {
        val sequencer = SequencerClient()
        sequencer.setFeeRates(makerFeeRatInBps = 100, takerFeeRateInBps = 200)

        val rnd = Random(0)
        val walletAddress = rnd.nextLong().toWalletAddress()
        val asset = "ETH".toAsset()
        val amount = BigInteger.valueOf(1000)

        // do a deposit
        sequencer.deposit(walletAddress, asset, amount)

        // withdraw half
        sequencer.withdrawal(walletAddress, asset, amount / BigInteger.TWO)

        // withdraw other half
        sequencer.withdrawal(walletAddress, asset, amount / BigInteger.TWO)

        // fail the 2 withdrawals
        sequencer.failedWithdrawals(walletAddress, asset, listOf(amount / BigInteger.TWO, amount / BigInteger.TWO))

        // should still be able to withdraw full amount since we rolled back the 2 halves
        sequencer.withdrawal(walletAddress, asset, amount)
    }

    @Test
    fun `Test failed settlements`() {
        val sequencer = SequencerClient()
        sequencer.setFeeRates(makerFeeRatInBps = 100, takerFeeRateInBps = 200)

        val marketId = MarketId("BTC8/ETH8")
        sequencer.createMarket(marketId)

        val maker = 123456789L.toWalletAddress()
        val taker = 555111555L.toWalletAddress()

        // maker deposits some of both assets -- 2 BTC, 2 ETH
        val makerBaseBalance = BigDecimal("2").inSats()
        val makerQuoteBalance = BigDecimal("2").inWei()
        sequencer.deposit(maker, marketId.baseAsset(), makerBaseBalance)
        sequencer.deposit(maker, marketId.quoteAsset(), makerQuoteBalance)

        // place an order and see that it gets accepted
        val response = sequencer.addOrder(marketId, BigDecimal("0.00012345").inSats(), "17.500", maker, Order.Type.LimitBuy)
        assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)

        // place a sell order
        val response2 = sequencer.addOrder(marketId, BigDecimal("0.00054321").inSats(), "17.550", maker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response2.ordersChangedList.first().disposition)

        // place a market buy and see that it gets executed
        sequencer.deposit(taker, marketId.quoteAsset(), BigDecimal.ONE.inWei())
        sequencer.deposit(taker, marketId.baseAsset(), BigDecimal.ONE.inSats())

        val response3 = sequencer.addOrder(marketId, BigDecimal("0.00043210").inSats(), null, taker, Order.Type.MarketBuy)
        assertEquals(2, response3.ordersChangedCount)
        val takerOrder = response3.ordersChangedList[0]
        assertEquals(OrderDisposition.Filled, takerOrder.disposition)
        val makerOrder = response3.ordersChangedList[1]
        assertEquals(OrderDisposition.PartiallyFilled, makerOrder.disposition)
        assertEquals(response2.orderGuid(), makerOrder.guid)
        val trade = response3.tradesCreatedList.first()
        assertEquals("17.550".toBigDecimal().toDecimalValue(), trade.price)
        assertEquals(BigDecimal("0.00043210").inSats().toIntegerValue(), trade.amount)
        assertEquals(makerOrder.guid, trade.sellOrderGuid)
        assertEquals(takerOrder.guid, trade.buyOrderGuid)

        // each of the maker and taker should have two balance changed messages, one for each asset
        verifyBalanceChanges(
            marketId = marketId,
            balancesChangedList = response3.balancesChangedList,
            makerWallet = maker,
            takerWallet = taker,
            expectedMakerBaseBalanceChange = -BigDecimal("0.00043210").inSats(),
            expectedMakerQuoteBalanceChange = BigDecimal("0.00750752145").inWei(),
            expectedTakerBaseBalanceChange = BigDecimal("0.00043210").inSats(),
            expectedTakerQuoteBalanceChange = -BigDecimal("0.0077350221").inWei(),
        )

        // now rollback the settlement - all the balances should be back to their original values
        val failedSettlementResponse = sequencer.failedSettlement(taker, maker, marketId, trade)
        verifyBalanceChanges(
            marketId = marketId,
            balancesChangedList = failedSettlementResponse.balancesChangedList,
            makerWallet = maker,
            takerWallet = taker,
            expectedMakerBaseBalanceChange = BigDecimal("0.00043210").inSats(),
            expectedMakerQuoteBalanceChange = -BigDecimal("0.00750752145").inWei(),
            expectedTakerBaseBalanceChange = -BigDecimal("0.00043210").inSats(),
            expectedTakerQuoteBalanceChange = BigDecimal("0.007735022100").inWei(),
        )
        // all balances should be back to original values
        sequencer.withdrawal(maker, marketId.baseAsset(), BigDecimal.TEN.inSats(), makerBaseBalance)
        sequencer.withdrawal(maker, marketId.quoteAsset(), BigDecimal.TEN.inWei(), makerQuoteBalance)
        sequencer.withdrawal(taker, marketId.baseAsset(), BigDecimal.TEN.inSats(), BigDecimal.ONE.inSats())
        sequencer.withdrawal(taker, marketId.quoteAsset(), BigDecimal.TEN.inWei(), BigDecimal.ONE.inWei())
    }

    @Test
    fun `Test autoreduce on failed settlements`() {
        val sequencer = SequencerClient()
        sequencer.setFeeRates(makerFeeRatInBps = 100, takerFeeRateInBps = 200)

        val marketId = MarketId("BTC9/ETH9")
        sequencer.createMarket(marketId)

        val maker = 123456789L.toWalletAddress()
        val taker = 555111555L.toWalletAddress()

        // maker deposits some of both assets -- 2 BTC, 2 ETH
        val makerBaseBalance = BigDecimal("2").inSats()
        val makerQuoteBalance = BigDecimal("10").inWei()
        sequencer.deposit(maker, marketId.baseAsset(), makerBaseBalance)
        sequencer.deposit(maker, marketId.quoteAsset(), makerQuoteBalance)

        // place a sell order
        val response = sequencer.addOrder(marketId, BigDecimal("2").inSats(), "17.550", maker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)

        // place a market buy and see that it gets executed
        sequencer.deposit(taker, marketId.quoteAsset(), BigDecimal("40").inWei())
        sequencer.deposit(taker, marketId.baseAsset(), BigDecimal.ONE.inSats())

        val response2 = sequencer.addOrder(marketId, BigDecimal("1").inSats(), null, taker, Order.Type.MarketBuy)
        assertEquals(2, response2.ordersChangedCount)
        val takerOrder = response2.ordersChangedList[0]
        assertEquals(OrderDisposition.Filled, takerOrder.disposition)
        val makerOrder = response2.ordersChangedList[1]
        assertEquals(OrderDisposition.PartiallyFilled, makerOrder.disposition)
        assertEquals(response.orderGuid(), makerOrder.guid)
        val trade = response2.tradesCreatedList.first()
        assertEquals("17.550".toBigDecimal().toDecimalValue(), trade.price)
        assertEquals(BigDecimal("1").inSats().toIntegerValue(), trade.amount)
        assertEquals(makerOrder.guid, trade.sellOrderGuid)
        assertEquals(takerOrder.guid, trade.buyOrderGuid)

        // each of the maker and taker should have two balance changed messages, one for each asset
        verifyBalanceChanges(
            marketId = marketId,
            balancesChangedList = response2.balancesChangedList,
            makerWallet = maker,
            takerWallet = taker,
            expectedMakerBaseBalanceChange = -BigDecimal("1").inSats(),
            expectedMakerQuoteBalanceChange = BigDecimal("17.3745").inWei(),
            expectedTakerBaseBalanceChange = BigDecimal("1").inSats(),
            expectedTakerQuoteBalanceChange = -BigDecimal("17.901").inWei(),
        )

        // place an order for taker to sell 2 BTC - they started with 1 and just bought 1, so they have 2 total
        val response3 = sequencer.addOrder(marketId, BigDecimal("2").inSats(), "17.500", taker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response3.ordersChangedList.first().disposition)

        // now rollback the settlement - all the balances should be back to their original values
        val failedSettlementResponse = sequencer.failedSettlement(taker, maker, marketId, trade)
        verifyBalanceChanges(
            marketId = marketId,
            balancesChangedList = failedSettlementResponse.balancesChangedList,
            makerWallet = maker,
            takerWallet = taker,
            expectedMakerBaseBalanceChange = BigDecimal("1").inSats(),
            expectedMakerQuoteBalanceChange = -BigDecimal("17.3745").inWei(),
            expectedTakerBaseBalanceChange = -BigDecimal("1").inSats(),
            expectedTakerQuoteBalanceChange = BigDecimal("17.901").inWei(),
        )
        assertEquals(failedSettlementResponse.ordersChangedList.size, 1)
        val reducedBid = failedSettlementResponse.ordersChangedList.find { it.guid == response3.orderGuid() }!!
        assertEquals(OrderDisposition.AutoReduced, reducedBid.disposition)
        assertEquals(BigDecimal("1").inSats(), reducedBid.newQuantity.toBigInteger())

        // all balances should be back to original values
        sequencer.withdrawal(maker, marketId.baseAsset(), BigDecimal.TEN.inSats(), makerBaseBalance)
        sequencer.withdrawal(maker, marketId.quoteAsset(), BigDecimal("100").inWei(), makerQuoteBalance)
        sequencer.withdrawal(taker, marketId.baseAsset(), BigDecimal.TEN.inSats(), BigDecimal.ONE.inSats())
        sequencer.withdrawal(taker, marketId.quoteAsset(), BigDecimal("100").inWei(), BigDecimal("40").inWei())
    }

    @Test
    fun `Test failed settlements - balances can go negative`() {
        val sequencer = SequencerClient()
        sequencer.setFeeRates(makerFeeRatInBps = 100, takerFeeRateInBps = 200)

        val marketId = MarketId("BTC10/ETH10")
        sequencer.createMarket(marketId)

        val maker = 123456789L.toWalletAddress()
        val taker = 555111555L.toWalletAddress()

        // maker deposits some of both assets -- 2 BTC, 2 ETH
        val makerBaseBalance = BigDecimal("2").inSats()
        val makerQuoteBalance = BigDecimal("2").inWei()
        sequencer.deposit(maker, marketId.baseAsset(), makerBaseBalance)
        sequencer.deposit(maker, marketId.quoteAsset(), makerQuoteBalance)

        // place an order and see that it gets accepted
        val response = sequencer.addOrder(marketId, BigDecimal("0.00012345").inSats(), "17.500", maker, Order.Type.LimitBuy)
        assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)

        // place a sell order
        val response2 = sequencer.addOrder(marketId, BigDecimal("0.00054321").inSats(), "17.550", maker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response2.ordersChangedList.first().disposition)

        // place a market buy and see that it gets executed
        sequencer.deposit(taker, marketId.quoteAsset(), BigDecimal.ONE.inWei())
        sequencer.deposit(taker, marketId.baseAsset(), BigDecimal.ONE.inSats())

        val response3 = sequencer.addOrder(marketId, BigDecimal("0.00043210").inSats(), null, taker, Order.Type.MarketBuy)
        assertEquals(2, response3.ordersChangedCount)
        val takerOrder = response3.ordersChangedList[0]
        assertEquals(OrderDisposition.Filled, takerOrder.disposition)
        val makerOrder = response3.ordersChangedList[1]
        assertEquals(OrderDisposition.PartiallyFilled, makerOrder.disposition)
        assertEquals(response2.orderGuid(), makerOrder.guid)
        val trade = response3.tradesCreatedList.first()
        assertEquals("17.550".toBigDecimal().toDecimalValue(), trade.price)
        assertEquals(BigDecimal("0.00043210").inSats().toIntegerValue(), trade.amount)
        assertEquals(makerOrder.guid, trade.sellOrderGuid)
        assertEquals(takerOrder.guid, trade.buyOrderGuid)

        verifyBalanceChanges(
            marketId = marketId,
            balancesChangedList = response3.balancesChangedList,
            makerWallet = maker,
            takerWallet = taker,
            expectedMakerBaseBalanceChange = -BigDecimal("0.00043210").inSats(),
            expectedMakerQuoteBalanceChange = BigDecimal("0.00750752145").inWei(),
            expectedTakerBaseBalanceChange = BigDecimal("0.00043210").inSats(),
            expectedTakerQuoteBalanceChange = -BigDecimal("0.0077350221").inWei(),
        )

        // balances now should be:
        //   maker BTC1 = 2 - 0.00043210 = 1.99956790
        //         ETH1 = 2 + .00750752145 = 2.00750752145
        //   taker BTC1 = 0.00043210
        //         ETH1 = 1 - .0077350221 = 0.9922649779
        // withdraw everything
        sequencer.withdrawal(maker, marketId.baseAsset(), BigDecimal.TEN.inSats(), BigDecimal("1.999567905").inSats())
        sequencer.withdrawal(maker, marketId.quoteAsset(), BigDecimal.TEN.inWei(), BigDecimal("2.00750752145").inWei())
        sequencer.withdrawal(taker, marketId.baseAsset(), BigDecimal.TEN.inSats(), BigDecimal("1.00043210").inSats())
        sequencer.withdrawal(taker, marketId.quoteAsset(), BigDecimal.TEN.inWei(), BigDecimal("0.9922649779").inWei())

        // now rollback the settlement - some balances go negative (takers base balance, and makers quote balance
        val failedSettlementResponse = sequencer.failedSettlement(taker, maker, marketId, trade)
        verifyBalanceChanges(
            marketId = marketId,
            balancesChangedList = failedSettlementResponse.balancesChangedList,
            makerWallet = maker,
            takerWallet = taker,
            expectedMakerBaseBalanceChange = BigDecimal("0.00043210").inSats(),
            expectedMakerQuoteBalanceChange = -BigDecimal("0.00750752145").inWei(),
            expectedTakerBaseBalanceChange = -BigDecimal("0.00043210").inSats(),
            expectedTakerQuoteBalanceChange = BigDecimal("0.0077350221").inWei(),
        )
        sequencer.withdrawal(maker, marketId.baseAsset(), BigDecimal.TEN.inSats(), BigDecimal("0.00043210").inSats())
        sequencer.withdrawal(taker, marketId.quoteAsset(), BigDecimal.TEN.inWei(), BigDecimal("0.0077350221").inWei())
        // no balance change for these two since they went negative
        sequencer.withdrawal(maker, marketId.quoteAsset(), BigDecimal.TEN.inWei(), null)
        sequencer.withdrawal(taker, marketId.baseAsset(), BigDecimal.TEN.inSats(), null)

        // now deposit to take balance positive and withdraw all to make sure adjustments are properly applied
        sequencer.deposit(maker, marketId.quoteAsset(), BigDecimal.ONE.inWei())
        sequencer.deposit(taker, marketId.baseAsset(), BigDecimal.ONE.inSats())

        sequencer.withdrawal(maker, marketId.quoteAsset(), BigDecimal.TEN.inWei(), BigDecimal("0.99249247855").inWei())
        sequencer.withdrawal(taker, marketId.baseAsset(), BigDecimal.TEN.inSats(), BigDecimal("0.999567905").inSats())
    }

    private fun verifyBalanceChanges(
        marketId: MarketId,
        balancesChangedList: MutableList<BalanceChange>,
        makerWallet: WalletAddress,
        takerWallet: WalletAddress,
        expectedMakerBaseBalanceChange: BigInteger,
        expectedMakerQuoteBalanceChange: BigInteger,
        expectedTakerBaseBalanceChange: BigInteger,
        expectedTakerQuoteBalanceChange: BigInteger,
    ) {
        assertEquals(4, balancesChangedList.size)

        val makerBalanceChanges = balancesChangedList.filter { it.wallet == makerWallet.value }
        val takerBalanceChanges = balancesChangedList.filter { it.wallet == takerWallet.value }
        assertEquals(2, makerBalanceChanges.size)
        assertEquals(2, takerBalanceChanges.size)

        val makerBaseBalanceChange = makerBalanceChanges.find { it.asset == marketId.baseAsset().value }!!
        val makerQuoteBalanceChange = makerBalanceChanges.find { it.asset == marketId.quoteAsset().value }!!
        assertEquals(expectedMakerBaseBalanceChange, makerBaseBalanceChange.delta.toBigInteger(), "Maker base balance change did not match")
        assertEquals(expectedMakerQuoteBalanceChange, makerQuoteBalanceChange.delta.toBigInteger(), "Maker quote balance change did not match")

        val takerBaseBalanceChange = takerBalanceChanges.find { it.asset == marketId.baseAsset().value }!!
        val takerQuoteBalanceChange = takerBalanceChanges.find { it.asset == marketId.quoteAsset().value }!!
        assertEquals(expectedTakerBaseBalanceChange, takerBaseBalanceChange.delta.toBigInteger(), "Taker base balance change did not match")
        assertEquals(expectedTakerQuoteBalanceChange, takerQuoteBalanceChange.delta.toBigInteger(), "Taker base balance change did not match")
    }
}
