package xyz.funkybit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import xyz.funkybit.core.model.SequencerUserId
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.db.FeeRates
import xyz.funkybit.core.model.db.UserId
import xyz.funkybit.core.sequencer.toSequencerId
import xyz.funkybit.core.utils.toFundamentalUnits
import xyz.funkybit.sequencer.core.MarketId
import xyz.funkybit.sequencer.core.notional
import xyz.funkybit.sequencer.core.notionalFee
import xyz.funkybit.sequencer.core.toBigInteger
import xyz.funkybit.sequencer.proto.Order
import xyz.funkybit.sequencer.proto.OrderChangeRejected
import xyz.funkybit.sequencer.proto.OrderDisposition
import xyz.funkybit.sequencer.proto.SequencerError
import xyz.funkybit.sequencer.proto.newQuantityOrNull
import xyz.funkybit.testutils.ExpectedLimitsUpdate
import xyz.funkybit.testutils.ExpectedTrade
import xyz.funkybit.testutils.MockClock
import xyz.funkybit.testutils.SequencerClient
import xyz.funkybit.testutils.assertBalanceChanges
import xyz.funkybit.testutils.assertLimits
import xyz.funkybit.testutils.assertTrades
import xyz.funkybit.testutils.fromFundamentalUnits
import xyz.funkybit.testutils.inSats
import xyz.funkybit.testutils.inWei
import java.math.BigDecimal
import java.math.BigInteger

class TestSequencer {
    private val mockClock = MockClock()

    companion object {
        @JvmStatic
        fun percentages() = listOf(
            Arguments.of(0),
            Arguments.of(100),
        )
    }

    @Test
    fun `Test basic order matching`() {
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))
        val btcWithdrawalFee = BigDecimal("0.0001").inSats()
        sequencer.setWithdrawalFees(
            listOf(
                SequencerClient.WithdrawalFee(Symbol("BTC1"), btcWithdrawalFee),
            ),
        )

        val market = sequencer.createMarket(MarketId("BTC1/ETH1"))
        val btc1 = market.baseAsset
        val eth1 = market.quoteAsset

        val maker = generateUser()
        sequencer.deposit(maker, btc1, BigDecimal("10")).also { response ->
            response.assertBalanceChanges(
                market,
                listOf(
                    Triple(maker, btc1, BigDecimal("10")),
                ),
            )
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market.id, base = BigDecimal("10").inSats(), quote = BigDecimal("0").inWei()),
                ),
            )
        }
        sequencer.deposit(maker, eth1, BigDecimal("10")).also { response ->
            response.assertBalanceChanges(
                market,
                listOf(
                    Triple(maker, eth1, BigDecimal("10")),
                ),
            )
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market.id, base = BigDecimal("10").inSats(), quote = BigDecimal("10").inWei()),
                ),
            )
        }

        // place a buy order
        sequencer.addOrder(market, BigDecimal("0.1"), BigDecimal("10.000"), maker, Order.Type.LimitBuy).also { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market.id, base = BigDecimal("10").inSats(), quote = BigDecimal("8.99").inWei()),
                ),
            )
        }

        // place a sell order
        val makerSellOrderGuid = sequencer.addOrder(market, BigDecimal("0.5"), BigDecimal("12.000"), maker, Order.Type.LimitSell).let { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market.id, base = BigDecimal("9.5").inSats(), quote = BigDecimal("8.99").inWei()),
                ),
            )
            response.ordersChangedList.first().guid
        }

        val taker = generateUser()
        sequencer.deposit(taker, eth1, BigDecimal("10")).also { response ->
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(taker, market.id, base = BigDecimal("0").inSats(), quote = BigDecimal("10").inWei()),
                ),
            )
        }

        // place a market buy and see that it gets executed
        sequencer.addOrder(market, BigDecimal("0.2"), null, taker, Order.Type.MarketBuy).also { response ->
            assertEquals(mockClock.currentTimeMillis(), response.createdAt)
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
                        price = BigDecimal("12.00"),
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

            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(
                        maker,
                        market.id,
                        // 9.8 on balance minus 0.3 BTC locked in the sell order
                        base = BigDecimal("9.5").inSats(),
                        // 12.376 on balance minus 1.01 locked in the buy order
                        quote = BigDecimal("11.366").inWei(),
                    ),
                    ExpectedLimitsUpdate(
                        taker,
                        market.id,
                        base = BigDecimal("0.2").inSats(),
                        quote = BigDecimal("7.552").inWei(),
                    ),
                ),
            )
        }

        // now try a market sell which can only be partially filled and see that it gets executed
        sequencer.addOrder(market, BigDecimal("0.2"), null, taker, Order.Type.MarketSell).also { response ->
            assertEquals(mockClock.currentTimeMillis(), response.createdAt)
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
                        price = BigDecimal("10.00"),
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

            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(
                        maker,
                        market.id,
                        // 9.9 on balance minus 0.3 BTC locked in the sell order
                        base = BigDecimal("9.6").inSats(),
                        // 11.366 as on balance since buy order was filled and no ETH is locked in the orders
                        quote = BigDecimal("11.366").inWei(),
                    ),
                    ExpectedLimitsUpdate(
                        taker,
                        market.id,
                        base = BigDecimal("0.1").inSats(),
                        quote = BigDecimal("8.532").inWei(),
                    ),
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
        sequencer.withdrawal(maker, btc1, BigDecimal.ZERO, expectedAmount = BigDecimal("9.9"), expectedWithdrawalFee = btcWithdrawalFee)
        sequencer.withdrawal(maker, eth1, BigDecimal.ZERO, expectedAmount = BigDecimal("11.366"))
        sequencer.withdrawal(taker, btc1, BigDecimal.ZERO, expectedAmount = BigDecimal("0.1"), expectedWithdrawalFee = btcWithdrawalFee)
        sequencer.withdrawal(taker, eth1, BigDecimal.ZERO, expectedAmount = BigDecimal("8.532"))
    }

    @ParameterizedTest
    @MethodSource("percentages")
    fun `Test a market order that executes against multiple orders at multiple levels`(percentage: Int) {
        val sequencer = SequencerClient(mockClock)
        val market = sequencer.createMarket(MarketId("BTC:1338/ETH:1338"))
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))

        val lp1 = generateUser()
        val lp2 = generateUser()
        val tkr = generateUser()

        sequencer.deposit(lp1, market.baseAsset, BigDecimal("0.31"))
        sequencer.deposit(lp2, market.baseAsset, BigDecimal("0.31"))

        val sell1Order = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.01"), BigDecimal("17.550"), lp1, Order.Type.LimitSell)
        val sell2Order = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.01"), BigDecimal("17.550"), lp2, Order.Type.LimitSell)
        val sell3Order = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.1"), BigDecimal("17.600"), lp1, Order.Type.LimitSell)
        val sell4Order = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.1"), BigDecimal("17.600"), lp2, Order.Type.LimitSell)
        val sell5Order = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.2"), BigDecimal("17.700"), lp1, Order.Type.LimitSell)
        val sell6Order = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.2"), BigDecimal("17.700"), lp2, Order.Type.LimitSell)

        // clearing price would be (0.02 * 17.55 + 0.15 * 17.6) / 0.17 = 17.59412
        // notional is 0.17 * 17.595 = 2.9910004, fee would be notional * 0.02 = 0.059820008
        sequencer.deposit(tkr, market.quoteAsset, BigDecimal("2.9910004") + BigDecimal("0.059820008"))

        sequencer.addOrder(market, BigDecimal("0.17"), null, tkr, Order.Type.MarketBuy).also { response ->
            assertEquals(mockClock.currentTimeMillis(), response.createdAt)
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
                        price = BigDecimal("17.55"),
                        amount = BigDecimal("0.01"),
                        buyerFee = BigDecimal("0.00351"),
                        sellerFee = BigDecimal("0.001755"),
                    ),
                    ExpectedTrade(
                        buyOrderGuid = takerOrder.guid,
                        sellOrderGuid = makerOrder2.guid,
                        price = BigDecimal("17.55"),
                        amount = BigDecimal("0.01"),
                        buyerFee = BigDecimal("0.00351"),
                        sellerFee = BigDecimal("0.001755"),
                    ),
                    ExpectedTrade(
                        buyOrderGuid = takerOrder.guid,
                        sellOrderGuid = makerOrder3.guid,
                        price = BigDecimal("17.60"),
                        amount = BigDecimal("0.1"),
                        buyerFee = BigDecimal("0.0352"),
                        sellerFee = BigDecimal("0.0176"),
                    ),
                    ExpectedTrade(
                        buyOrderGuid = takerOrder.guid,
                        sellOrderGuid = makerOrder4.guid,
                        price = BigDecimal("17.60"),
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
        sequencer.addOrder(market, if (percentage == 0) BigDecimal("0.45") else BigDecimal.ZERO, null, tkr, Order.Type.MarketBuy, percentage = percentage).also { response ->
            assertEquals(mockClock.currentTimeMillis(), response.createdAt)
            assertEquals(4, response.ordersChangedCount)
            val takerOrder = response.ordersChangedList[0].also {
                assertEquals(OrderDisposition.Filled, it.disposition)
                if (percentage == 100) {
                    assertEquals(BigDecimal("0.45").toFundamentalUnits(market.baseDecimals), it.newQuantity.toBigInteger())
                }
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
                        price = BigDecimal("17.60"),
                        amount = BigDecimal("0.05"),
                        buyerFee = BigDecimal("0.0176"),
                        sellerFee = BigDecimal("0.0088"),
                    ),
                    ExpectedTrade(
                        buyOrderGuid = takerOrder.guid,
                        sellOrderGuid = sell5Order.guid,
                        price = BigDecimal("17.70"),
                        amount = BigDecimal("0.2"),
                        buyerFee = BigDecimal("0.0708"),
                        sellerFee = BigDecimal("0.0354"),
                    ),
                    ExpectedTrade(
                        buyOrderGuid = takerOrder.guid,
                        sellOrderGuid = sell6Order.guid,
                        price = BigDecimal("17.70"),
                        amount = BigDecimal("0.2"),
                        buyerFee = BigDecimal(if (percentage == 100) "0.070851408" else "0.0708"),
                        sellerFee = BigDecimal("0.0354"),
                    ),
                ),
            )
        }
    }

    @Test
    fun `Test market order that executes against multiple orders at multiple levels`() {
        val sequencer = SequencerClient(mockClock)
        val market = sequencer.createMarket(MarketId("BTC20/ETH20"))
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))

        val lp1 = generateUser()
        val lp2 = generateUser()
        val tkr = generateUser()

        sequencer.deposit(lp1, market.baseAsset, BigDecimal("1")).also { response ->
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(lp1, market.id, base = BigDecimal("1").inSats(), quote = BigDecimal("0").inWei()),
                ),
            )
        }
        sequencer.deposit(lp2, market.baseAsset, BigDecimal("1")).also { response ->
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(lp2, market.id, base = BigDecimal("1").inSats(), quote = BigDecimal("0").inWei()),
                ),
            )
        }

        sequencer.addOrder(market, BigDecimal("0.19762845"), BigDecimal("17.750"), lp1, Order.Type.LimitSell).also { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(lp1, market.id, base = BigDecimal("0.80237155").inSats(), quote = BigDecimal("0").inWei()),
                ),
            )
        }
        sequencer.addOrder(market, BigDecimal("0.79051383"), BigDecimal("18.000"), lp2, Order.Type.LimitSell).also { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(lp2, market.id, base = BigDecimal("0.20948617").inSats(), quote = BigDecimal("0").inWei()),
                ),
            )
        }

        sequencer.deposit(tkr, market.quoteAsset, BigDecimal("10")).also { response ->
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(tkr, market.id, base = BigDecimal("0").inSats(), quote = BigDecimal("10").inWei()),
                ),
            )
        }

        sequencer.addOrder(market, BigDecimal.ZERO, null, tkr, Order.Type.MarketBuy, percentage = 100).also { response ->
            assertEquals(mockClock.currentTimeMillis(), response.createdAt)
            assertEquals(3, response.ordersChangedCount)
            response.ordersChangedList[0].also {
                assertEquals(OrderDisposition.Filled, it.disposition)
                assertEquals(BigDecimal("0.54740714").toFundamentalUnits(market.baseDecimals), it.newQuantity.toBigInteger())
            }
            assertEquals(OrderDisposition.Filled, response.ordersChangedList[1].disposition)
            assertEquals(OrderDisposition.PartiallyFilled, response.ordersChangedList[2].disposition)

            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(lp1, market.id, base = BigDecimal("0.80237155").inSats(), quote = BigDecimal("3.472825937625").inWei()),
                    ExpectedLimitsUpdate(lp2, market.id, base = BigDecimal("0.20948617").inSats(), quote = BigDecimal("6.233056255800").inWei()),
                    ExpectedLimitsUpdate(tkr, market.id, base = BigDecimal("0.54740714").inSats(), quote = BigDecimal("0").inWei()),
                ),
            )
        }
    }

    @Test
    fun `test balances`() {
        val sequencer = SequencerClient(mockClock)
        val walletAddress = generateUser()

        val ethWithdrawalFee = BigDecimal("0.001").inWei()
        val pepeWithdrawalFee = BigDecimal("0.002").inWei()
        sequencer.setWithdrawalFees(
            listOf(
                SequencerClient.WithdrawalFee(Symbol("ETH"), ethWithdrawalFee),
                SequencerClient.WithdrawalFee(Symbol("PEPE"), pepeWithdrawalFee),
            ),
        )

        val asset1 = SequencerClient.Asset("ETH", decimals = 18)
        val asset2 = SequencerClient.Asset("PEPE", decimals = 18)
        val amount = BigDecimal("0.2")

        // do a deposit
        sequencer.deposit(walletAddress, asset1, amount).also { response ->
            response.assertLimits(emptyList()) // sequencer has no markets with asset1
        }

        // withdraw half
        sequencer.withdrawal(walletAddress, asset1, BigDecimal("0.1"), expectedWithdrawalFee = ethWithdrawalFee).also { response ->
            response.assertLimits(emptyList()) // sequencer has no markets with asset1
        }

        // request for more than balance - should fail
        sequencer.withdrawal(walletAddress, asset1, BigDecimal("0.2"), expectedAmount = null)

        // request for less than the fee should fail
        sequencer.withdrawal(walletAddress, asset1, BigDecimal("0.0001"), expectedAmount = null)

        // request for equal to the fee should fail
        sequencer.withdrawal(walletAddress, asset1, BigDecimal("0.001"), expectedAmount = null)

        // request for just above the fee should work
        sequencer.withdrawal(walletAddress, asset1, BigDecimal("0.0011"), expectedWithdrawalFee = ethWithdrawalFee)

        // request for the entire balance (set amount to zero) - should withdraw other half
        sequencer.withdrawal(walletAddress, asset1, BigDecimal.ZERO, expectedAmount = BigDecimal("0.1") - BigDecimal("0.0011"), expectedWithdrawalFee = ethWithdrawalFee)

        // attempt to withdraw more does not return a balance change
        sequencer.withdrawal(walletAddress, asset1, BigDecimal("0.1"), expectedAmount = null)

        // attempt to withdraw from an unknown wallet or asset does not return a balance change
        sequencer.withdrawal(generateUser(), asset1, BigDecimal("1"), expectedAmount = null)
        sequencer.withdrawal(walletAddress, asset2, BigDecimal("1"), expectedAmount = null)

        // can combine deposits and withdrawals in a batch - amount should be net
        sequencer.depositsAndWithdrawals(walletAddress, asset1, listOf(BigDecimal("10"), BigDecimal("1").negate()), expectedWithdrawalFees = listOf(ethWithdrawalFee))

        // if it nets to 0, no balance change returned
        sequencer.depositsAndWithdrawals(walletAddress, asset1, listOf(BigDecimal("10").negate(), BigDecimal("10")), expectedAmount = null, expectedWithdrawalFees = listOf(ethWithdrawalFee))
    }

    @Test
    fun `test limit checking on orders`() {
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))
        val market1 = sequencer.createMarket(MarketId("BTC3/ETH3"))
        val market2 = sequencer.createMarket(MarketId("ETH3/USDC3"), baseDecimals = 18, quoteDecimals = 6, tickSize = BigDecimal("1"))

        val maker = generateUser()
        // cannot place a buy or sell limit order without any deposits
        assertEquals(SequencerError.ExceedsLimit, sequencer.addOrder(market1, BigDecimal("0.1"), BigDecimal("12.00"), maker, Order.Type.LimitSell).error)
        assertEquals(SequencerError.ExceedsLimit, sequencer.addOrder(market1, BigDecimal("0.1"), BigDecimal("11.00"), maker, Order.Type.LimitBuy).error)

        // deposit some base and can sell
        sequencer.deposit(maker, market1.baseAsset, BigDecimal("0.1")).also {
            ExpectedLimitsUpdate(maker, market1.id, base = BigDecimal("0.1").inSats(), quote = BigDecimal("0").inWei())
        }
        sequencer.addOrder(market1, BigDecimal("0.1"), BigDecimal("12.00"), maker, Order.Type.LimitSell).also { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market1.id, base = BigDecimal("0").inSats(), quote = BigDecimal("0").inWei()),
                ),
            )
        }

        // deposit some quote, but still can't buy because of the fee
        sequencer.deposit(maker, market1.quoteAsset, BigDecimal("11.00") * BigDecimal("0.1")).also { response ->
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market1.id, base = BigDecimal("0.0").inSats(), quote = BigDecimal("1.10").inWei()),
                    ExpectedLimitsUpdate(maker, market2.id, base = BigDecimal("1.10").inWei(), quote = BigDecimal("0").toFundamentalUnits(6)),
                ),
            )
        }
        assertEquals(SequencerError.ExceedsLimit, sequencer.addOrder(market1, BigDecimal("0.1"), BigDecimal("11.00"), maker, Order.Type.LimitBuy).error)

        // can buy after depositing more quote to cover the fee
        sequencer.deposit(maker, market1.quoteAsset, BigDecimal("11.00") * BigDecimal("0.001")).also { response ->
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market1.id, base = BigDecimal("0.0").inSats(), quote = BigDecimal("1.111").inWei()),
                    ExpectedLimitsUpdate(maker, market2.id, base = BigDecimal("1.111").inWei(), quote = BigDecimal("0").toFundamentalUnits(6)),
                ),
            )
        }
        sequencer.addOrder(market1, BigDecimal("0.1"), BigDecimal("11.00"), maker, Order.Type.LimitBuy).also { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market1.id, base = BigDecimal("0").inSats(), quote = BigDecimal("0").inWei()),
                ),
            )
        }

        // but now that we've exhausted our balance we can't add more
        assertEquals(SequencerError.ExceedsLimit, sequencer.addOrder(market1, BigDecimal("0.001"), BigDecimal("12.00"), maker, Order.Type.LimitSell).error)
        assertEquals(SequencerError.ExceedsLimit, sequencer.addOrder(market1, BigDecimal("0.001"), BigDecimal("11.00"), maker, Order.Type.LimitBuy).error)

        // but we can reuse the same liquidity in another market
        sequencer.addOrder(market2, BigDecimal("1.111"), BigDecimal("100.00"), maker, Order.Type.LimitSell).also { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market2.id, base = BigDecimal("0").inWei(), quote = BigDecimal("0").toFundamentalUnits(6)),
                ),
            )
        }

        // if we deposit some more we can add another order
        sequencer.deposit(maker, market1.baseAsset, BigDecimal("0.1")).also { response ->
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market1.id, base = BigDecimal("0.1").inSats(), quote = BigDecimal("0").inWei()),
                ),
            )
        }
        sequencer.addOrder(market1, BigDecimal("0.1"), BigDecimal("12.00"), maker, Order.Type.LimitSell).also { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market1.id, base = BigDecimal("0").inSats(), quote = BigDecimal("0").inWei()),
                ),
            )
        }

        // but not more
        assertEquals(SequencerError.ExceedsLimit, sequencer.addOrder(market1, BigDecimal("0.1"), BigDecimal("12.00"), maker, Order.Type.LimitSell).error)

        // unless a trade increases the balance
        val taker = generateUser()
        sequencer.deposit(taker, market1.baseAsset, BigDecimal("0.1")).also { response ->
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(taker, market1.id, base = BigDecimal("0.1").inSats(), quote = BigDecimal("0").inWei()),
                ),
            )
        }
        sequencer.addOrder(market1, BigDecimal("0.1"), null, taker, Order.Type.MarketSell).also { response ->
            assertEquals(mockClock.currentTimeMillis(), response.createdAt)
            assertEquals(OrderDisposition.Filled, response.ordersChangedList.first().disposition)
            assertEquals(OrderDisposition.AutoReduced, response.ordersChangedList.last().disposition)
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market1.id, base = BigDecimal("0.1").inSats(), quote = BigDecimal("0").inWei()),
                    // due to auto-reducing
                    ExpectedLimitsUpdate(maker, market2.id, base = BigDecimal("0").inWei(), quote = BigDecimal("0").toFundamentalUnits(6)),
                    ExpectedLimitsUpdate(taker, market1.id, base = BigDecimal("0").inSats(), quote = BigDecimal("1.078000000000000000").inWei()),
                    ExpectedLimitsUpdate(taker, market2.id, base = BigDecimal("1.078000000000000000").inWei(), quote = BigDecimal("0").toFundamentalUnits(6)),
                ),
            )
        }

        sequencer.addOrder(market1, BigDecimal("0.1"), BigDecimal("12.00"), maker, Order.Type.LimitSell).also { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market1.id, base = BigDecimal("0").inSats(), quote = BigDecimal("0").inWei()),
                ),
            )
        }
    }

    @Test
    fun `test LimitBuy order can cross the market`() {
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))
        val market = sequencer.createMarket(MarketId("BTC8/ETH8"))

        val maker = generateUser()
        // deposit and prepare liquidity
        sequencer.deposit(maker, market.baseAsset, BigDecimal("0.2"))
        sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.2"), BigDecimal("17.55"), maker, Order.Type.LimitSell)

        // prepare second maker
        val crossingTheMarketMaker = generateUser()
        sequencer.deposit(crossingTheMarketMaker, market.quoteAsset, BigDecimal("18.00") * BigDecimal("0.3"))
        // deposit extra for the fees
        sequencer.deposit(crossingTheMarketMaker, market.quoteAsset, BigDecimal("0.03510") * BigDecimal(2))

        // limit order can cross the market and be filled immediately
        sequencer.addOrder(market, BigDecimal("0.1"), BigDecimal("18.00"), crossingTheMarketMaker, Order.Type.LimitBuy).also { response ->
            assertEquals(mockClock.currentTimeMillis(), response.createdAt)
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
                        price = BigDecimal("17.55"),
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
            assertEquals(mockClock.currentTimeMillis(), response.createdAt)
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
                        price = BigDecimal("17.55"),
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
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))
        val market = sequencer.createMarket(MarketId("BTC9/ETH9"))

        val maker = generateUser()
        // deposit and prepare liquidity
        sequencer.deposit(maker, market.baseAsset, BigDecimal("0.2"))
        val sellOrder1 = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.01"), BigDecimal("17.55"), maker, Order.Type.LimitSell)
        val sellOrder2 = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.01"), BigDecimal("18.00"), maker, Order.Type.LimitSell)
        val sellOrder3 = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.01"), BigDecimal("18.50"), maker, Order.Type.LimitSell)
        sequencer.addOrder(market, BigDecimal("0.01"), BigDecimal("19.00"), maker, Order.Type.LimitSell)
        sequencer.addOrder(market, BigDecimal("0.01"), BigDecimal("19.50"), maker, Order.Type.LimitSell)

        // prepare second maker
        val crossingTheMarketMaker = generateUser()
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
            assertEquals(mockClock.currentTimeMillis(), response.createdAt)
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
                        price = BigDecimal("17.55"),
                        amount = BigDecimal("0.01"),
                        buyerFee = BigDecimal("0.003510"),
                        sellerFee = BigDecimal("0.001755"),
                    ),
                    ExpectedTrade(
                        buyOrderGuid = takerOrder.guid,
                        sellOrderGuid = sellOrder2.guid,
                        price = BigDecimal("18.00"),
                        amount = BigDecimal("0.01"),
                        buyerFee = BigDecimal("0.0036"),
                        sellerFee = BigDecimal("0.0018"),
                    ),
                    ExpectedTrade(
                        buyOrderGuid = takerOrder.guid,
                        sellOrderGuid = sellOrder3.guid,
                        price = BigDecimal("18.50"),
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
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))
        val market = sequencer.createMarket(MarketId("BTC10/ETH10"))

        val maker = generateUser()
        // deposit and prepare liquidity
        sequencer.deposit(maker, market.quoteAsset, BigDecimal("17.50") * BigDecimal("0.2"))
        // deposit extra for the fees
        sequencer.deposit(maker, market.quoteAsset, (BigDecimal("0.1750") * BigDecimal(2)))
        sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.2"), BigDecimal("17.50"), maker, Order.Type.LimitBuy)

        // prepare second maker
        val crossingTheMarketMaker = generateUser()
        sequencer.deposit(crossingTheMarketMaker, market.baseAsset, BigDecimal("0.3"))

        // limit order can cross the market and be filled immediately
        sequencer.addOrder(market, BigDecimal("0.1"), BigDecimal("17.00"), crossingTheMarketMaker, Order.Type.LimitSell).also { response ->
            assertEquals(mockClock.currentTimeMillis(), response.createdAt)
            assertEquals(2, response.ordersChangedList.size)
            val takerOrder = response.ordersChangedList.first()
            val makerOrder = response.ordersChangedList.last()

            response.assertTrades(
                market,
                listOf(
                    ExpectedTrade(
                        buyOrderGuid = makerOrder.guid,
                        sellOrderGuid = takerOrder.guid,
                        price = BigDecimal("17.50"),
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
            assertEquals(mockClock.currentTimeMillis(), response.createdAt)
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
                        price = BigDecimal("17.50"),
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
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))
        val market = sequencer.createMarket(MarketId("BTC11/ETH11"))

        val maker = generateUser()
        // deposit and prepare liquidity
        sequencer.deposit(maker, market.quoteAsset, BigDecimal("17.50") * BigDecimal("0.2"))
        val buyOrder1 = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.01"), BigDecimal("17.50"), maker, Order.Type.LimitBuy)
        val buyOrder2 = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.01"), BigDecimal("17.00"), maker, Order.Type.LimitBuy)
        val buyOrder3 = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.01"), BigDecimal("16.50"), maker, Order.Type.LimitBuy)
        sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.01"), BigDecimal("16.00"), maker, Order.Type.LimitBuy)
        sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.01"), BigDecimal("15.50"), maker, Order.Type.LimitBuy)

        // prepare second maker
        val crossingTheMarketMaker = generateUser()
        sequencer.deposit(crossingTheMarketMaker, market.baseAsset, BigDecimal("0.3"))

        // limit order is partially filled until price is reached
        sequencer.addOrder(market, BigDecimal("0.05"), BigDecimal("16.50"), crossingTheMarketMaker, Order.Type.LimitSell).also { response ->
            assertEquals(mockClock.currentTimeMillis(), response.createdAt)
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
                        price = BigDecimal("17.50"),
                        amount = BigDecimal("0.01"),
                        buyerFee = BigDecimal("0.001750"),
                        sellerFee = BigDecimal("0.003500"),
                    ),
                    ExpectedTrade(
                        buyOrderGuid = buyOrder2.guid,
                        sellOrderGuid = takerOrder.guid,
                        price = BigDecimal("17.00"),
                        amount = BigDecimal("0.01"),
                        buyerFee = BigDecimal("0.001700"),
                        sellerFee = BigDecimal("0.003400"),
                    ),
                    ExpectedTrade(
                        buyOrderGuid = buyOrder3.guid,
                        sellOrderGuid = takerOrder.guid,
                        price = BigDecimal("16.50"),
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
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))
        val market = sequencer.createMarket(MarketId("BTC4/ETH4"))

        val maker = generateUser()
        sequencer.deposit(maker, market.baseAsset, BigDecimal("0.1")).also { response ->
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market.id, base = BigDecimal("0.1").inSats(), quote = BigDecimal("0").inWei()),
                ),
            )
        }

        val order = sequencer.addOrder(market, BigDecimal("0.1"), BigDecimal("17.55"), maker, Order.Type.LimitSell).let { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market.id, base = BigDecimal("0.0").inSats(), quote = BigDecimal("0").inWei()),
                ),
            )
            response.ordersChangedList.first()
        }

        sequencer.cancelOrder(market, order.guid, maker).also { response ->
            assertEquals(mockClock.currentTimeMillis(), response.createdAt)
            assertEquals(OrderDisposition.Canceled, response.ordersChangedList.first().disposition)
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market.id, base = BigDecimal("0.1").inSats(), quote = BigDecimal("0").inWei()),
                ),
            )
        }

        val order2 = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.1"), BigDecimal("17.55"), maker, Order.Type.LimitSell)

        val taker = generateUser()
        sequencer.deposit(taker, market.quoteAsset, BigDecimal("1.1")).also { response ->
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(taker, market.id, base = BigDecimal("0").inSats(), quote = BigDecimal("1.1").inWei()),
                ),
            )
        }

        // have taker try to cancel maker order
        sequencer.cancelOrder(market, order2.guid, taker).also { response ->
            assertEquals(mockClock.currentTimeMillis(), response.createdAt)
            assertEquals(0, response.ordersChangedList.size)
            assertEquals(1, response.ordersChangeRejectedList.size)
            assertEquals(OrderChangeRejected.Reason.NotForUser, response.ordersChangeRejectedList.first().reason)
        }

        // try canceling an order which has been partially filled
        sequencer.addOrder(market, BigDecimal("0.05"), null, taker, Order.Type.MarketBuy).also { response ->
            assertEquals(mockClock.currentTimeMillis(), response.createdAt)
            val partiallyFilledOrder = response.ordersChangedList[1]
            assertEquals(OrderDisposition.PartiallyFilled, partiallyFilledOrder.disposition)
            assertEquals(order2.guid, partiallyFilledOrder.guid)

            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market.id, base = BigDecimal("0").inSats(), quote = BigDecimal("0.868725").inWei()),
                    ExpectedLimitsUpdate(taker, market.id, base = BigDecimal("0.05").inSats(), quote = BigDecimal("0.20495").inWei()),
                ),
            )
        }

        sequencer.cancelOrder(market, order2.guid, maker).also { response ->
            assertEquals(OrderDisposition.Canceled, response.ordersChangedList.first().disposition)

            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market.id, base = BigDecimal("0.05").inSats(), quote = BigDecimal("0.868725").inWei()),
                ),
            )
        }

        // cancel an invalid order
        sequencer.cancelOrder(market, order2.guid, maker).also { response ->
            assertEquals(mockClock.currentTimeMillis(), response.createdAt)
            assertEquals(0, response.ordersChangedList.size)
            assertEquals(1, response.ordersChangeRejectedList.size)
            assertEquals(OrderChangeRejected.Reason.DoesNotExist, response.ordersChangeRejectedList.first().reason)
        }
    }

    @Test
    fun `test auto-reduce from trades`() {
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))

        val market1 = sequencer.createMarket(MarketId("BTC6/ETH6"), tickSize = BigDecimal(1), baseDecimals = 8, quoteDecimals = 18)
        val market2 = sequencer.createMarket(MarketId("ETH6/USDC6"), tickSize = BigDecimal(1), baseDecimals = 18, quoteDecimals = 6)
        val market3 = sequencer.createMarket(MarketId("XXX6/ETH6"), tickSize = BigDecimal(1), baseDecimals = 6, quoteDecimals = 18)

        val maker = generateUser()

        // maker deposits 10.1 ETH
        sequencer.deposit(maker, market1.quoteAsset, BigDecimal("10.1")).also { response ->
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market1.id, base = BigInteger.ZERO, quote = BigDecimal("10.1").inWei()),
                    ExpectedLimitsUpdate(maker, market2.id, base = BigDecimal("10.1").inWei(), quote = BigInteger.ZERO),
                    ExpectedLimitsUpdate(maker, market3.id, base = BigInteger.ZERO, quote = BigDecimal("10.1").inWei()),
                ),
            )
        }

        // maker adds a bid in market1 using all 10.1 eth
        sequencer.addOrder(market1, BigDecimal("1"), BigDecimal("10"), maker, Order.Type.LimitBuy).also { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market1.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                ),
            )
        }

        // maker adds an offer in market2 using 10 eth
        val market2Offer = sequencer.addOrder(market2, BigDecimal("10"), BigDecimal("10"), maker, Order.Type.LimitSell).let { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market2.id, base = BigDecimal("0.1").inWei(), quote = BigInteger.ZERO),
                ),
            )
            response.ordersChangedList.first()
        }

        // maker also adds a bid in market3 using all 10.1 eth
        val market3Bid = sequencer.addOrder(market3, BigDecimal("0.5"), BigDecimal("20"), maker, Order.Type.LimitBuy).let { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market3.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                ),
            )
            response.ordersChangedList.first()
        }

        // now add a taker who will hit the market1 bid selling 0.6 BTC, this would consume 6 ETH + 0,06 ETH fee from maker
        val taker = generateUser()
        sequencer.deposit(taker, market1.baseAsset, BigDecimal("0.6")).also { response ->
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(taker, market1.id, base = BigDecimal("0.6").inSats(), quote = BigInteger.ZERO),
                ),
            )
        }
        sequencer.addOrder(market1, BigDecimal("0.6"), null, taker, Order.Type.MarketSell).also { response ->
            assertEquals(mockClock.currentTimeMillis(), response.createdAt)
            assertEquals(OrderDisposition.Filled, response.ordersChangedList.first().disposition)

            response.assertBalanceChanges(
                market1,
                listOf(
                    Triple(maker, market1.quoteAsset, -BigDecimal("6.06")),
                    Triple(taker, market1.baseAsset, -BigDecimal("0.6")),
                    Triple(maker, market1.baseAsset, BigDecimal("0.6")),
                    Triple(taker, market1.quoteAsset, BigDecimal("5.88")),
                ),
            )

            // the maker's offer in market2 should be auto-reduced
            val reducedOffer = response.ordersChangedList.first { it.guid == market2Offer.guid }
            assertEquals(OrderDisposition.AutoReduced, reducedOffer.disposition)
            assertEquals(BigDecimal("4.04").setScale(market2.baseDecimals), reducedOffer.newQuantity.fromFundamentalUnits(market2.baseDecimals))

            // also the maker's bid in market3 should be auto-reduced
            val reducedBid = response.ordersChangedList.first { it.guid == market3Bid.guid }
            assertEquals(OrderDisposition.AutoReduced, reducedBid.disposition)
            assertEquals(BigDecimal("0.2").setScale(market3.baseDecimals), reducedBid.newQuantity.fromFundamentalUnits(market3.baseDecimals))

            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market1.id, base = BigDecimal("0.6").inSats(), quote = BigInteger.ZERO),
                    ExpectedLimitsUpdate(maker, market2.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    ExpectedLimitsUpdate(maker, market3.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                    ExpectedLimitsUpdate(taker, market1.id, base = BigInteger.ZERO, quote = BigDecimal("5.88").inWei()),
                    ExpectedLimitsUpdate(taker, market2.id, base = BigDecimal("5.88").inWei(), quote = BigInteger.ZERO),
                    ExpectedLimitsUpdate(taker, market3.id, base = BigInteger.ZERO, quote = BigDecimal("5.88").inWei()),
                ),
            )
        }
    }

    @Test
    fun `test auto-reduce from withdrawals`() {
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))
        val market = sequencer.createMarket(MarketId("BTC7/ETH7"))

        val maker = generateUser()
        // maker deposits 10 BTC
        sequencer.deposit(maker, market.baseAsset, BigDecimal("10")).also { response ->
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market.id, base = BigDecimal("10").inSats(), quote = BigInteger.ZERO),
                ),
            )
        }
        // maker adds two offers combined which use all 10 BTC
        val order1 = sequencer.addOrder(market, BigDecimal("4"), BigDecimal("17.75"), maker, Order.Type.LimitSell).let { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market.id, base = BigDecimal("6").inSats(), quote = BigInteger.ZERO),
                ),
            )
            response.ordersChangedList.first()
        }
        val order2 = sequencer.addOrder(market, BigDecimal("6"), BigDecimal("18.00"), maker, Order.Type.LimitSell).let { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market.id, base = BigDecimal("0").inSats(), quote = BigInteger.ZERO),
                ),
            )
            response.ordersChangedList.first()
        }

        // now maker withdraws 7 BTC
        sequencer.withdrawal(maker, market.baseAsset, BigDecimal("7")).also { response ->
            assertEquals(mockClock.currentTimeMillis(), response.createdAt)
            val reducedOffer1 = response.ordersChangedList.first { it.guid == order1.guid }
            assertEquals(OrderDisposition.AutoReduced, reducedOffer1.disposition)
            assertEquals(BigDecimal("3").setScale(market.baseDecimals), reducedOffer1.newQuantity.fromFundamentalUnits(market.baseDecimals))

            val reducedOffer2 = response.ordersChangedList.first { it.guid == order2.guid }
            assertEquals(OrderDisposition.AutoReduced, reducedOffer2.disposition)
            assertEquals(BigDecimal("0").setScale(market.baseDecimals), reducedOffer2.newQuantity.fromFundamentalUnits(market.baseDecimals))

            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market.id, base = BigDecimal("0").inSats(), quote = BigInteger.ZERO),
                ),
            )
        }
    }

    @Test
    fun `fee rate change does not affect existing orders in the book`() {
        val sequencer = SequencerClient(mockClock)
        // set maker fee rate to 1% and taker fee rate to 2%
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))
        val market = sequencer.createMarket(MarketId("BTC8/ETH8"))

        val maker = generateUser()
        val taker = generateUser()

        sequencer.deposit(maker, market.baseAsset, BigDecimal("10"))
        sequencer.deposit(taker, market.quoteAsset, BigDecimal("200"))

        val sellOrder1 = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("5"), BigDecimal("10.00"), maker, Order.Type.LimitSell)

        // increase fee rates
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 2.0, taker = 4.0))

        val sellOrder2 = sequencer.addOrderAndVerifyAccepted(market, BigDecimal("5"), BigDecimal("10.00"), maker, Order.Type.LimitSell)

        sequencer.addOrder(market, BigDecimal("10"), null, taker, Order.Type.MarketBuy).also { response ->
            assertEquals(mockClock.currentTimeMillis(), response.createdAt)
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
                        price = BigDecimal("10.00"),
                        amount = BigDecimal("5.0"),
                        // taker's fee is 4%
                        buyerFee = BigDecimal("2.0"),
                        // maker's fee is 1% since first order was created before fee rate increase
                        sellerFee = BigDecimal("0.5"),
                    ),
                    ExpectedTrade(
                        buyOrderGuid = response.ordersChangedList[0].guid,
                        sellOrderGuid = sellOrder2.guid,
                        price = BigDecimal("10.00"),
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
    fun `fee rate increase - consumption released correctly`() {
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 0.0, taker = 0.0))
        val market = sequencer.createMarket(MarketId("BTC200/ETH200"))

        val maker = generateUser()
        sequencer.deposit(maker, market.quoteAsset, BigDecimal("50")).also { response ->
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market.id, base = BigInteger.ZERO, quote = BigDecimal("50").inWei()),
                ),
            )
        }

        // maker adds orders that use all the quote (plus fee)
        val order1 = sequencer.addOrder(market, BigDecimal("1"), BigDecimal("10.00"), maker, Order.Type.LimitBuy).let { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market.id, base = BigInteger.ZERO, quote = BigDecimal("40").inWei()),
                ),
            )
            response.ordersChangedList.first()
        }
        sequencer.addOrder(market, BigDecimal("1"), BigDecimal("10.00"), maker, Order.Type.LimitBuy).also { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market.id, base = BigInteger.ZERO, quote = BigDecimal("30").inWei()),
                ),
            )
        }
        sequencer.addOrder(market, BigDecimal("1"), BigDecimal("10.00"), maker, Order.Type.LimitBuy).also { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market.id, base = BigInteger.ZERO, quote = BigDecimal("20").inWei()),
                ),
            )
        }
        sequencer.addOrder(market, BigDecimal("1"), BigDecimal("10.00"), maker, Order.Type.LimitBuy).also { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market.id, base = BigInteger.ZERO, quote = BigDecimal("10").inWei()),
                ),
            )
        }
        sequencer.addOrder(market, BigDecimal("1"), BigDecimal("10.00"), maker, Order.Type.LimitBuy).also { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                ),
            )
        }

        // check all the quote has been used
        assertEquals(
            SequencerError.ExceedsLimit,
            sequencer.addOrder(
                market,
                BigDecimal("0.001"),
                BigDecimal("10.00"),
                maker,
                Order.Type.LimitBuy,
            ).error,
        )

        // decrease fee rate to zero
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 100.0, taker = 100.0))

        // quote consumption should be released using the original order's fee rate releasing 10 (10 order + 0 fee)
        sequencer.cancelOrder(market, order1.guid, maker).also { response ->
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market.id, base = BigInteger.ZERO, quote = BigDecimal("10").inWei()),
                ),
            )
        }

        // now withdraw
        sequencer.withdrawal(maker, market.quoteAsset, BigDecimal("11")).also { response ->
            assertEquals(mockClock.currentTimeMillis(), response.createdAt)

            // since only consumption '10' order was released, withdrawal of 11 should lead to auto-reduce
            assertEquals(1, response.ordersChangedList.size)
            assertEquals(OrderDisposition.AutoReduced, response.ordersChangedList.first().disposition)

            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker, market.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                ),
            )
        }
    }

    @Test
    fun `test failed withdrawals`() {
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))

        val user = generateUser()
        val asset = SequencerClient.Asset("ETH", decimals = 18)
        val amount = BigDecimal("0.2")

        // do a deposit
        sequencer.deposit(user, asset, amount)

        // withdraw half
        sequencer.withdrawal(user, asset, BigDecimal("0.1"))

        // withdraw other half
        sequencer.withdrawal(user, asset, BigDecimal("0.1"))

        // fail the 2 withdrawals
        sequencer.failedWithdrawals(user, asset, listOf(BigDecimal("0.1"), BigDecimal("0.1")))

        // should still be able to withdraw full amount since we rolled back the 2 halves
        sequencer.withdrawal(user, asset, amount)
    }

    @Test
    fun `Test failed settlements`() {
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))

        val market = sequencer.createMarket(MarketId("BTC8/ETH8"))

        val maker = generateUser()
        val taker = generateUser()

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
            assertEquals(mockClock.currentTimeMillis(), response.createdAt)
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
                        price = BigDecimal("17.55"),
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
        sequencer.withdrawal(maker, market.baseAsset, BigDecimal.ZERO, makerBaseBalance)
        sequencer.withdrawal(maker, market.quoteAsset, BigDecimal.ZERO, makerQuoteBalance)
        sequencer.withdrawal(taker, market.baseAsset, BigDecimal.ZERO, BigDecimal("10"))
        sequencer.withdrawal(taker, market.quoteAsset, BigDecimal.ZERO, BigDecimal("10"))
    }

    @Test
    fun `Test autoreduce on failed settlements`() {
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))

        val market = sequencer.createMarket(MarketId("BTC9/ETH9"))

        val maker = generateUser()
        val taker = generateUser()

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
            assertEquals(mockClock.currentTimeMillis(), response.createdAt)
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
                        price = BigDecimal("17.55"),
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
            assertEquals(mockClock.currentTimeMillis(), response.createdAt)
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
        sequencer.withdrawal(maker, market.baseAsset, BigDecimal.ZERO, makerBaseBalance)
        sequencer.withdrawal(maker, market.quoteAsset, BigDecimal.ZERO, makerQuoteBalance)
        sequencer.withdrawal(taker, market.baseAsset, BigDecimal.ZERO, BigDecimal("1"))
        sequencer.withdrawal(taker, market.quoteAsset, BigDecimal.ZERO, BigDecimal("40"))
    }

    @Test
    fun `Test failed settlements - balances can go negative`() {
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))

        val market = sequencer.createMarket(MarketId("BTC10/ETH10"))

        val maker = generateUser()
        val taker = generateUser()

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
            assertEquals(mockClock.currentTimeMillis(), response.createdAt)
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
                        price = BigDecimal("17.55"),
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
        sequencer.withdrawal(maker, market.baseAsset, BigDecimal.ZERO, BigDecimal("9.5679"))
        sequencer.withdrawal(maker, market.quoteAsset, BigDecimal.ZERO, BigDecimal("17.50752145"))
        sequencer.withdrawal(taker, market.baseAsset, BigDecimal.ZERO, BigDecimal("10.43210"))
        sequencer.withdrawal(taker, market.quoteAsset, BigDecimal.ZERO, BigDecimal("2.2649779"))

        // now rollback the settlement - some balances go negative (takers base balance, and makers quote balance
        sequencer.failedSettlement(taker, maker, market, trade).also { response ->
            assertEquals(mockClock.currentTimeMillis(), response.createdAt)
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

        sequencer.withdrawal(maker, market.baseAsset, BigDecimal.ZERO, BigDecimal("0.43210"))
        sequencer.withdrawal(taker, market.quoteAsset, BigDecimal.ZERO, BigDecimal("7.7350221"))
        // no balance change for these two since they went negative
        sequencer.withdrawal(maker, market.quoteAsset, BigDecimal.ZERO, null)
        sequencer.withdrawal(taker, market.baseAsset, BigDecimal.ZERO, null)

        // now deposit to take balance positive and withdraw all to make sure adjustments are properly applied
        sequencer.deposit(maker, market.quoteAsset, BigDecimal("10"))
        sequencer.deposit(taker, market.baseAsset, BigDecimal("10"))

        sequencer.withdrawal(maker, market.quoteAsset, BigDecimal.ZERO, BigDecimal("2.49247855"))
        sequencer.withdrawal(taker, market.baseAsset, BigDecimal.ZERO, BigDecimal("9.56790000"))
    }

    @Test
    fun `Test dust gets rolled into last trade fee on market order`() {
        val sequencer = SequencerClient(mockClock)
        val market = sequencer.createMarket(MarketId("BTC21/ETH21"))
        val feeRates = FeeRates.fromPercents(maker = 1.0, taker = 2.0)
        sequencer.setFeeRates(feeRates)

        val lp1 = generateUser()
        val lp2 = generateUser()
        val tkr = generateUser()

        sequencer.deposit(lp1, market.baseAsset, BigDecimal("1"))
        sequencer.deposit(lp2, market.baseAsset, BigDecimal("1"))

        sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.1"), BigDecimal("17.500"), lp1, Order.Type.LimitSell)
        sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.1"), BigDecimal("18.000"), lp2, Order.Type.LimitSell)

        sequencer.deposit(tkr, market.quoteAsset, BigDecimal("3"))

        sequencer.addOrder(market, BigDecimal.ZERO, null, tkr, Order.Type.MarketBuy, percentage = 100).also { response ->
            assertEquals(mockClock.currentTimeMillis(), response.createdAt)
            assertEquals(3, response.ordersChangedCount)
            response.ordersChangedList[0].also {
                assertEquals(OrderDisposition.Filled, it.disposition)
                assertEquals(BigDecimal("0.16617647").toFundamentalUnits(market.baseDecimals), it.newQuantity.toBigInteger())
            }
            assertEquals(OrderDisposition.Filled, response.ordersChangedList[1].disposition)
            assertEquals(OrderDisposition.PartiallyFilled, response.ordersChangedList[2].disposition)
            // make sure entire balance used
            assertEquals(
                response.balancesChangedList.first { it.user == tkr.value && it.asset == market.quoteAsset.name }.delta.toBigInteger().negate(),
                BigDecimal("3").toFundamentalUnits(market.quoteAsset.decimals),
            )

            response.tradesCreatedList[0].also {
                assertEquals(
                    it.amount.toBigInteger(),
                    BigDecimal("0.1").toFundamentalUnits(market.baseDecimals),
                )
                assertEquals(
                    it.buyerFee.toBigInteger(),
                    notionalFee(
                        notional(it.amount.toBigInteger(), BigDecimal("17.5"), market.baseDecimals, market.quoteDecimals),
                        xyz.funkybit.sequencer.core.FeeRate(feeRates.taker.value),
                    ),
                )
            }
            // verify dust added to last trade fee
            response.tradesCreatedList[1].also {
                assertEquals(
                    it.amount.toBigInteger(),
                    BigDecimal("0.06617647").toFundamentalUnits(market.baseDecimals),
                )
                assertEquals(
                    it.buyerFee.toBigInteger(),
                    notionalFee(
                        notional(it.amount.toBigInteger(), BigDecimal("18.0"), market.baseDecimals, market.quoteDecimals),
                        xyz.funkybit.sequencer.core.FeeRate(feeRates.taker.value),
                    ) + BigDecimal("0.000000010800000000").toFundamentalUnits(market.quoteDecimals),
                )
            }
        }
    }

    @Test
    fun `Test dust not taken if taker has quote assets reserved`() {
        val sequencer = SequencerClient(mockClock)
        val market = sequencer.createMarket(MarketId("BTC22/ETH22"))
        val feeRates = FeeRates.fromPercents(maker = 1.0, taker = 2.0)
        sequencer.setFeeRates(feeRates)

        val lp1 = generateUser()
        val lp2 = generateUser()
        val tkr = generateUser()

        sequencer.deposit(lp1, market.baseAsset, BigDecimal("1"))
        sequencer.deposit(lp2, market.baseAsset, BigDecimal("1"))

        sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.1"), BigDecimal("17.500"), lp1, Order.Type.LimitSell)
        sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.1"), BigDecimal("18.000"), lp2, Order.Type.LimitSell)

        sequencer.deposit(tkr, market.quoteAsset, BigDecimal("4"))

        sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.1"), BigDecimal("17.400"), tkr, Order.Type.LimitBuy)

        sequencer.addOrder(market, BigDecimal.ZERO, null, tkr, Order.Type.MarketBuy, percentage = 100).also { response ->
            assertEquals(mockClock.currentTimeMillis(), response.createdAt)
            assertEquals(3, response.ordersChangedCount)
            response.ordersChangedList[0].also {
                assertEquals(OrderDisposition.Filled, it.disposition)
                assertEquals(BigDecimal("0.12492374").toFundamentalUnits(market.baseDecimals), it.newQuantity.toBigInteger())
            }
            assertEquals(OrderDisposition.Filled, response.ordersChangedList[1].disposition)
            assertEquals(OrderDisposition.PartiallyFilled, response.ordersChangedList[2].disposition)

            response.tradesCreatedList[0].also {
                assertEquals(
                    it.amount.toBigInteger(),
                    BigDecimal("0.1").toFundamentalUnits(market.baseDecimals),
                )
                assertEquals(
                    it.buyerFee.toBigInteger(),
                    notionalFee(
                        notional(it.amount.toBigInteger(), BigDecimal("17.5"), market.baseDecimals, market.quoteDecimals),
                        xyz.funkybit.sequencer.core.FeeRate(feeRates.taker.value),
                    ),
                )
            }
            // no dust added to last trade fee
            response.tradesCreatedList[1].also {
                assertEquals(
                    it.amount.toBigInteger(),
                    BigDecimal("0.02492374").toFundamentalUnits(market.baseDecimals),
                )
                assertEquals(
                    it.buyerFee.toBigInteger(),
                    notionalFee(
                        notional(it.amount.toBigInteger(), BigDecimal("18.0"), market.baseDecimals, market.quoteDecimals),
                        xyz.funkybit.sequencer.core.FeeRate(feeRates.taker.value),
                    ),
                )
            }
        }
    }

    @Test
    fun `Test dust not taken if market is exhausted`() {
        val sequencer = SequencerClient(mockClock)
        val market = sequencer.createMarket(MarketId("BTC23/ETH23"))
        val feeRates = FeeRates.fromPercents(maker = 1.0, taker = 2.0)
        sequencer.setFeeRates(feeRates)

        val lp1 = generateUser()
        val lp2 = generateUser()
        val tkr = generateUser()

        sequencer.deposit(lp1, market.baseAsset, BigDecimal("1"))
        sequencer.deposit(lp2, market.baseAsset, BigDecimal("1"))

        sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.1"), BigDecimal("17.500"), lp1, Order.Type.LimitSell)
        sequencer.addOrderAndVerifyAccepted(market, BigDecimal("0.01"), BigDecimal("18.000"), lp2, Order.Type.LimitSell)

        sequencer.deposit(tkr, market.quoteAsset, BigDecimal("3"))

        sequencer.addOrder(market, BigDecimal.ZERO, null, tkr, Order.Type.MarketBuy, percentage = 100).also { response ->
            assertEquals(mockClock.currentTimeMillis(), response.createdAt)
            assertEquals(3, response.ordersChangedCount)
            response.ordersChangedList[0].also {
                assertEquals(OrderDisposition.Filled, it.disposition)
                assertEquals(BigDecimal("0.11000000").toFundamentalUnits(market.baseDecimals), it.newQuantity.toBigInteger())
            }
            assertEquals(OrderDisposition.Filled, response.ordersChangedList[1].disposition)
            assertEquals(OrderDisposition.Filled, response.ordersChangedList[2].disposition)

            response.tradesCreatedList[0].also {
                assertEquals(
                    it.amount.toBigInteger(),
                    BigDecimal("0.1").toFundamentalUnits(market.baseDecimals),
                )
                assertEquals(
                    it.buyerFee.toBigInteger(),
                    notionalFee(
                        notional(it.amount.toBigInteger(), BigDecimal("17.5"), market.baseDecimals, market.quoteDecimals),
                        xyz.funkybit.sequencer.core.FeeRate(feeRates.taker.value),
                    ),
                )
            }
            // no dust added to last trade fee
            response.tradesCreatedList[1].also {
                assertEquals(
                    it.amount.toBigInteger(),
                    BigDecimal("0.01").toFundamentalUnits(market.baseDecimals),
                )
                assertEquals(
                    it.buyerFee.toBigInteger(),
                    notionalFee(
                        notional(it.amount.toBigInteger(), BigDecimal("18.0"), market.baseDecimals, market.quoteDecimals),
                        xyz.funkybit.sequencer.core.FeeRate(feeRates.taker.value),
                    ),
                )
            }
        }
    }

    private fun generateUser(): SequencerUserId =
        UserId.generate().toSequencerId()
}
