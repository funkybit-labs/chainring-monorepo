package co.chainring

import co.chainring.core.model.db.FeeRates
import co.chainring.sequencer.core.MarketId
import co.chainring.sequencer.core.WalletAddress
import co.chainring.sequencer.core.toOrderGuid
import co.chainring.sequencer.core.toWalletAddress
import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.OrderChangeRejected
import co.chainring.sequencer.proto.OrderDisposition
import co.chainring.sequencer.proto.SequencerError
import co.chainring.sequencer.proto.newQuantityOrNull
import co.chainring.testutils.ExpectedTrade
import co.chainring.testutils.SequencerClient
import co.chainring.testutils.assertBalanceChanges
import co.chainring.testutils.assertTrades
import co.chainring.testutils.fromFundamentalUnits
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.random.Random

class TestSequencer {
    @Test
    fun `Test basic order matching`() {
        val sequencer = SequencerClient()
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))

        val market = sequencer.createMarket(MarketId("BTC1/ETH1"))
        val btc1 = market.baseAsset
        val eth1 = market.quoteAsset

        val maker = generateWalletAddress()
        sequencer.deposit(maker, btc1, BigDecimal("10"))
        sequencer.deposit(maker, eth1, BigDecimal("10"))

        // place a buy order
        sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.1"), BigDecimal("10.000"), maker, Order.Type.LimitBuy)

        // place a sell order
        val makerSellOrderGuid = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.5"), BigDecimal("12.000"), maker, Order.Type.LimitSell).guid

        val taker = generateWalletAddress()
        sequencer.deposit(taker, eth1, BigDecimal("10"))

        // place a market buy and see that it gets executed
        sequencer.addOrder(market, BigDecimal("0.2"), null, taker, Order.Type.MarketBuy).also { response ->
            assertEquals(2, response.ordersChangedCount)

            val takerOrder = response.ordersChangedList[0]
            assertEquals(OrderDisposition.Filled, takerOrder.disposition)

            val makerOrder = response.ordersChangedList[1]
            assertEquals(makerSellOrderGuid, makerOrder.guid)
            assertEquals(OrderDisposition.PartiallyFilled, makerOrder.disposition)

            response.assertTrades(
                market,
                listOf(
                    ExpectedTrade(
                        buyOrderGuid = takerOrder.guid,
                        sellOrderGuid = makerOrder.guid,
                        price = BigDecimal("12.000"),
                        amount = BigDecimal("0.2"),
                        buyerFee = BigDecimal("0.048"),
                        sellerFee = BigDecimal("0.024"),
                    ),
                ),
            )

            response.assertBalanceChanges(
                market,
                listOf(
                    Triple(taker, eth1, -BigDecimal("2.448")),
                    Triple(maker, btc1, -BigDecimal("0.2")),
                    Triple(taker, btc1, BigDecimal("0.2")),
                    Triple(maker, eth1, BigDecimal("2.376")),
                ),
            )
            // balances now should be:
            //   maker BTC1 = 10 - 0.2 = 9.8
            //         ETH1 = 10 + 2.4 - 0.024 = 12.376
            //   taker BTC1 = 02
            //         ETH1 = 10 - 2.4 - 0.048 = 7.552
        }

        // now try a market sell which can only be partially filled and see that it gets executed
        sequencer.addOrder(market, BigDecimal("0.2"), null, taker, Order.Type.MarketSell).also { response ->
            assertEquals(2, response.ordersChangedCount)

            val takerOrder = response.ordersChangedList[0]
            assertEquals(OrderDisposition.PartiallyFilled, takerOrder.disposition)

            val makerOrder = response.ordersChangedList[1]
            assertEquals(OrderDisposition.Filled, makerOrder.disposition)

            response.assertTrades(
                market,
                listOf(
                    ExpectedTrade(
                        buyOrderGuid = makerOrder.guid,
                        sellOrderGuid = takerOrder.guid,
                        price = BigDecimal("10.000"),
                        amount = BigDecimal("0.1"),
                        buyerFee = BigDecimal("0.01"),
                        sellerFee = BigDecimal("0.02"),
                    ),
                ),
            )

            response.assertBalanceChanges(
                market,
                listOf(
                    Triple(maker, eth1, -BigDecimal("1.01")),
                    Triple(taker, btc1, -BigDecimal("0.1")),
                    Triple(maker, btc1, BigDecimal("0.1")),
                    Triple(taker, eth1, BigDecimal("0.98")),
                ),
            )
        }

        // verify the remaining balances for maker and taker (withdraw a large amount - returned balance change will
        // indicate what the balance was)
        // expected balances:
        //
        //   maker BTC1 = 9.8 + 0.1 = 9.9
        //         ETH1 = 12.376 - 1.0 - 0.01 = 11.366
        //   taker BTC1 = 0.2 - 0.1 = 0.1
        //         ETH1 = 7.552 + 1.0 - 0.02 = 8.532
        sequencer.withdrawal(maker, btc1, BigDecimal(100), expectedAmount = BigDecimal("9.9"))
        sequencer.withdrawal(maker, eth1, BigDecimal(100), expectedAmount = BigDecimal("11.366"))
        sequencer.withdrawal(taker, btc1, BigDecimal(100), expectedAmount = BigDecimal("0.1"))
        sequencer.withdrawal(taker, eth1, BigDecimal(100), expectedAmount = BigDecimal("8.532"))
    }

    @Test
    fun `Test a market order that executes against multiple orders at multiple levels`() {
        val sequencer = SequencerClient()
        val market = sequencer.createMarket(MarketId("BTC2/ETH2"))
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))

        val lp1 = generateWalletAddress()
        val lp2 = generateWalletAddress()
        val tkr = generateWalletAddress()

        sequencer.deposit(lp1, market.baseAsset, BigDecimal("0.31"))
        sequencer.deposit(lp2, market.baseAsset, BigDecimal("0.31"))

        val sell1Order = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.01"), BigDecimal("17.550"), lp1, Order.Type.LimitSell)
        val sell2Order = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.01"), BigDecimal("17.550"), lp2, Order.Type.LimitSell)
        val sell3Order = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.1"), BigDecimal("17.600"), lp1, Order.Type.LimitSell)
        val sell4Order = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.1"), BigDecimal("17.600"), lp2, Order.Type.LimitSell)
        val sell5Order = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.2"), BigDecimal("17.700"), lp1, Order.Type.LimitSell)
        val sell6Order = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.2"), BigDecimal("17.700"), lp2, Order.Type.LimitSell)

        // clearing price would be (0.02 * 17.55 + 0.15 * 17.6) / 0.17 = 17.594
        // notional is 0.17 * 17.594 = 2.99098, fee would be notional * 0.02 = 0.0598196
        sequencer.deposit(tkr, market.quoteAsset, BigDecimal("2.99098") + BigDecimal("0.0598196"))

        sequencer.addOrder(market, BigDecimal("0.17"), null, tkr, Order.Type.MarketBuy).also { response ->
            assertEquals(5, response.ordersChangedCount)

            val takerOrder = response.ordersChangedList[0].also {
                assertEquals(OrderDisposition.Filled, it.disposition)
            }

            val makerOrder1 = response.ordersChangedList[1].also {
                assertEquals(OrderDisposition.Filled, it.disposition)
                assertEquals(sell1Order.guid, it.guid)
            }

            val makerOrder2 = response.ordersChangedList[2].also {
                assertEquals(OrderDisposition.Filled, it.disposition)
                assertEquals(sell2Order.guid, it.guid)
            }

            val makerOrder3 = response.ordersChangedList[3].also {
                assertEquals(OrderDisposition.Filled, it.disposition)
                assertEquals(sell3Order.guid, it.guid)
            }

            val makerOrder4 = response.ordersChangedList[4].also {
                assertEquals(OrderDisposition.PartiallyFilled, it.disposition)
                assertEquals(sell4Order.guid, it.guid)
                assertEquals(
                    BigDecimal("0.05").setScale(market.baseDecimals),
                    it.newQuantityOrNull?.fromFundamentalUnits(market.baseDecimals),
                )
            }

            response.assertTrades(
                market,
                listOf(
                    ExpectedTrade(
                        buyOrderGuid = takerOrder.guid,
                        sellOrderGuid = makerOrder1.guid,
                        price = BigDecimal("17.550"),
                        amount = BigDecimal("0.01"),
                        buyerFee = BigDecimal("0.00351"),
                        sellerFee = BigDecimal("0.001755"),
                    ),
                    ExpectedTrade(
                        buyOrderGuid = takerOrder.guid,
                        sellOrderGuid = makerOrder2.guid,
                        price = BigDecimal("17.550"),
                        amount = BigDecimal("0.01"),
                        buyerFee = BigDecimal("0.00351"),
                        sellerFee = BigDecimal("0.001755"),
                    ),
                    ExpectedTrade(
                        buyOrderGuid = takerOrder.guid,
                        sellOrderGuid = makerOrder3.guid,
                        price = BigDecimal("17.600"),
                        amount = BigDecimal("0.1"),
                        buyerFee = BigDecimal("0.0352"),
                        sellerFee = BigDecimal("0.0176"),
                    ),
                    ExpectedTrade(
                        buyOrderGuid = takerOrder.guid,
                        sellOrderGuid = makerOrder4.guid,
                        price = BigDecimal("17.600"),
                        amount = BigDecimal("0.05"),
                        buyerFee = BigDecimal("0.0176"),
                        sellerFee = BigDecimal("0.0088"),
                    ),
                ),
            )
        }

        // place another market order to exhaust remaining limit orders
        // clearing price would be (0.05 * 17.6 + 0.4 * 17.7) / 0.45 = 17.689
        // notional is 0.45 * 17.689 = 7.96005, fee would be notional * 0,02 = 0.159201
        sequencer.deposit(tkr, market.quoteAsset, BigDecimal("7.96005") + BigDecimal("0.159201"))
        sequencer.addOrder(market, BigDecimal("0.45"), null, tkr, Order.Type.MarketBuy).also { response ->
            assertEquals(4, response.ordersChangedCount)
            val takerOrder = response.ordersChangedList[0].also {
                assertEquals(OrderDisposition.Filled, it.disposition)
            }
            assertEquals(OrderDisposition.Filled, response.ordersChangedList[1].disposition)
            assertEquals(OrderDisposition.Filled, response.ordersChangedList[2].disposition)
            assertEquals(OrderDisposition.Filled, response.ordersChangedList[3].disposition)

            response.assertTrades(
                market,
                listOf(
                    ExpectedTrade(
                        buyOrderGuid = takerOrder.guid,
                        sellOrderGuid = sell4Order.guid,
                        price = BigDecimal("17.600"),
                        amount = BigDecimal("0.05"),
                        buyerFee = BigDecimal("0.0176"),
                        sellerFee = BigDecimal("0.0088"),
                    ),
                    ExpectedTrade(
                        buyOrderGuid = takerOrder.guid,
                        sellOrderGuid = sell5Order.guid,
                        price = BigDecimal("17.700"),
                        amount = BigDecimal("0.2"),
                        buyerFee = BigDecimal("0.0708"),
                        sellerFee = BigDecimal("0.0354"),
                    ),
                    ExpectedTrade(
                        buyOrderGuid = takerOrder.guid,
                        sellOrderGuid = sell6Order.guid,
                        price = BigDecimal("17.700"),
                        amount = BigDecimal("0.2"),
                        buyerFee = BigDecimal("0.0708"),
                        sellerFee = BigDecimal("0.0354"),
                    ),
                ),
            )
        }
    }

    @Test
    fun `test balances`() {
        val sequencer = SequencerClient()
        val walletAddress = generateWalletAddress()

        val asset1 = SequencerClient.Asset("ETH", decimals = 18)
        val asset2 = SequencerClient.Asset("PEPE", decimals = 18)
        val amount = BigDecimal("0.2")

        // do a deposit
        sequencer.deposit(walletAddress, asset1, amount)

        // withdraw half
        sequencer.withdrawal(walletAddress, asset1, BigDecimal("0.1"))

        // request to withdraw amount, only half should be withdrawn
        sequencer.withdrawal(walletAddress, asset1, BigDecimal("0.2"), expectedAmount = BigDecimal("0.1"))

        // attempt to withdraw more does not return a balance change
        sequencer.withdrawal(walletAddress, asset1, BigDecimal("0.1"), expectedAmount = null)

        // attempt to withdraw from an unknown wallet or asset does not return a balance change
        sequencer.withdrawal(generateWalletAddress(), asset1, BigDecimal("1"), expectedAmount = null)
        sequencer.withdrawal(walletAddress, asset2, BigDecimal("1"), expectedAmount = null)

        // can combine deposits and withdrawals in a batch - amount should be net
        sequencer.depositsAndWithdrawals(walletAddress, asset1, listOf(BigDecimal("10"), BigDecimal("1").negate()))

        // if it nets to 0, no balance change returned
        sequencer.depositsAndWithdrawals(walletAddress, asset1, listOf(BigDecimal("10").negate(), BigDecimal("10")), expectedAmount = null)
    }

    @Test
    fun `test limit checking on orders`() {
        val sequencer = SequencerClient()
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))
        val market1 = sequencer.createMarket(MarketId("BTC3/ETH3"), marketPrice = BigDecimal("11.00"))

        val maker = generateWalletAddress()
        // cannot place a buy or sell limit order without any deposits
        assertEquals(SequencerError.ExceedsLimit, sequencer.addOrder(market1, BigDecimal("0.1"), BigDecimal("12.00"), maker, Order.Type.LimitSell).error)
        assertEquals(SequencerError.ExceedsLimit, sequencer.addOrder(market1, BigDecimal("0.1"), BigDecimal("11.00"), maker, Order.Type.LimitBuy).error)

        // deposit some base and can sell
        sequencer.deposit(maker, market1.baseAsset, BigDecimal("0.1"))
        sequencer.addOrderAndVerifyAccepted(market1, BigDecimal("0.1"), BigDecimal("12.00"), maker, Order.Type.LimitSell)

        // deposit some quote, but still can't buy because of the fee
        sequencer.deposit(maker, market1.quoteAsset, BigDecimal("11.00") * BigDecimal("0.1"))
        assertEquals(SequencerError.ExceedsLimit, sequencer.addOrder(market1, BigDecimal("0.1"), BigDecimal("11.00"), maker, Order.Type.LimitBuy).error)

        // can buy after depositing more quote to cover the fee
        sequencer.deposit(maker, market1.quoteAsset, BigDecimal("11.00") * BigDecimal("0.001"))
        sequencer.addOrderAndVerifyAccepted(market1, BigDecimal("0.1"), BigDecimal("11.00"), maker, Order.Type.LimitBuy)

        // but now that we've exhausted our balance we can't add more
        assertEquals(SequencerError.ExceedsLimit, sequencer.addOrder(market1, BigDecimal("0.001"), BigDecimal("12.00"), maker, Order.Type.LimitSell).error)
        assertEquals(SequencerError.ExceedsLimit, sequencer.addOrder(market1, BigDecimal("0.001"), BigDecimal("11.00"), maker, Order.Type.LimitBuy).error)

        // but we can reuse the same liquidity in another market
        val market2 = sequencer.createMarket(MarketId("ETH3/USDC3"), baseDecimals = 18, quoteDecimals = 6, marketPrice = BigDecimal("100"))
        sequencer.addOrderAndVerifyAccepted(market2, BigDecimal("1.111"), BigDecimal("100.00"), maker, Order.Type.LimitSell)

        // if we deposit some more we can add another order
        sequencer.deposit(maker, market1.baseAsset, BigDecimal("0.1"))
        sequencer.addOrderAndVerifyAccepted(market1, BigDecimal("0.1"), BigDecimal("12.00"), maker, Order.Type.LimitSell)

        // but not more
        assertEquals(SequencerError.ExceedsLimit, sequencer.addOrder(market1, BigDecimal("0.1"), BigDecimal("12.00"), maker, Order.Type.LimitSell).error)

        // unless a trade increases the balance
        val taker = generateWalletAddress()
        sequencer.deposit(taker, market1.baseAsset, BigDecimal("0.1"))
        sequencer.addOrder(market1, BigDecimal("0.1"), null, taker, Order.Type.MarketSell).also {
            assertEquals(OrderDisposition.Filled, it.ordersChangedList.first().disposition)
        }

        sequencer.addOrderAndVerifyAccepted(market1, BigDecimal("0.1"), BigDecimal("12.00"), maker, Order.Type.LimitSell)
    }

    @Test
    fun `test LimitBuy order can cross the market`() {
        val sequencer = SequencerClient()
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))
        val market = sequencer.createMarket(MarketId("BTC8/ETH8"))

        val maker = generateWalletAddress()
        // deposit and prepare liquidity
        sequencer.deposit(maker, market.baseAsset, BigDecimal("0.2"))
        sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.2"), BigDecimal("17.55"), maker, Order.Type.LimitSell)

        // prepare second maker
        val crossingTheMarketMaker = generateWalletAddress()
        sequencer.deposit(crossingTheMarketMaker, market.quoteAsset, BigDecimal("18.00") * BigDecimal("0.3"))
        // deposit extra for the fees
        sequencer.deposit(crossingTheMarketMaker, market.quoteAsset, BigDecimal("0.03510") * BigDecimal(2))

        // limit order can cross the market and be filled immediately
        sequencer.addOrder(market, BigDecimal("0.1"), BigDecimal("18.00"), crossingTheMarketMaker, Order.Type.LimitBuy).also { response ->
            val takerOrder = response.ordersChangedList.first()
            assertEquals(OrderDisposition.Filled, takerOrder.disposition)

            val makerOrder = response.ordersChangedList.last()
            assertEquals(OrderDisposition.PartiallyFilled, makerOrder.disposition)

            response.assertTrades(
                market,
                listOf(
                    ExpectedTrade(
                        buyOrderGuid = takerOrder.guid,
                        sellOrderGuid = makerOrder.guid,
                        price = BigDecimal("17.550"),
                        amount = BigDecimal("0.1"),
                        buyerFee = BigDecimal("0.0351"),
                        sellerFee = BigDecimal("0.01755"),
                    ),
                ),
            )

            response.assertBalanceChanges(
                market,
                listOf(
                    Triple(crossingTheMarketMaker, market.quoteAsset, -BigDecimal("1.7901")),
                    Triple(maker, market.baseAsset, -BigDecimal("0.1")),
                    Triple(crossingTheMarketMaker, market.baseAsset, BigDecimal("0.1")),
                    Triple(maker, market.quoteAsset, BigDecimal("1.73745")),
                ),
            )
        }

        // or filled partially with remaining limit amount stays on the book
        sequencer.addOrder(market, BigDecimal("0.2"), BigDecimal("18.00"), crossingTheMarketMaker, Order.Type.LimitBuy).also { response ->
            val takerOrder = response.ordersChangedList.first()
            assertEquals(OrderDisposition.PartiallyFilled, takerOrder.disposition)

            val makerOrder = response.ordersChangedList.last()
            assertEquals(OrderDisposition.Filled, makerOrder.disposition)

            response.assertTrades(
                market,
                listOf(
                    ExpectedTrade(
                        buyOrderGuid = takerOrder.guid,
                        sellOrderGuid = makerOrder.guid,
                        price = BigDecimal("17.550"),
                        amount = BigDecimal("0.1"),
                        buyerFee = BigDecimal("0.0351"),
                        sellerFee = BigDecimal("0.01755"),
                    ),
                ),
            )

            response.assertBalanceChanges(
                market,
                listOf(
                    Triple(crossingTheMarketMaker, market.quoteAsset, -BigDecimal("1.7901")),
                    Triple(maker, market.baseAsset, -BigDecimal("0.1")),
                    Triple(crossingTheMarketMaker, market.baseAsset, BigDecimal("0.1")),
                    Triple(maker, market.quoteAsset, BigDecimal("1.73745")),
                ),
            )
        }
    }

    @Test
    fun `test LimitBuy order can cross the market filling LimitSell orders at multiple levels until limit price`() {
        val sequencer = SequencerClient()
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))
        val market = sequencer.createMarket(MarketId("BTC9/ETH9"))

        val maker = generateWalletAddress()
        // deposit and prepare liquidity
        sequencer.deposit(maker, market.baseAsset, BigDecimal("0.2"))
        val sellOrder1 = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.01"), BigDecimal("17.55"), maker, Order.Type.LimitSell)
        val sellOrder2 = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.01"), BigDecimal("18.00"), maker, Order.Type.LimitSell)
        val sellOrder3 = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.01"), BigDecimal("18.50"), maker, Order.Type.LimitSell)
        sequencer.addOrder(market, BigDecimal("0.01"), BigDecimal("19.00"), maker, Order.Type.LimitSell)
        sequencer.addOrder(market, BigDecimal("0.01"), BigDecimal("19.50"), maker, Order.Type.LimitSell)

        // prepare second maker
        val crossingTheMarketMaker = generateWalletAddress()
        sequencer.deposit(crossingTheMarketMaker, market.quoteAsset, BigDecimal("18.2101") * BigDecimal("0.05"))
        assertEquals(SequencerError.ExceedsLimit, sequencer.addOrder(market, BigDecimal("0.05"), BigDecimal("18.50"), crossingTheMarketMaker, Order.Type.LimitBuy).error)

        val expectedBuyerFeesForFilledAmount = (BigDecimal("0.003510") + BigDecimal("0.0036") + BigDecimal("0.00370"))
        val expectedBuyerFeesForRemainingAmount = BigDecimal("19.00") * BigDecimal("0.02")

        // limit check passes on lower deposited amount due to partial filling by market price
        sequencer.deposit(
            crossingTheMarketMaker,
            market.quoteAsset,
            BigDecimal("0.000005") + expectedBuyerFeesForFilledAmount + expectedBuyerFeesForRemainingAmount,
        )

        // limit order is partially filled until limit price is reached
        sequencer.addOrder(market, BigDecimal("0.05"), BigDecimal("18.50"), crossingTheMarketMaker, Order.Type.LimitBuy).also { response ->
            assertEquals(4, response.ordersChangedCount)
            val takerOrder = response.ordersChangedList[0].also {
                assertEquals(OrderDisposition.PartiallyFilled, it.disposition)
            }
            assertEquals(OrderDisposition.Filled, response.ordersChangedList[1].disposition)
            assertEquals(OrderDisposition.Filled, response.ordersChangedList[2].disposition)
            assertEquals(OrderDisposition.Filled, response.ordersChangedList[3].disposition)

            response.assertTrades(
                market,
                listOf(
                    ExpectedTrade(
                        buyOrderGuid = takerOrder.guid,
                        sellOrderGuid = sellOrder1.guid,
                        price = BigDecimal("17.550"),
                        amount = BigDecimal("0.01"),
                        buyerFee = BigDecimal("0.003510"),
                        sellerFee = BigDecimal("0.001755"),
                    ),
                    ExpectedTrade(
                        buyOrderGuid = takerOrder.guid,
                        sellOrderGuid = sellOrder2.guid,
                        price = BigDecimal("18.000"),
                        amount = BigDecimal("0.01"),
                        buyerFee = BigDecimal("0.0036"),
                        sellerFee = BigDecimal("0.0018"),
                    ),
                    ExpectedTrade(
                        buyOrderGuid = takerOrder.guid,
                        sellOrderGuid = sellOrder3.guid,
                        price = BigDecimal("18.500"),
                        amount = BigDecimal("0.01"),
                        buyerFee = BigDecimal("0.00370"),
                        sellerFee = BigDecimal("0.00185"),
                    ),
                ),
            )

            response.assertBalanceChanges(
                market,
                listOf(
                    Triple(crossingTheMarketMaker, market.quoteAsset, -BigDecimal("0.5405") - expectedBuyerFeesForFilledAmount),
                    Triple(maker, market.baseAsset, -BigDecimal("0.03")),
                    Triple(crossingTheMarketMaker, market.baseAsset, BigDecimal("0.03")),
                    Triple(maker, market.quoteAsset, BigDecimal("0.535095")),
                ),
            )
        }
    }

    @Test
    fun `test LimitSell order can cross the market`() {
        val sequencer = SequencerClient()
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))
        val market = sequencer.createMarket(MarketId("BTC10/ETH10"))

        val maker = generateWalletAddress()
        // deposit and prepare liquidity
        sequencer.deposit(maker, market.quoteAsset, BigDecimal("17.50") * BigDecimal("0.2"))
        // deposit extra for the fees
        sequencer.deposit(maker, market.quoteAsset, (BigDecimal("0.1750") * BigDecimal(2)))
        sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.2"), BigDecimal("17.50"), maker, Order.Type.LimitBuy)

        // prepare second maker
        val crossingTheMarketMaker = generateWalletAddress()
        sequencer.deposit(crossingTheMarketMaker, market.baseAsset, BigDecimal("0.3"))

        // limit order can cross the market and be filled immediately
        sequencer.addOrder(market, BigDecimal("0.1"), BigDecimal("17.00"), crossingTheMarketMaker, Order.Type.LimitSell).also { response ->
            assertEquals(2, response.ordersChangedList.size)
            val takerOrder = response.ordersChangedList.first()
            val makerOrder = response.ordersChangedList.last()

            response.assertTrades(
                market,
                listOf(
                    ExpectedTrade(
                        buyOrderGuid = makerOrder.guid,
                        sellOrderGuid = takerOrder.guid,
                        price = BigDecimal("17.500"),
                        amount = BigDecimal("0.1"),
                        buyerFee = BigDecimal("0.01750"),
                        sellerFee = BigDecimal("0.03500"),
                    ),
                ),
            )

            response.assertBalanceChanges(
                market,
                listOf(
                    Triple(maker, market.quoteAsset, -BigDecimal("1.7675")),
                    Triple(crossingTheMarketMaker, market.baseAsset, -BigDecimal("0.1")),
                    Triple(maker, market.baseAsset, BigDecimal("0.1")),
                    Triple(crossingTheMarketMaker, market.quoteAsset, BigDecimal("1.715")),
                ),
            )
        }

        // or filled partially with remaining limit amount stays on the book
        sequencer.addOrder(market, BigDecimal("0.2"), BigDecimal("17.00"), crossingTheMarketMaker, Order.Type.LimitSell).also { response ->
            assertEquals(2, response.ordersChangedList.size)

            val takerOrder = response.ordersChangedList.first().also {
                assertEquals(OrderDisposition.PartiallyFilled, it.disposition)
            }

            val makerOrder = response.ordersChangedList.last().also {
                assertEquals(OrderDisposition.Filled, it.disposition)
            }

            response.assertTrades(
                market,
                listOf(
                    ExpectedTrade(
                        buyOrderGuid = makerOrder.guid,
                        sellOrderGuid = takerOrder.guid,
                        price = BigDecimal("17.500"),
                        amount = BigDecimal("0.1"),
                        buyerFee = BigDecimal("0.01750"),
                        sellerFee = BigDecimal("0.03500"),
                    ),
                ),
            )

            response.assertBalanceChanges(
                market,
                listOf(
                    Triple(maker, market.quoteAsset, -BigDecimal("1.7675")),
                    Triple(crossingTheMarketMaker, market.baseAsset, -BigDecimal("0.1")),
                    Triple(maker, market.baseAsset, BigDecimal("0.1")),
                    Triple(crossingTheMarketMaker, market.quoteAsset, BigDecimal("1.715")),
                ),
            )
        }
    }

    @Test
    fun `test LimitSell order can cross the market filling LimitBuy orders at multiple levels until limit price`() {
        val sequencer = SequencerClient()
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))
        val market = sequencer.createMarket(MarketId("BTC11/ETH11"))

        val maker = generateWalletAddress()
        // deposit and prepare liquidity
        sequencer.deposit(maker, market.quoteAsset, BigDecimal("17.50") * BigDecimal("0.2"))
        val buyOrder1 = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.01"), BigDecimal("17.50"), maker, Order.Type.LimitBuy)
        val buyOrder2 = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.01"), BigDecimal("17.00"), maker, Order.Type.LimitBuy)
        val buyOrder3 = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.01"), BigDecimal("16.50"), maker, Order.Type.LimitBuy)
        sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.01"), BigDecimal("16.00"), maker, Order.Type.LimitBuy)
        sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.01"), BigDecimal("15.50"), maker, Order.Type.LimitBuy)

        // prepare second maker
        val crossingTheMarketMaker = generateWalletAddress()
        sequencer.deposit(crossingTheMarketMaker, market.baseAsset, BigDecimal("0.3"))

        // limit order is partially filled until price is reached
        sequencer.addOrder(market, BigDecimal("0.05"), BigDecimal("16.50"), crossingTheMarketMaker, Order.Type.LimitSell).also { response ->
            assertEquals(4, response.ordersChangedCount)
            val takerOrder = response.ordersChangedList.first().also {
                assertEquals(OrderDisposition.PartiallyFilled, it.disposition)
            }
            assertEquals(OrderDisposition.Filled, response.ordersChangedList[1].disposition)
            assertEquals(OrderDisposition.Filled, response.ordersChangedList[2].disposition)
            assertEquals(OrderDisposition.Filled, response.ordersChangedList[3].disposition)

            response.assertTrades(
                market,
                listOf(
                    ExpectedTrade(
                        buyOrderGuid = buyOrder1.guid,
                        sellOrderGuid = takerOrder.guid,
                        price = BigDecimal("17.500"),
                        amount = BigDecimal("0.01"),
                        buyerFee = BigDecimal("0.001750"),
                        sellerFee = BigDecimal("0.003500"),
                    ),
                    ExpectedTrade(
                        buyOrderGuid = buyOrder2.guid,
                        sellOrderGuid = takerOrder.guid,
                        price = BigDecimal("17.000"),
                        amount = BigDecimal("0.01"),
                        buyerFee = BigDecimal("0.001700"),
                        sellerFee = BigDecimal("0.003400"),
                    ),
                    ExpectedTrade(
                        buyOrderGuid = buyOrder3.guid,
                        sellOrderGuid = takerOrder.guid,
                        price = BigDecimal("16.500"),
                        amount = BigDecimal("0.01"),
                        buyerFee = BigDecimal("0.001650"),
                        sellerFee = BigDecimal("0.003300"),
                    ),
                ),
            )

            response.assertBalanceChanges(
                market,
                listOf(
                    Triple(maker, market.quoteAsset, -BigDecimal("0.5151")),
                    Triple(crossingTheMarketMaker, market.baseAsset, -BigDecimal("0.03")),
                    Triple(maker, market.baseAsset, BigDecimal("0.03")),
                    Triple(crossingTheMarketMaker, market.quoteAsset, BigDecimal("0.4998")),
                ),
            )
        }
    }

    @Test
    fun `test order cancel`() {
        val sequencer = SequencerClient()
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))
        val market = sequencer.createMarket(MarketId("BTC4/ETH4"))

        val maker = generateWalletAddress()
        sequencer.deposit(maker, market.baseAsset, BigDecimal("0.1"))

        val order = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.1"), BigDecimal("17.55"), maker, Order.Type.LimitSell)

        sequencer.cancelOrder(market, order.guid, maker).also {
            assertEquals(OrderDisposition.Canceled, it.ordersChangedList.first().disposition)
        }

        val order2 = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.1"), BigDecimal("17.55"), maker, Order.Type.LimitSell)

        val taker = generateWalletAddress()
        sequencer.deposit(taker, market.quoteAsset, BigDecimal("1.1"))

        // have taker try to cancel maker order
        sequencer.cancelOrder(market, order2.guid, taker).also { response ->
            assertEquals(0, response.ordersChangedList.size)
            assertEquals(1, response.ordersChangeRejectedList.size)
            assertEquals(OrderChangeRejected.Reason.NotForWallet, response.ordersChangeRejectedList.first().reason)
        }

        // try canceling an order which has been partially filled
        sequencer.addOrder(market, BigDecimal("0.05"), null, taker, Order.Type.MarketBuy).also { response ->
            val partiallyFilledOrder = response.ordersChangedList[1]
            assertEquals(OrderDisposition.PartiallyFilled, partiallyFilledOrder.disposition)
            assertEquals(order2.guid, partiallyFilledOrder.guid)

            sequencer.cancelOrder(market, partiallyFilledOrder.guid, maker).also {
                assertEquals(OrderDisposition.Canceled, it.ordersChangedList.first().disposition)
            }
        }

        // cancel an invalid order
        sequencer.cancelOrder(market, order2.guid, maker).also { response ->
            assertEquals(0, response.ordersChangedList.size)
            assertEquals(1, response.ordersChangeRejectedList.size)
            assertEquals(OrderChangeRejected.Reason.DoesNotExist, response.ordersChangeRejectedList.first().reason)
        }
    }

    @Test
    fun `test order change`() {
        val sequencer = SequencerClient()
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))
        val market = sequencer.createMarket(MarketId("BTC5/ETH5"))

        val maker = generateWalletAddress()
        sequencer.deposit(maker, market.baseAsset, BigDecimal("0.1"))
        val sellOrder1 = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.1"), BigDecimal("17.55"), maker, Order.Type.LimitSell)

        // reduce amount
        sequencer.changeOrder(market, sellOrder1.guid.toOrderGuid(), BigDecimal("0.0999"), BigDecimal("17.55"), maker).also { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
        }

        // cannot increase amount beyond 0.00001 since there is not enough collateral
        assertEquals(SequencerError.ExceedsLimit, sequencer.changeOrder(market, sellOrder1.guid.toOrderGuid(), BigDecimal("0.11"), BigDecimal("17.55"), maker).error)

        // but can change price
        sequencer.changeOrder(market, sellOrder1.guid.toOrderGuid(), BigDecimal("0.0999"), BigDecimal("17.60"), maker).also { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
        }

        // can also add a new order for remaining collateral
        sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.0001"), BigDecimal("17.55"), maker, Order.Type.LimitSell)

        // can change the price to cross the market, disposition is 'Accepted' since there is no liquidity yet available in the market
        sequencer.changeOrder(market, sellOrder1.guid.toOrderGuid(), BigDecimal("0.0999"), BigDecimal("15.50"), maker).also { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
        }

        // check for a limit buy
        sequencer.deposit(maker, market.quoteAsset, BigDecimal("10.1"))
        val buyOrder1 = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("1"), BigDecimal("10.00"), maker, Order.Type.LimitBuy)

        // cannot increase amount since we have consumed all the collateral
        assertEquals(
            SequencerError.ExceedsLimit,
            sequencer.changeOrder(
                market,
                buyOrder1.guid.toOrderGuid(),
                BigDecimal("1.1"),
                BigDecimal("10.00"),
                maker,
            ).error,
        )

        // also cannot increase price
        assertEquals(
            SequencerError.ExceedsLimit,
            sequencer.changeOrder(
                market,
                buyOrder1.guid.toOrderGuid(),
                BigDecimal("1.0"),
                BigDecimal("10.05"),
                maker,
            ).error,
        )

        // but can decrease amount or decrease price
        sequencer.changeOrder(market, buyOrder1.guid.toOrderGuid(), BigDecimal("0.99"), BigDecimal("10.00"), maker).also {
            assertEquals(OrderDisposition.Accepted, it.ordersChangedList.first().disposition)
        }

        sequencer.changeOrder(market, buyOrder1.guid.toOrderGuid(), BigDecimal("1"), BigDecimal("9.95"), maker).also {
            assertEquals(OrderDisposition.Accepted, it.ordersChangedList.first().disposition)
        }

        // different wallet try to update an order
        val taker = generateWalletAddress()
        sequencer.changeOrder(market, buyOrder1.guid.toOrderGuid(), BigDecimal("0.99"), BigDecimal("9.95"), taker).also {
            assertEquals(0, it.ordersChangedList.size)
            assertEquals(1, it.ordersChangeRejectedList.size)
            assertEquals(OrderChangeRejected.Reason.NotForWallet, it.ordersChangeRejectedList.first().reason)
        }

        // update an invalid order.
        sequencer.changeOrder(market, (buyOrder1.guid + 1).toOrderGuid(), BigDecimal("0.99"), BigDecimal("9.95"), maker).also {
            assertEquals(0, it.ordersChangedList.size)
            assertEquals(1, it.ordersChangeRejectedList.size)
            assertEquals(OrderChangeRejected.Reason.DoesNotExist, it.ordersChangeRejectedList.first().reason)
        }
    }

    @Test
    fun `test order change when new price crosses the market`() {
        val sequencer = SequencerClient()
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))
        val market = sequencer.createMarket(MarketId("BTC12/ETH12"))

        // onboard maker and prepare market
        val maker = generateWalletAddress()
        sequencer.deposit(maker, market.baseAsset, BigDecimal("0.2"))
        sequencer.deposit(maker, market.quoteAsset, BigDecimal("17.50") * BigDecimal("0.2"))
        // deposit extra for the fees
        sequencer.deposit(maker, market.quoteAsset, BigDecimal("0.01750") + BigDecimal("0.01755"))
        val m1sell2 = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.1"), BigDecimal("18.00"), maker, Order.Type.LimitSell)
        val m1sell1 = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.1"), BigDecimal("17.55"), maker, Order.Type.LimitSell)
        val m1buy1 = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.1"), BigDecimal("17.50"), maker, Order.Type.LimitBuy)
        val m1buy2 = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.1"), BigDecimal("17.25"), maker, Order.Type.LimitBuy)

        // onboard another maker
        val anotherMaker = generateWalletAddress()
        sequencer.deposit(anotherMaker, market.baseAsset, BigDecimal("0.2"))
        sequencer.deposit(anotherMaker, market.quoteAsset, BigDecimal("17.70") * BigDecimal("0.2"))
        // deposit extra for the fees
        sequencer.deposit(maker, market.quoteAsset, BigDecimal("0.03500") + BigDecimal("0.03510"))
        val m2sell1 = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.2"), BigDecimal("18.00"), anotherMaker, Order.Type.LimitSell)
        val m2buy1 = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.2"), BigDecimal("17.00"), anotherMaker, Order.Type.LimitBuy)

        // verify setup is successful
        listOf(m1buy1, m1buy2, m1sell1, m1sell2, m2buy1, m2sell1).forEach {
            assertEquals(OrderDisposition.Accepted, it.disposition)
        }

        // update limit sell order to cross the market. Results in immediate partial execution.
        sequencer.changeOrder(market, m2sell1.guid.toOrderGuid(), BigDecimal("0.2"), BigDecimal("17.30"), anotherMaker).also { response ->
            assertEquals(2, response.ordersChangedCount)
            assertEquals(OrderDisposition.PartiallyFilled, response.ordersChangedList[0].disposition)
            assertEquals(OrderDisposition.Filled, response.ordersChangedList[1].disposition)

            response.assertTrades(
                market,
                listOf(
                    ExpectedTrade(
                        buyOrderGuid = m1buy1.guid,
                        sellOrderGuid = response.ordersChangedList[0].guid,
                        price = BigDecimal("17.500"),
                        amount = BigDecimal("0.1"),
                        buyerFee = BigDecimal("0.01750"),
                        sellerFee = BigDecimal("0.03500"),
                    ),
                ),
            )

            response.assertBalanceChanges(
                market,
                listOf(
                    Triple(maker, market.quoteAsset, -BigDecimal("1.7675")),
                    Triple(anotherMaker, market.baseAsset, -BigDecimal("0.1")),
                    Triple(maker, market.baseAsset, BigDecimal("0.1")),
                    Triple(anotherMaker, market.quoteAsset, BigDecimal("1.71500")),
                ),
            )
        }

        // now cancel own order (limit sell 17.30) to avoid matching
        sequencer.cancelOrder(market, m2sell1.guid, anotherMaker)

        // update limit buy order to cross the market (immediate partial execution)
        sequencer.changeOrder(market, m2buy1.guid.toOrderGuid(), BigDecimal("0.2"), BigDecimal("17.75"), anotherMaker).also { response ->
            assertEquals(2, response.ordersChangedCount)
            assertEquals(OrderDisposition.PartiallyFilled, response.ordersChangedList[0].disposition)
            assertEquals(OrderDisposition.Filled, response.ordersChangedList[1].disposition)

            response.assertTrades(
                market,
                listOf(
                    ExpectedTrade(
                        buyOrderGuid = response.ordersChangedList[0].guid,
                        sellOrderGuid = m1sell1.guid,
                        price = BigDecimal("17.550"),
                        amount = BigDecimal("0.1"),
                        buyerFee = BigDecimal("0.03510"),
                        sellerFee = BigDecimal("0.01755"),
                    ),
                ),
            )

            response.assertBalanceChanges(
                market,
                listOf(
                    Triple(anotherMaker, market.quoteAsset, -BigDecimal("1.7901")),
                    Triple(maker, market.baseAsset, -BigDecimal("0.1")),
                    Triple(anotherMaker, market.baseAsset, BigDecimal("0.1")),
                    Triple(maker, market.quoteAsset, BigDecimal("1.73745")),
                ),
            )
        }
    }

    @Test
    fun `test auto-reduce from trades`() {
        val sequencer = SequencerClient()
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))

        val market1 = sequencer.createMarket(MarketId("BTC6/ETH6"), tickSize = BigDecimal(1), marketPrice = BigDecimal(10.5), baseDecimals = 8, quoteDecimals = 18)
        val market2 = sequencer.createMarket(MarketId("ETH6/USDC6"), tickSize = BigDecimal(1), marketPrice = BigDecimal(9.5), baseDecimals = 18, quoteDecimals = 6)
        val market3 = sequencer.createMarket(MarketId("XXX6/ETH6"), tickSize = BigDecimal(1), marketPrice = BigDecimal(20.5), baseDecimals = 1, quoteDecimals = 18)

        val maker = generateWalletAddress()

        // maker deposits 10.1 ETH
        sequencer.deposit(maker, market1.quoteAsset, BigDecimal("10.1"))

        // maker adds a bid in market1 using all 10.1 eth
        sequencer.addOrderAndVerifyAccepted(market1, BigDecimal("1"), BigDecimal("10"), maker, Order.Type.LimitBuy)

        // maker adds an offer in market2 using all 10.1 eth
        val market2Offer = sequencer.addOrderAndVerifyAccepted(market2, BigDecimal("10"), BigDecimal("10"), maker, Order.Type.LimitSell)

        // maker also adds a bid in market3 using all 10.1 eth
        val market3Bid = sequencer.addOrderAndVerifyAccepted(market3, BigDecimal("0.5"), BigDecimal("20"), maker, Order.Type.LimitBuy)

        // now add a taker who will hit the market1 bid selling 0.6 BTC, this would consume 6 ETH + 0,06 ETH fee from maker
        val taker = generateWalletAddress()
        sequencer.deposit(taker, market1.baseAsset, BigDecimal("0.6"))
        sequencer.addOrder(market1, BigDecimal("0.6"), null, taker, Order.Type.MarketSell).also { response ->
            assertEquals(OrderDisposition.Filled, response.ordersChangedList.first().disposition)

            // the maker's offer in market2 should be auto-reduced
            val reducedOffer = response.ordersChangedList.first { it.guid == market2Offer.guid }
            assertEquals(OrderDisposition.AutoReduced, reducedOffer.disposition)
            assertEquals(BigDecimal("4.04").setScale(market2.baseDecimals), reducedOffer.newQuantity.fromFundamentalUnits(market2.baseDecimals))

            // also the maker's bid in market3 should be auto-reduced
            val reducedBid = response.ordersChangedList.first { it.guid == market3Bid.guid }
            assertEquals(OrderDisposition.AutoReduced, reducedBid.disposition)
            assertEquals(BigDecimal("0.2").setScale(market3.baseDecimals), reducedBid.newQuantity.fromFundamentalUnits(market3.baseDecimals))
        }
    }

    @Test
    fun `test auto-reduce from withdrawals`() {
        val sequencer = SequencerClient()
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))
        val market = sequencer.createMarket(MarketId("BTC7/ETH7"))

        val maker = generateWalletAddress()
        // maker deposits 10 BTC
        sequencer.deposit(maker, market.baseAsset, BigDecimal("10"))
        // maker adds two offers combined which use all 10 BTC
        val order1 = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("4"), BigDecimal("17.75"), maker, Order.Type.LimitSell)
        val order2 = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("6"), BigDecimal("18.00"), maker, Order.Type.LimitSell)

        // now maker withdraws 7 BTC
        sequencer.withdrawal(maker, market.baseAsset, BigDecimal("7")).also { response ->
            val reducedOffer1 = response.ordersChangedList.first { it.guid == order1.guid }
            assertEquals(OrderDisposition.AutoReduced, reducedOffer1.disposition)
            assertEquals(BigDecimal("3").setScale(market.baseDecimals), reducedOffer1.newQuantity.fromFundamentalUnits(market.baseDecimals))

            val reducedOffer2 = response.ordersChangedList.first { it.guid == order2.guid }
            assertEquals(OrderDisposition.AutoReduced, reducedOffer2.disposition)
            assertEquals(BigDecimal("0").setScale(market.baseDecimals), reducedOffer2.newQuantity.fromFundamentalUnits(market.baseDecimals))
        }
    }

    @Test
    fun `fee rate change does not affect existing orders in the book`() {
        val sequencer = SequencerClient()
        // set maker fee rate to 1% and taker fee rate to 2%
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))
        val market = sequencer.createMarket(MarketId("BTC8/ETH8"))

        val maker = generateWalletAddress()
        val taker = generateWalletAddress()

        sequencer.deposit(maker, market.baseAsset, BigDecimal("10"))
        sequencer.deposit(taker, market.quoteAsset, BigDecimal("200"))

        val sellOrder1 = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("5"), BigDecimal("10.00"), maker, Order.Type.LimitSell)

        // increase fee rates
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 2.0, taker = 4.0))

        val sellOrder2 = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("5"), BigDecimal("10.00"), maker, Order.Type.LimitSell)

        sequencer.addOrder(market, BigDecimal("10"), null, taker, Order.Type.MarketBuy).also { response ->
            assertEquals(3, response.ordersChangedList.size)
            assertEquals(OrderDisposition.Filled, response.ordersChangedList[0].disposition)
            assertEquals(OrderDisposition.Filled, response.ordersChangedList[1].disposition)
            assertEquals(OrderDisposition.Filled, response.ordersChangedList[2].disposition)

            response.assertTrades(
                market,
                listOf(
                    ExpectedTrade(
                        buyOrderGuid = response.ordersChangedList[0].guid,
                        sellOrderGuid = sellOrder1.guid,
                        price = BigDecimal("10.000"),
                        amount = BigDecimal("5.0"),
                        // taker's fee is 4%
                        buyerFee = BigDecimal("2.0"),
                        // maker's fee is 1% since first order was created before fee rate increase
                        sellerFee = BigDecimal("0.5"),
                    ),
                    ExpectedTrade(
                        buyOrderGuid = response.ordersChangedList[0].guid,
                        sellOrderGuid = sellOrder2.guid,
                        price = BigDecimal("10.000"),
                        amount = BigDecimal("5.0"),
                        // taker's fee is 4%
                        buyerFee = BigDecimal("2.0"),
                        // maker's fee is 2% since second order was created after fee rate increase
                        sellerFee = BigDecimal("1.0"),
                    ),
                ),
            )
        }
    }

    @Test
    fun `test failed withdrawals`() {
        val sequencer = SequencerClient()
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))

        val walletAddress = generateWalletAddress()
        val asset = SequencerClient.Asset("ETH", decimals = 18)
        val amount = BigDecimal("0.2")

        // do a deposit
        sequencer.deposit(walletAddress, asset, amount)

        // withdraw half
        sequencer.withdrawal(walletAddress, asset, BigDecimal("0.1"))

        // withdraw other half
        sequencer.withdrawal(walletAddress, asset, BigDecimal("0.1"))

        // fail the 2 withdrawals
        sequencer.failedWithdrawals(walletAddress, asset, listOf(BigDecimal("0.1"), BigDecimal("0.1")))

        // should still be able to withdraw full amount since we rolled back the 2 halves
        sequencer.withdrawal(walletAddress, asset, amount)
    }

    @Test
    fun `Test failed settlements`() {
        val sequencer = SequencerClient()
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))

        val market = sequencer.createMarket(MarketId("BTC8/ETH8"))

        val maker = generateWalletAddress()
        val taker = generateWalletAddress()

        // maker deposits some of both assets -- 10 BTC, 10 ETH
        val makerBaseBalance = BigDecimal("10")
        val makerQuoteBalance = BigDecimal("10")
        sequencer.deposit(maker, market.baseAsset, makerBaseBalance)
        sequencer.deposit(maker, market.quoteAsset, makerQuoteBalance)

        // place an order and see that it gets accepted
        sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.12345"), BigDecimal("17.500"), maker, Order.Type.LimitBuy)

        // place a sell order
        val sellOrder = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.54321"), BigDecimal("17.550"), maker, Order.Type.LimitSell)

        // place a market buy and see that it gets executed
        sequencer.deposit(taker, market.quoteAsset, BigDecimal("10"))
        sequencer.deposit(taker, market.baseAsset, BigDecimal("10"))

        val trade = sequencer.addOrder(market, BigDecimal("0.43210"), null, taker, Order.Type.MarketBuy).let { response ->
            assertEquals(2, response.ordersChangedCount)

            val takerOrder = response.ordersChangedList[0].also {
                assertEquals(OrderDisposition.Filled, it.disposition)
            }

            val makerOrder = response.ordersChangedList[1].also {
                assertEquals(OrderDisposition.PartiallyFilled, it.disposition)
                assertEquals(sellOrder.guid, it.guid)
            }

            response.assertTrades(
                market,
                listOf(
                    ExpectedTrade(
                        buyOrderGuid = takerOrder.guid,
                        sellOrderGuid = makerOrder.guid,
                        price = BigDecimal("17.550"),
                        amount = BigDecimal("0.43210"),
                        buyerFee = BigDecimal("0.1516671000000000"),
                        sellerFee = BigDecimal("0.075833550000000"),
                    ),
                ),
            )

            // each of the maker and taker should have two balance changed messages, one for each asset
            response.assertBalanceChanges(
                market,
                listOf(
                    Triple(taker, market.quoteAsset, -BigDecimal("7.7350221")),
                    Triple(maker, market.baseAsset, -BigDecimal("0.4321")),
                    Triple(taker, market.baseAsset, BigDecimal("0.4321")),
                    Triple(maker, market.quoteAsset, BigDecimal("7.50752145")),
                ),
            )
            response.tradesCreatedList[0]
        }

        // now rollback the settlement - all the balances should be back to their original values
        sequencer.failedSettlement(taker, maker, market, trade).also { response ->
            response.assertBalanceChanges(
                market,
                listOf(
                    Triple(maker, market.baseAsset, BigDecimal("0.4321")),
                    Triple(maker, market.quoteAsset, -BigDecimal("7.50752145")),
                    Triple(taker, market.baseAsset, -BigDecimal("0.4321")),
                    Triple(taker, market.quoteAsset, BigDecimal("7.7350221")),
                ),
            )
        }

        // all balances should be back to original values
        sequencer.withdrawal(maker, market.baseAsset, BigDecimal("100"), makerBaseBalance)
        sequencer.withdrawal(maker, market.quoteAsset, BigDecimal("100"), makerQuoteBalance)
        sequencer.withdrawal(taker, market.baseAsset, BigDecimal("100"), BigDecimal("10"))
        sequencer.withdrawal(taker, market.quoteAsset, BigDecimal("100"), BigDecimal("10"))
    }

    @Test
    fun `Test autoreduce on failed settlements`() {
        val sequencer = SequencerClient()
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))

        val market = sequencer.createMarket(MarketId("BTC9/ETH9"))

        val maker = generateWalletAddress()
        val taker = generateWalletAddress()

        // maker deposits some of both assets -- 2 BTC, 10 ETH
        val makerBaseBalance = BigDecimal("2")
        val makerQuoteBalance = BigDecimal("10")
        sequencer.deposit(maker, market.baseAsset, makerBaseBalance)
        sequencer.deposit(maker, market.quoteAsset, makerQuoteBalance)

        // place a sell order
        val makerLimitSellOrder = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("2"), BigDecimal("17.550"), maker, Order.Type.LimitSell)

        // place a market buy and see that it gets executed
        sequencer.deposit(taker, market.quoteAsset, BigDecimal("40"))
        sequencer.deposit(taker, market.baseAsset, BigDecimal("1"))

        val trade = sequencer.addOrder(market, BigDecimal("1"), null, taker, Order.Type.MarketBuy).let { response ->
            assertEquals(2, response.ordersChangedCount)

            val takerOrder = response.ordersChangedList[0].also {
                assertEquals(OrderDisposition.Filled, it.disposition)
            }

            val makerOrder = response.ordersChangedList[1].also {
                assertEquals(OrderDisposition.PartiallyFilled, it.disposition)
                assertEquals(makerLimitSellOrder.guid, it.guid)
            }

            response.assertTrades(
                market,
                listOf(
                    ExpectedTrade(
                        buyOrderGuid = takerOrder.guid,
                        sellOrderGuid = makerOrder.guid,
                        price = BigDecimal("17.550"),
                        amount = BigDecimal("1"),
                        buyerFee = BigDecimal("0.3510"),
                        sellerFee = BigDecimal("0.1755"),
                    ),
                ),
            )

            // each of the maker and taker should have two balance changed messages, one for each asset
            response.assertBalanceChanges(
                market,
                listOf(
                    Triple(taker, market.quoteAsset, -BigDecimal("17.901")),
                    Triple(maker, market.baseAsset, -BigDecimal("1")),
                    Triple(taker, market.baseAsset, BigDecimal("1")),
                    Triple(maker, market.quoteAsset, BigDecimal("17.3745")),
                ),
            )
            response.tradesCreatedList[0]
        }

        // place an order for taker to sell 2 BTC - they started with 1 and just bought 1, so they have 2 total
        val takerLimitSellOrder = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("2"), BigDecimal("17.500"), taker, Order.Type.LimitSell)

        // now rollback the settlement - all the balances should be back to their original values
        sequencer.failedSettlement(taker, maker, market, trade).also { response ->
            response.assertBalanceChanges(
                market,
                listOf(
                    Triple(maker, market.baseAsset, BigDecimal("1")),
                    Triple(maker, market.quoteAsset, -BigDecimal("17.3745")),
                    Triple(taker, market.baseAsset, -BigDecimal("1")),
                    Triple(taker, market.quoteAsset, BigDecimal("17.901")),
                ),
            )

            assertEquals(response.ordersChangedList.size, 1)
            val reducedBid = response.ordersChangedList.first { it.guid == takerLimitSellOrder.guid }
            assertEquals(OrderDisposition.AutoReduced, reducedBid.disposition)
            assertEquals(BigDecimal("1").setScale(market.baseDecimals), reducedBid.newQuantity.fromFundamentalUnits(market.baseDecimals))
        }

        // all balances should be back to original values
        sequencer.withdrawal(maker, market.baseAsset, BigDecimal("10"), makerBaseBalance)
        sequencer.withdrawal(maker, market.quoteAsset, BigDecimal("100"), makerQuoteBalance)
        sequencer.withdrawal(taker, market.baseAsset, BigDecimal("10"), BigDecimal("1"))
        sequencer.withdrawal(taker, market.quoteAsset, BigDecimal("100"), BigDecimal("40"))
    }

    @Test
    fun `Test failed settlements - balances can go negative`() {
        val sequencer = SequencerClient()
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))

        val market = sequencer.createMarket(MarketId("BTC10/ETH10"))

        val maker = generateWalletAddress()
        val taker = generateWalletAddress()

        // maker deposits some of both assets -- 2 BTC, 2 ETH
        val makerBaseBalance = BigDecimal("10")
        val makerQuoteBalance = BigDecimal("10")
        sequencer.deposit(maker, market.baseAsset, makerBaseBalance)
        sequencer.deposit(maker, market.quoteAsset, makerQuoteBalance)

        // place an order and see that it gets accepted
        sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.12345"), BigDecimal("17.500"), maker, Order.Type.LimitBuy)

        // place a sell order
        val sellOrder = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.54321"), BigDecimal("17.550"), maker, Order.Type.LimitSell)

        // place a market buy and see that it gets executed
        sequencer.deposit(taker, market.quoteAsset, BigDecimal("10"))
        sequencer.deposit(taker, market.baseAsset, BigDecimal("10"))

        val trade = sequencer.addOrder(market, BigDecimal("0.43210"), null, taker, Order.Type.MarketBuy).let { response ->
            assertEquals(2, response.ordersChangedCount)

            val takerOrder = response.ordersChangedList[0].also {
                assertEquals(OrderDisposition.Filled, it.disposition)
            }

            val makerOrder = response.ordersChangedList[1].also {
                assertEquals(OrderDisposition.PartiallyFilled, it.disposition)
                assertEquals(sellOrder.guid, it.guid)
            }

            response.assertTrades(
                market,
                listOf(
                    ExpectedTrade(
                        buyOrderGuid = takerOrder.guid,
                        sellOrderGuid = makerOrder.guid,
                        price = BigDecimal("17.550"),
                        amount = BigDecimal("0.43210"),
                        buyerFee = BigDecimal("0.1516671"),
                        sellerFee = BigDecimal("0.07583355"),
                    ),
                ),
            )

            response.assertBalanceChanges(
                market,
                listOf(
                    Triple(taker, market.quoteAsset, -BigDecimal("7.7350221")),
                    Triple(maker, market.baseAsset, -BigDecimal("0.43210")),
                    Triple(taker, market.baseAsset, BigDecimal("0.43210")),
                    Triple(maker, market.quoteAsset, BigDecimal("7.50752145")),
                ),
            )

            response.tradesCreatedList[0]
        }

        // balances now should be:
        //   maker BTC1 = 10 - 0.43210 = 9.5679
        //         ETH1 = 10 + 7.50752145 = 17.50752145
        //   taker BTC1 = 10 + 0.43210
        //         ETH1 = 10 - 7.7350221 = 2.2649779
        // withdraw everything
        sequencer.withdrawal(maker, market.baseAsset, BigDecimal("100"), BigDecimal("9.5679"))
        sequencer.withdrawal(maker, market.quoteAsset, BigDecimal("100"), BigDecimal("17.50752145"))
        sequencer.withdrawal(taker, market.baseAsset, BigDecimal("100"), BigDecimal("10.43210"))
        sequencer.withdrawal(taker, market.quoteAsset, BigDecimal("100"), BigDecimal("2.2649779"))

        // now rollback the settlement - some balances go negative (takers base balance, and makers quote balance
        sequencer.failedSettlement(taker, maker, market, trade).also { response ->
            response.assertBalanceChanges(
                market,
                listOf(
                    Triple(maker, market.baseAsset, BigDecimal("0.43210")),
                    Triple(maker, market.quoteAsset, -BigDecimal("7.50752145")),
                    Triple(taker, market.baseAsset, -BigDecimal("0.43210")),
                    Triple(taker, market.quoteAsset, BigDecimal("7.7350221")),
                ),
            )
        }

        sequencer.withdrawal(maker, market.baseAsset, BigDecimal("100"), BigDecimal("0.43210"))
        sequencer.withdrawal(taker, market.quoteAsset, BigDecimal("100"), BigDecimal("7.7350221"))
        // no balance change for these two since they went negative
        sequencer.withdrawal(maker, market.quoteAsset, BigDecimal("100"), null)
        sequencer.withdrawal(taker, market.baseAsset, BigDecimal("100"), null)

        // now deposit to take balance positive and withdraw all to make sure adjustments are properly applied
        sequencer.deposit(maker, market.quoteAsset, BigDecimal("10"))
        sequencer.deposit(taker, market.baseAsset, BigDecimal("10"))

        sequencer.withdrawal(maker, market.quoteAsset, BigDecimal("100"), BigDecimal("2.49247855"))
        sequencer.withdrawal(taker, market.baseAsset, BigDecimal("100"), BigDecimal("9.56790000"))
    }

    private val rnd = Random(0)

    private fun generateWalletAddress(): WalletAddress =
        rnd.nextLong().toWalletAddress()
}
