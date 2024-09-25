package xyz.funkybit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import xyz.funkybit.core.model.Percentage
import xyz.funkybit.core.model.db.FeeRates
import xyz.funkybit.core.model.db.UserId
import xyz.funkybit.core.sequencer.toSequencerId
import xyz.funkybit.sequencer.core.MarketId
import xyz.funkybit.sequencer.core.toBigInteger
import xyz.funkybit.sequencer.core.toIntegerValue
import xyz.funkybit.sequencer.core.toWalletAddress
import xyz.funkybit.sequencer.proto.Order
import xyz.funkybit.sequencer.proto.OrderDisposition
import xyz.funkybit.sequencer.proto.SequencerError
import xyz.funkybit.testutils.ExpectedLimitsUpdate
import xyz.funkybit.testutils.ExpectedTrade
import xyz.funkybit.testutils.MockClock
import xyz.funkybit.testutils.SequencerClient
import xyz.funkybit.testutils.assertLimits
import xyz.funkybit.testutils.assertTrade
import xyz.funkybit.testutils.inSats
import xyz.funkybit.testutils.inUsdc
import xyz.funkybit.testutils.inWei
import xyz.funkybit.testutils.toFundamentalUnits
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.random.Random

class TestBackToBackOrders {
    private val mockClock = MockClock()

    companion object {
        @JvmStatic
        fun orderAmounts() = listOf(
            Arguments.of("0.4", "1", OrderDisposition.Filled, OrderDisposition.PartiallyFilled, "0"),
            Arguments.of("1", "0.4", OrderDisposition.PartiallyFilled, OrderDisposition.Filled, "1"),
        )
    }

    @Test
    fun `Test market base to quote`() {
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = BigDecimal("0.01"), taker = BigDecimal("0.02")))

        val market1 = sequencer.createMarket(MarketId("BTC:CHAIN1/ETH:CHAIN1"))
        val market2 = sequencer.createMarket(MarketId("BTC:CHAIN2/BTC:CHAIN1"), quoteDecimals = 8, baseDecimals = 8)
        val btcChain2 = market2.baseAsset
        val btcChain1 = market2.quoteAsset
        val ethChain1 = market1.quoteAsset

        val maker = generateUser()
        sequencer.deposit(maker, btcChain2, BigDecimal("1")).also { response ->
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker.account, market2.id, base = BigDecimal("1").inSats(), quote = BigInteger.ZERO),
                ),
            )
        }
        sequencer.deposit(maker, btcChain1, BigDecimal("1")).also { response ->
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker.account, market2.id, base = BigDecimal("1").inSats(), quote = BigDecimal("1").inSats()),
                    ExpectedLimitsUpdate(maker.account, market1.id, base = BigDecimal("1").inSats(), quote = BigInteger.ZERO),
                ),
            )
        }

        // place a limit sell
        val makerSellOrder1Guid = sequencer.addOrder(market2, BigDecimal("1"), BigDecimal("1.050"), maker, Order.Type.LimitSell).let { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker.account, market2.id, base = BigInteger.ZERO, quote = BigDecimal("1").inSats()),
                ),
            )
            response.ordersChangedList.first()
        }.guid

        // place a limit sell
        val makerSellOrder2Guid = sequencer.addOrder(market1, BigDecimal("1"), BigDecimal("18.000"), maker, Order.Type.LimitSell).let { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker.account, market1.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                ),
            )
            response.ordersChangedList.first()
        }.guid

        val taker = generateUser()
        sequencer.deposit(taker, ethChain1, BigDecimal("10")).also { response ->
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(taker.account, market1.id, base = BigInteger.ZERO, quote = BigDecimal("10").inWei()),
                ),
            )
        }

        // swap ETH:CHAIN1 for BTC:CHAIN2
        val backToBackOrderGuid = Random.nextLong()
        sequencer.addBackToBackOrder(backToBackOrderGuid, market1, market2, BigDecimal("9.0").toFundamentalUnits(market1.quoteDecimals).toIntegerValue(), taker, Order.Type.MarketBuy).also { response ->
            assertEquals(3, response.ordersChangedCount)

            val takerOrder = response.ordersChangedList.first { it.guid == backToBackOrderGuid }
            assertEquals(OrderDisposition.Filled, takerOrder.disposition)

            val makerOrder1 = response.ordersChangedList.first { it.guid == makerSellOrder1Guid }
            assertEquals(OrderDisposition.PartiallyFilled, makerOrder1.disposition)
            assertEquals(BigDecimal("0.53314660").inSats(), makerOrder1.newQuantity.toBigInteger())

            val makerOrder2 = response.ordersChangedList.first { it.guid == makerSellOrder2Guid }
            assertEquals(OrderDisposition.PartiallyFilled, makerOrder2.disposition)

            assertEquals(5, response.balancesChangedCount)

            // taker balance deltas
            assertEquals(
                BigDecimal("0.46685340").toFundamentalUnits(btcChain2.decimals),
                response.balancesChangedList.first { it.account == taker.account.value && it.asset == btcChain2.name }.delta.toBigInteger(),
            )
            assertEquals(
                BigDecimal("8.9999998452").toFundamentalUnits(ethChain1.decimals).negate(),
                response.balancesChangedList.first { it.account == taker.account.value && it.asset == ethChain1.name }.delta.toBigInteger(),
            )

            // maker balance deltas
            assertEquals(
                BigDecimal("0.46685340").toFundamentalUnits(btcChain2.decimals).negate(),
                response.balancesChangedList.first { it.account == maker.account.value && it.asset == btcChain2.name }.delta.toBigInteger(),
            )
            assertEquals(
                BigDecimal("0.00490196").toFundamentalUnits(btcChain1.decimals).negate(),
                response.balancesChangedList.first { it.account == maker.account.value && it.asset == btcChain1.name }.delta.toBigInteger(),
            )
            assertEquals(
                BigDecimal("8.7352939674").toFundamentalUnits(ethChain1.decimals),
                response.balancesChangedList.first { it.account == maker.account.value && it.asset == ethChain1.name }.delta.toBigInteger(),
            )

            assertEquals(2, response.tradesCreatedCount)

            // first trade is buying the bridge asset
            response.assertTrade(
                market1,
                ExpectedTrade(
                    buyOrderGuid = backToBackOrderGuid,
                    sellOrderGuid = makerSellOrder2Guid,
                    price = BigDecimal("18.00"),
                    amount = BigDecimal("0.49019607"),
                    buyerFee = BigDecimal("0.1764705852"),
                    sellerFee = BigDecimal("0.0882352926"),
                ),
                0,
            )

            response.assertTrade(
                market2,
                ExpectedTrade(
                    buyOrderGuid = backToBackOrderGuid,
                    sellOrderGuid = makerSellOrder1Guid,
                    price = BigDecimal("1.05"),
                    amount = BigDecimal("0.46685340"),
                    buyerFee = BigDecimal.ZERO,
                    sellerFee = BigDecimal("0.00490196"),
                ),
                1,
            )

            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker.account, market1.id, base = BigDecimal("0.48529411").inSats(), quote = BigDecimal("8.7352939674").inWei()),
                    ExpectedLimitsUpdate(maker.account, market2.id, base = BigInteger.ZERO, quote = BigDecimal("0.99509804").inSats()),
                    ExpectedLimitsUpdate(taker.account, market1.id, base = BigInteger.ZERO, quote = BigDecimal("1.0000001548").inWei()),
                    ExpectedLimitsUpdate(taker.account, market2.id, base = BigDecimal("0.46685340").inSats(), quote = BigInteger.ZERO),
                ),
            )
        }

        sequencer.withdrawal(taker, btcChain2, BigDecimal.ZERO, expectedAmount = BigDecimal("0.46685340"))
        sequencer.withdrawal(taker, btcChain1, BigDecimal.ZERO, expectedAmount = null)
        sequencer.withdrawal(taker, ethChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("1.0000001548"))

        sequencer.withdrawal(maker, btcChain2, BigDecimal.ZERO, expectedAmount = BigDecimal("0.53314660"))
        sequencer.withdrawal(maker, btcChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("0.99509804"))
        sequencer.withdrawal(maker, ethChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("8.7352939674"))
    }

    @Test
    fun `Test market base to quote - 2nd leg fails`() {
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = BigDecimal("0.01"), taker = BigDecimal("0.02")))

        val market1 = sequencer.createMarket(MarketId("BTC:CHAIN1/ETH:CHAIN1"))
        val market2 = sequencer.createMarket(MarketId("BTC:CHAIN2/BTC:CHAIN1"), quoteDecimals = 8, baseDecimals = 8)
        val btcChain2 = market2.baseAsset
        val btcChain1 = market1.baseAsset
        val ethChain1 = market1.quoteAsset

        val maker = generateUser()
        sequencer.deposit(maker, btcChain1, BigDecimal("1")).also { response ->
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker.account, market1.id, base = BigDecimal("1").inSats(), quote = BigInteger.ZERO),
                    ExpectedLimitsUpdate(maker.account, market2.id, quote = BigDecimal("1").inSats(), base = BigInteger.ZERO),
                ),
            )
        }
        sequencer.deposit(maker, ethChain1, BigDecimal("18")).also { response ->
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker.account, market1.id, base = BigDecimal("1").inSats(), quote = BigDecimal("18").inWei()),
                ),
            )
        }

        // place a limit sell for first leg
        val makerSellOrderGuid = sequencer.addOrder(market1, BigDecimal("1"), BigDecimal("18.000"), maker, Order.Type.LimitSell).let { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker.account, market1.id, base = BigInteger.ZERO, quote = BigDecimal("18").inWei()),
                ),
            )
            response.ordersChangedList.first()
        }.guid

        // no maker order for second leg

        // place a limit buy for unwind of first leg
        val makerBuyOrderGuid = sequencer.addOrder(market1, BigDecimal("1"), BigDecimal("17.00"), maker, Order.Type.LimitBuy).let { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker.account, market1.id, base = BigInteger.ZERO, quote = BigDecimal("0.83").inWei()),
                ),
            )
            response.ordersChangedList.first()
        }.guid

        val taker = generateUser()
        sequencer.deposit(taker, ethChain1, BigDecimal("10")).also { response ->
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(taker.account, market1.id, base = BigInteger.ZERO, quote = BigDecimal("10").inWei()),
                ),
            )
        }

        // swap ETH:CHAIN1 for BTC:CHAIN2
        val backToBackOrderGuid = Random.nextLong()
        sequencer.addBackToBackOrder(backToBackOrderGuid, market1, market2, BigDecimal("9.0").toFundamentalUnits(market1.quoteDecimals).toIntegerValue(), taker, Order.Type.MarketBuy).also { response ->
            assertEquals(3, response.ordersChangedCount)

            val takerOrder = response.ordersChangedList.first { it.guid == backToBackOrderGuid }
            assertEquals(OrderDisposition.PartiallyFilled, takerOrder.disposition)

            val makerOrder1 = response.ordersChangedList.first { it.guid == makerSellOrderGuid }
            assertEquals(OrderDisposition.PartiallyFilled, makerOrder1.disposition)
            assertEquals(BigDecimal("0.50980393").inSats(), makerOrder1.newQuantity.toBigInteger())

            val makerOrder2 = response.ordersChangedList.first { it.guid == makerBuyOrderGuid }
            assertEquals(OrderDisposition.PartiallyFilled, makerOrder2.disposition)
            assertEquals(BigDecimal("0.50980393").inSats(), makerOrder2.newQuantity.toBigInteger())

            assertEquals(2, response.balancesChangedCount)

            // taker balance deltas
            assertEquals(
                BigDecimal("0.6666666552").toFundamentalUnits(ethChain1.decimals).negate(),
                response.balancesChangedList.first { it.account == taker.account.value && it.asset == ethChain1.name }.delta.toBigInteger(),
            )

            // maker balance deltas
            assertEquals(
                BigDecimal("0.3186274455").toFundamentalUnits(ethChain1.decimals),
                response.balancesChangedList.first { it.account == maker.account.value && it.asset == ethChain1.name }.delta.toBigInteger(),
            )

            assertEquals(2, response.tradesCreatedCount)

            // first trade is buying the bridge asset
            response.assertTrade(
                market1,
                ExpectedTrade(
                    buyOrderGuid = backToBackOrderGuid,
                    sellOrderGuid = makerSellOrderGuid,
                    price = BigDecimal("18.00"),
                    amount = BigDecimal("0.49019607"),
                    buyerFee = BigDecimal("0.1764705852"),
                    sellerFee = BigDecimal("0.0882352926"),
                ),
                0,
            )

            // second trade unwinds the first
            response.assertTrade(
                market1,
                ExpectedTrade(
                    buyOrderGuid = makerBuyOrderGuid,
                    sellOrderGuid = backToBackOrderGuid,
                    price = BigDecimal("17.00"),
                    amount = BigDecimal("0.49019607"),
                    buyerFee = BigDecimal("0.0833333319"),
                    sellerFee = BigDecimal.ZERO,
                ),
                1,
            )

            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker.account, market1.id, base = BigDecimal("0.49019607").inSats(), quote = BigDecimal("9.5652939674").inWei()),
                    ExpectedLimitsUpdate(maker.account, market2.id, base = BigInteger.ZERO, quote = BigDecimal("1").inSats()),
                    ExpectedLimitsUpdate(taker.account, market1.id, base = BigInteger.ZERO, quote = BigDecimal("9.3333333448").inWei()),
                    ExpectedLimitsUpdate(taker.account, market2.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                ),
            )
        }

        sequencer.withdrawal(taker, btcChain2, BigDecimal.ZERO, expectedAmount = null)
        sequencer.withdrawal(taker, btcChain1, BigDecimal.ZERO, expectedAmount = null)
        sequencer.withdrawal(taker, ethChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("9.3333333448"))

        sequencer.withdrawal(maker, btcChain2, BigDecimal.ZERO, expectedAmount = null)
        sequencer.withdrawal(maker, btcChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("1"))
        sequencer.withdrawal(maker, ethChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("18.3186274455"))
    }

    @Test
    fun `Test market quote to quote`() {
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = BigDecimal("0.01"), taker = BigDecimal("0.02")))

        val market1 = sequencer.createMarket(MarketId("BTC:CHAIN/USDC:CHAIN"), baseDecimals = 8, quoteDecimals = 6)
        val market2 = sequencer.createMarket(MarketId("DAI:CHAIN/USDC:CHAIN"), baseDecimals = 18, quoteDecimals = 6)
        val btc = market1.baseAsset
        val usdc = market1.quoteAsset
        val dai = market2.baseAsset

        val maker = generateUser()
        sequencer.deposit(maker, usdc, BigDecimal("50000"))
        sequencer.deposit(maker, dai, BigDecimal("40000"))

        // place a limit buy
        val makerBuyOrderGuid = sequencer.addOrder(market1, BigDecimal("1"), BigDecimal("40000"), maker, Order.Type.LimitBuy).let { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.ordersChangedList.first()
        }.guid

        // place a limit sell
        val makerSellOrderGuid = sequencer.addOrder(market2, BigDecimal("40000"), BigDecimal("0.8"), maker, Order.Type.LimitSell).let { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.ordersChangedList.first()
        }.guid

        val taker = generateUser()
        sequencer.deposit(taker, btc, BigDecimal("1"))

        // swap BTC for DAI
        val backToBackOrderGuid = Random.nextLong()
        sequencer.addBackToBackOrder(backToBackOrderGuid, market1, market2, BigDecimal("0.5").toFundamentalUnits(market1.baseDecimals).toIntegerValue(), taker, Order.Type.MarketSell).also { response ->
            assertEquals(3, response.ordersChangedCount)

            val takerOrder = response.ordersChangedList.first { it.guid == backToBackOrderGuid }
            assertEquals(OrderDisposition.Filled, takerOrder.disposition)

            val makerOrder1 = response.ordersChangedList.first { it.guid == makerBuyOrderGuid }
            assertEquals(OrderDisposition.PartiallyFilled, makerOrder1.disposition)

            val makerOrder2 = response.ordersChangedList.first { it.guid == makerSellOrderGuid }
            assertEquals(OrderDisposition.PartiallyFilled, makerOrder2.disposition)

            assertEquals(5, response.balancesChangedCount)
            // taker balance deltas
            assertEquals(
                BigDecimal("0.5").toFundamentalUnits(btc.decimals).negate(),
                response.balancesChangedList.first { it.account == taker.account.value && it.asset == btc.name }.delta.toBigInteger(),
            )
            assertEquals(
                BigDecimal("24500").toFundamentalUnits(dai.decimals),
                response.balancesChangedList.first { it.account == taker.account.value && it.asset == dai.name }.delta.toBigInteger(),
            )

            // maker balance deltas
            assertEquals(
                BigDecimal("0.5").toFundamentalUnits(btc.decimals),
                response.balancesChangedList.first { it.account == maker.account.value && it.asset == btc.name }.delta.toBigInteger(),
            )
            assertEquals(
                BigDecimal("796").toFundamentalUnits(usdc.decimals).negate(),
                response.balancesChangedList.first { it.account == maker.account.value && it.asset == usdc.name }.delta.toBigInteger(),
            )
            assertEquals(
                BigDecimal("24500").toFundamentalUnits(dai.decimals).negate(),
                response.balancesChangedList.first { it.account == maker.account.value && it.asset == dai.name }.delta.toBigInteger(),
            )

            assertEquals(2, response.tradesCreatedCount)

            // first trade is selling base for bridge asset
            response.assertTrade(
                market1,
                ExpectedTrade(
                    buyOrderGuid = makerBuyOrderGuid,
                    sellOrderGuid = backToBackOrderGuid,
                    price = BigDecimal("40000.00"),
                    amount = BigDecimal("0.5"),
                    buyerFee = BigDecimal("200.0"),
                    sellerFee = BigDecimal("400.0"),
                ),
                0,
            )

            response.assertTrade(
                market2,
                ExpectedTrade(
                    buyOrderGuid = backToBackOrderGuid,
                    sellOrderGuid = makerSellOrderGuid,
                    price = BigDecimal("0.80"),
                    amount = BigDecimal("24500.0"),
                    buyerFee = BigDecimal.ZERO,
                    sellerFee = BigDecimal("196"),
                ),
                1,
            )

            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker.account, market2.id, base = BigDecimal.ZERO.inSats(), quote = BigDecimal("49204.0").inUsdc()),
                    ExpectedLimitsUpdate(maker.account, market1.id, base = BigDecimal("0.5").inSats(), quote = BigDecimal("29004.000000").inUsdc()),
                    ExpectedLimitsUpdate(taker.account, market2.id, base = BigDecimal("24500.0").inWei(), quote = BigInteger.ZERO),
                    ExpectedLimitsUpdate(taker.account, market1.id, base = BigDecimal("0.5").inSats(), quote = BigInteger.ZERO),
                ),
            )
        }

        sequencer.withdrawal(taker, btc, BigDecimal.ZERO, expectedAmount = BigDecimal("0.5"))
        sequencer.withdrawal(taker, usdc, BigDecimal.ZERO, expectedAmount = null)
        sequencer.withdrawal(taker, dai, BigDecimal.ZERO, expectedAmount = BigDecimal("24500.0"))

        sequencer.withdrawal(maker, btc, BigDecimal.ZERO, expectedAmount = BigDecimal("0.5"))
        sequencer.withdrawal(maker, usdc, BigDecimal.ZERO, expectedAmount = BigDecimal("49204.0"))
        sequencer.withdrawal(maker, dai, BigDecimal.ZERO, expectedAmount = BigDecimal("15500.0"))
    }

    @Test
    fun `Test market quote to quote - 2nd leg fails`() {
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = BigDecimal("0.01"), taker = BigDecimal("0.02")))

        val market1 = sequencer.createMarket(MarketId("BTC:CHAIN/USDC:CHAIN"), baseDecimals = 8, quoteDecimals = 6)
        val market2 = sequencer.createMarket(MarketId("DAI:CHAIN/USDC:CHAIN"), baseDecimals = 18, quoteDecimals = 6)
        val btc = market1.baseAsset
        val usdc = market1.quoteAsset

        val maker = generateUser()
        sequencer.deposit(maker, usdc, BigDecimal("50000"))
        sequencer.deposit(maker, btc, BigDecimal("1"))

        // place a limit buy for first leg
        val makerBuyOrderGuid = sequencer.addOrder(market1, BigDecimal("1"), BigDecimal("40000"), maker, Order.Type.LimitBuy).let { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.ordersChangedList.first()
        }.guid

        // no order for second leg
        // place a limit sell for unwind
        val makerSellOrderGuid = sequencer.addOrder(market1, BigDecimal("1"), BigDecimal("41000"), maker, Order.Type.LimitSell).let { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.ordersChangedList.first()
        }.guid

        val taker = generateUser()
        sequencer.deposit(taker, btc, BigDecimal("1"))

        // swap BTC for DAI
        val backToBackOrderGuid = Random.nextLong()
        sequencer.addBackToBackOrder(backToBackOrderGuid, market1, market2, BigDecimal("0.5").toFundamentalUnits(market1.baseDecimals).toIntegerValue(), taker, Order.Type.MarketSell).also { response ->
            assertEquals(3, response.ordersChangedCount)

            val takerOrder = response.ordersChangedList.first { it.guid == backToBackOrderGuid }
            assertEquals(OrderDisposition.PartiallyFilled, takerOrder.disposition)

            val makerOrder1 = response.ordersChangedList.first { it.guid == makerBuyOrderGuid }
            assertEquals(OrderDisposition.PartiallyFilled, makerOrder1.disposition)

            val makerOrder2 = response.ordersChangedList.first { it.guid == makerSellOrderGuid }
            assertEquals(OrderDisposition.PartiallyFilled, makerOrder2.disposition)

            assertEquals(4, response.balancesChangedCount)
            // taker balance deltas
            assertEquals(
                BigDecimal("0.02195122").toFundamentalUnits(btc.decimals).negate(),
                response.balancesChangedList.first { it.account == taker.account.value && it.asset == btc.name }.delta.toBigInteger(),
            )

            // maker balance deltas
            assertEquals(
                BigDecimal("0.02195122").toFundamentalUnits(btc.decimals),
                response.balancesChangedList.first { it.account == maker.account.value && it.asset == btc.name }.delta.toBigInteger(),
            )
            assertEquals(
                BigDecimal("796.000019").toFundamentalUnits(usdc.decimals).negate(),
                response.balancesChangedList.first { it.account == maker.account.value && it.asset == usdc.name }.delta.toBigInteger(),
            )

            assertEquals(2, response.tradesCreatedCount)

            // first trade is selling base for bridge asset
            response.assertTrade(
                market1,
                ExpectedTrade(
                    buyOrderGuid = makerBuyOrderGuid,
                    sellOrderGuid = backToBackOrderGuid,
                    price = BigDecimal("40000.00"),
                    amount = BigDecimal("0.5"),
                    buyerFee = BigDecimal("200.0"),
                    sellerFee = BigDecimal("400.0"),
                ),
                0,
            )

            response.assertTrade(
                market1,
                ExpectedTrade(
                    buyOrderGuid = backToBackOrderGuid,
                    sellOrderGuid = makerSellOrderGuid,
                    price = BigDecimal("41000.00"),
                    amount = BigDecimal("0.47804878"),
                    buyerFee = BigDecimal.ZERO,
                    sellerFee = BigDecimal("195.999999"),
                ),
                1,
            )

            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker.account, market2.id, base = BigDecimal.ZERO.inSats(), quote = BigDecimal("49203.999981").inUsdc()),
                    ExpectedLimitsUpdate(maker.account, market1.id, base = BigDecimal("0.5").inSats(), quote = BigDecimal("29003.999981").inUsdc()),
                    ExpectedLimitsUpdate(taker.account, market2.id, base = BigInteger.ZERO, quote = BigInteger("20")),
                    ExpectedLimitsUpdate(taker.account, market1.id, base = BigDecimal("0.97804878").inSats(), quote = BigInteger("20")),
                ),
            )
        }

        sequencer.withdrawal(taker, btc, BigDecimal.ZERO, expectedAmount = BigDecimal("0.97804878"))
        sequencer.withdrawal(taker, usdc, BigDecimal.ZERO, expectedAmount = BigDecimal("0.000020"))

        sequencer.withdrawal(maker, btc, BigDecimal.ZERO, expectedAmount = BigDecimal("1.02195122"))
        sequencer.withdrawal(maker, usdc, BigDecimal.ZERO, expectedAmount = BigDecimal("49203.999981"))
    }

    @Test
    fun `Test market base to base`() {
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = BigDecimal("0.01"), taker = BigDecimal("0.02")))

        val market1 = sequencer.createMarket(MarketId("USDC:CHAIN/BTC:CHAIN"), baseDecimals = 6, quoteDecimals = 8, tickSize = BigDecimal("0.000001"))
        val market2 = sequencer.createMarket(MarketId("USDC:CHAIN/DAI:CHAIN"), baseDecimals = 6, quoteDecimals = 18)
        val btc = market1.quoteAsset
        val usdc = market1.baseAsset
        val dai = market2.quoteAsset

        val maker = generateUser()
        sequencer.deposit(maker, usdc, BigDecimal("100000"))
        sequencer.deposit(maker, dai, BigDecimal("40000"))

        // place a limit sell
        val makerSellOrderGuid = sequencer.addOrder(market1, BigDecimal("50000.0"), BigDecimal("0.000016"), maker, Order.Type.LimitSell).let { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.ordersChangedList.first()
        }.guid

        // place a limit buy
        val makerBuyOrderGuid = sequencer.addOrder(market2, BigDecimal("40000.0"), BigDecimal("0.8"), maker, Order.Type.LimitBuy).let { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.ordersChangedList.first()
        }.guid

        val taker = generateUser()
        sequencer.deposit(taker, btc, BigDecimal("1"))

        // swap BTC for DAI
        val backToBackOrderGuid = Random.nextLong()
        sequencer.addBackToBackOrder(backToBackOrderGuid, market1, market2, BigDecimal("0.5").toFundamentalUnits(market1.quoteDecimals).toIntegerValue(), taker, Order.Type.MarketSell).also { response ->
            assertEquals(3, response.ordersChangedCount)

            val takerOrder = response.ordersChangedList.first { it.guid == backToBackOrderGuid }
            assertEquals(OrderDisposition.Filled, takerOrder.disposition)

            val makerOrder1 = response.ordersChangedList.first { it.guid == makerSellOrderGuid }
            assertEquals(OrderDisposition.PartiallyFilled, makerOrder1.disposition)

            val makerOrder2 = response.ordersChangedList.first { it.guid == makerBuyOrderGuid }
            assertEquals(OrderDisposition.PartiallyFilled, makerOrder2.disposition)

            assertEquals(4, response.balancesChangedCount)
            // taker balance deltas
            assertEquals(
                BigDecimal("0.5").toFundamentalUnits(btc.decimals).negate(),
                response.balancesChangedList.first { it.account == taker.account.value && it.asset == btc.name }.delta.toBigInteger(),
            )
            assertEquals(
                BigDecimal("24509.804").toFundamentalUnits(dai.decimals),
                response.balancesChangedList.first { it.account == taker.account.value && it.asset == dai.name }.delta.toBigInteger(),
            )

            // maker balance deltas
            assertEquals(
                BigDecimal("0.48529412").toFundamentalUnits(btc.decimals),
                response.balancesChangedList.first { it.account == maker.account.value && it.asset == btc.name }.delta.toBigInteger(),
            )
            assertEquals(
                BigDecimal("24754.90204").toFundamentalUnits(dai.decimals).negate(),
                response.balancesChangedList.first { it.account == maker.account.value && it.asset == dai.name }.delta.toBigInteger(),
            )

            assertEquals(2, response.tradesCreatedCount)

            // first trade is selling base for bridge asset
            response.assertTrade(
                market1,
                ExpectedTrade(
                    buyOrderGuid = backToBackOrderGuid,
                    sellOrderGuid = makerSellOrderGuid,
                    price = BigDecimal("0.000016"),
                    amount = BigDecimal("30637.255"),
                    buyerFee = BigDecimal("0.00980392"),
                    sellerFee = BigDecimal("0.00490196"),
                ),
                0,
            )

            response.assertTrade(
                market2,
                ExpectedTrade(
                    buyOrderGuid = makerBuyOrderGuid,
                    sellOrderGuid = backToBackOrderGuid,
                    price = BigDecimal("0.80"),
                    amount = BigDecimal("30637.255"),
                    buyerFee = BigDecimal("245.09804"),
                    sellerFee = BigDecimal.ZERO,
                ),
                1,
            )

            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker.account, market2.id, base = BigDecimal("100000.0").inUsdc(), quote = BigDecimal("7680.0").inWei()),
                    ExpectedLimitsUpdate(maker.account, market1.id, base = BigDecimal("80637.255").inUsdc(), quote = BigDecimal("0.48529412").inSats()),
                    ExpectedLimitsUpdate(taker.account, market2.id, base = BigInteger.ZERO, quote = BigDecimal("24509.804").inWei()),
                    ExpectedLimitsUpdate(taker.account, market1.id, base = BigInteger.ZERO, quote = BigDecimal("0.5").inSats()),
                ),
            )
        }

        sequencer.withdrawal(taker, btc, BigDecimal.ZERO, expectedAmount = BigDecimal("0.5"))
        sequencer.withdrawal(taker, usdc, BigDecimal.ZERO, expectedAmount = null)
        sequencer.withdrawal(taker, dai, BigDecimal.ZERO, expectedAmount = BigDecimal("24509.804"))

        sequencer.withdrawal(maker, btc, BigDecimal.ZERO, expectedAmount = BigDecimal("0.48529412"))
        sequencer.withdrawal(maker, usdc, BigDecimal.ZERO, expectedAmount = BigDecimal("100000.0"))
        sequencer.withdrawal(maker, dai, BigDecimal.ZERO, expectedAmount = BigDecimal("15245.09796"))
    }

    @Test
    fun `Test market base to quote - max swap (100 percent)`() {
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = BigDecimal("0.01"), taker = BigDecimal("0.02")))

        val market1 = sequencer.createMarket(MarketId("BTC:CHAIN1/ETH:CHAIN1"))
        val market2 = sequencer.createMarket(MarketId("BTC:CHAIN2/BTC:CHAIN1"), quoteDecimals = 8, baseDecimals = 8)
        val btcChain2 = market2.baseAsset
        val btcChain1 = market2.quoteAsset
        val ethChain1 = market1.quoteAsset

        val maker = generateUser()
        sequencer.deposit(maker, btcChain2, BigDecimal("1"))
        sequencer.deposit(maker, btcChain1, BigDecimal("1"))

        // place a limit sell
        val makerSellOrder1Guid = sequencer.addOrderAndVerifyAccepted(market2, BigDecimal("1"), BigDecimal("1.050"), maker, Order.Type.LimitSell).guid

        // place a limit sell
        val makerSellOrder2Guid = sequencer.addOrderAndVerifyAccepted(market1, BigDecimal("1"), BigDecimal("18.000"), maker, Order.Type.LimitSell).guid

        val taker = generateUser()
        sequencer.deposit(taker, ethChain1, BigDecimal("10"))

        // swap max ETH:CHAIN1 for BTC:CHAIN2
        val backToBackOrderGuid = Random.nextLong()
        sequencer.addBackToBackOrder(backToBackOrderGuid, market1, market2, BigInteger.ZERO.toIntegerValue(), taker, Order.Type.MarketBuy, Percentage.MAX_VALUE).also { response ->
            assertEquals(3, response.ordersChangedCount)

            val takerOrder = response.ordersChangedList.first { it.guid == backToBackOrderGuid }
            assertEquals(OrderDisposition.Filled, takerOrder.disposition)

            val makerOrder1 = response.ordersChangedList.first { it.guid == makerSellOrder1Guid }
            assertEquals(OrderDisposition.PartiallyFilled, makerOrder1.disposition)

            val makerOrder2 = response.ordersChangedList.first { it.guid == makerSellOrder2Guid }
            assertEquals(OrderDisposition.PartiallyFilled, makerOrder2.disposition)

            // taker balance deltas
            assertEquals(
                BigDecimal("0.51872600").toFundamentalUnits(btcChain2.decimals),
                response.balancesChangedList.first { it.account == taker.account.value && it.asset == btcChain2.name }.delta.toBigInteger(),
            )
            // 100% of quote
            assertEquals(
                BigDecimal("10.0").toFundamentalUnits(ethChain1.decimals).negate(),
                response.balancesChangedList.first { it.account == taker.account.value && it.asset == ethChain1.name }.delta.toBigInteger(),
            )

            // maker balance deltas
            assertEquals(
                BigDecimal("0.51872600").toFundamentalUnits(btcChain2.decimals).negate(),
                response.balancesChangedList.first { it.account == maker.account.value && it.asset == btcChain2.name }.delta.toBigInteger(),
            )
            // fee 0.01601948
            assertEquals(
                BigDecimal("0.00544662").toFundamentalUnits(btcChain1.decimals).negate(),
                response.balancesChangedList.first { it.account == maker.account.value && it.asset == btcChain1.name }.delta.toBigInteger(),
            )
            assertEquals(
                BigDecimal("9.705882186").toFundamentalUnits(ethChain1.decimals),
                response.balancesChangedList.first { it.account == maker.account.value && it.asset == ethChain1.name }.delta.toBigInteger(),
            )

            assertEquals(2, response.tradesCreatedCount)

            // first trade is buying the bridge asset
            response.assertTrade(
                market1,
                ExpectedTrade(
                    buyOrderGuid = backToBackOrderGuid,
                    sellOrderGuid = makerSellOrder2Guid,
                    price = BigDecimal("18.00"),
                    amount = BigDecimal("0.54466230"),
                    buyerFee = BigDecimal("0.1960786"),
                    sellerFee = BigDecimal("0.098039214"),
                ),
                0,
            )

            response.assertTrade(
                market2,
                ExpectedTrade(
                    buyOrderGuid = backToBackOrderGuid,
                    sellOrderGuid = makerSellOrder1Guid,
                    price = BigDecimal("1.05"),
                    amount = BigDecimal("0.51872600"),
                    buyerFee = BigDecimal.ZERO,
                    sellerFee = BigDecimal("0.00544662"),
                ),
                1,
            )
        }

        sequencer.withdrawal(taker, btcChain2, BigDecimal.ZERO, expectedAmount = BigDecimal("0.51872600"))
        sequencer.withdrawal(taker, btcChain1, BigDecimal.ZERO, expectedAmount = null)
        sequencer.withdrawal(taker, ethChain1, BigDecimal.ZERO, expectedAmount = null)

        sequencer.withdrawal(maker, btcChain2, BigDecimal.ZERO, expectedAmount = BigDecimal("0.481274"))
        sequencer.withdrawal(maker, btcChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("0.99455338"))
        sequencer.withdrawal(maker, ethChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("9.705882186"))
    }

    @ParameterizedTest
    @MethodSource("orderAmounts")
    fun `Test market base to quote - partial fill`(order1Amount: String, order2Amount: String, order1Disposition: OrderDisposition, order2Disposition: OrderDisposition, hasExcess: String) {
        val excessAmount = if (hasExcess == "1") "0.1446623" else "0"
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = BigDecimal("0.01"), taker = BigDecimal("0.02")))

        val market1 = sequencer.createMarket(MarketId("BTC:CHAIN1/ETH:CHAIN1"))
        val market2 = sequencer.createMarket(MarketId("BTC:CHAIN2/BTC:CHAIN1"), quoteDecimals = 8, baseDecimals = 8)
        val btcChain2 = market2.baseAsset
        val btcChain1 = market2.quoteAsset
        val ethChain1 = market1.quoteAsset

        val maker = generateUser()
        sequencer.deposit(maker, btcChain2, BigDecimal("1"))
        sequencer.deposit(maker, btcChain1, BigDecimal("1"))
        sequencer.deposit(maker, ethChain1, BigDecimal("3"))

        // place a limit sell
        val makerSellOrder1Guid = sequencer.addOrderAndVerifyAccepted(market1, BigDecimal(order1Amount), BigDecimal("18.000"), maker, Order.Type.LimitSell).guid

        // place a limit sell
        val makerSellOrder2Guid = sequencer.addOrderAndVerifyAccepted(market2, BigDecimal(order2Amount), BigDecimal("1.000"), maker, Order.Type.LimitSell).guid

        // place a limit buy to recover any leftover assets
        sequencer.addOrderAndVerifyAccepted(market1, BigDecimal("0.15"), BigDecimal("17.00"), maker, Order.Type.LimitBuy).guid

        val taker = generateUser()
        sequencer.deposit(taker, ethChain1, BigDecimal("10"))

        // swap ETH:CHAIN1 for BTC:CHAIN2
        val backToBackOrderGuid = Random.nextLong()
        sequencer.addBackToBackOrder(backToBackOrderGuid, market1, market2, BigDecimal("10.0").toFundamentalUnits(market1.quoteDecimals).toIntegerValue(), taker, Order.Type.MarketBuy).also { response ->
            assertEquals(if (excessAmount != "0") 4 else 3, response.ordersChangedCount)

            val takerOrder = response.ordersChangedList.first { it.guid == backToBackOrderGuid }
            assertEquals(OrderDisposition.PartiallyFilled, takerOrder.disposition)

            val makerOrder1 = response.ordersChangedList.first { it.guid == makerSellOrder1Guid }
            assertEquals(order1Disposition, makerOrder1.disposition)

            val makerOrder2 = response.ordersChangedList.first { it.guid == makerSellOrder2Guid }
            assertEquals(order2Disposition, makerOrder2.disposition)

            assertEquals(if (excessAmount != "0") 3 else 2, response.tradesCreatedCount)

            // first trade is buying the bridge asset
            response.assertTrade(
                market1,
                ExpectedTrade(
                    buyOrderGuid = backToBackOrderGuid,
                    sellOrderGuid = makerSellOrder1Guid,
                    price = BigDecimal("18.00"),
                    amount = BigDecimal("0.4") + BigDecimal(excessAmount),
                    buyerFee = BigDecimal("0.144") + BigDecimal(excessAmount) * BigDecimal("18.00") * BigDecimal("0.02"),
                    sellerFee = BigDecimal("0.072") + BigDecimal(excessAmount) * BigDecimal("18.00") * BigDecimal("0.01"),
                ),
                0,
            )
            response.assertTrade(
                market2,
                ExpectedTrade(
                    buyOrderGuid = backToBackOrderGuid,
                    sellOrderGuid = makerSellOrder2Guid,
                    price = BigDecimal("1.00"),
                    amount = BigDecimal("0.40000000"),
                    buyerFee = BigDecimal.ZERO,
                    sellerFee = BigDecimal("0.00400000"),
                ),
                1,
            )
        }

        sequencer.withdrawal(taker, btcChain2, BigDecimal.ZERO, expectedAmount = BigDecimal("0.4"))
        sequencer.withdrawal(taker, btcChain1, BigDecimal.ZERO, expectedAmount = null)
        sequencer.withdrawal(taker, ethChain1, BigDecimal.ZERO, expectedAmount = if (hasExcess == "0") BigDecimal("2.656") else BigDecimal(excessAmount) * BigDecimal("17.00") + BigDecimal("0.000000172"))

        sequencer.withdrawal(maker, btcChain2, BigDecimal.ZERO, expectedAmount = BigDecimal("0.6"))
        sequencer.withdrawal(maker, btcChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("0.996"))
        sequencer.withdrawal(maker, ethChain1, BigDecimal.ZERO, expectedAmount = if (hasExcess == "0") BigDecimal("10.128") else BigDecimal("10.222030495"))
    }

    @Test
    fun `Test market base to quote - errors`() {
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = BigDecimal("0.01"), taker = BigDecimal("0.02")))

        val market1 = sequencer.createMarket(MarketId("BTC:CHAIN1/ETH:CHAIN1"))
        val market2 = sequencer.createMarket(MarketId("BTC:CHAIN2/BTC:CHAIN1"), quoteDecimals = 8, baseDecimals = 8, minFee = BigDecimal("0.0001"))
        val market3 = sequencer.createMarket(MarketId("USDC:CHAIN1/ETH:CHAIN1"))
        val market4 = sequencer.createMarket(MarketId("BTC:CHAIN1/BTC:CHAIN2"))
        val btcChain2 = market2.baseAsset
        val btcChain1 = market2.quoteAsset
        val ethChain1 = market1.quoteAsset

        val maker = generateUser()
        sequencer.deposit(maker, btcChain2, BigDecimal("1"))
        sequencer.deposit(maker, btcChain1, BigDecimal("1"))

        // place a limit sell
        sequencer.addOrderAndVerifyAccepted(market2, BigDecimal("0.4"), BigDecimal("1.000"), maker, Order.Type.LimitSell).guid

        // place a limit sell
        sequencer.addOrderAndVerifyAccepted(market1, BigDecimal("1"), BigDecimal("18.000"), maker, Order.Type.LimitSell).guid

        val taker = generateUser()
        sequencer.deposit(taker, ethChain1, BigDecimal("5"))

        // place a market buy that exceeds the limit
        val backToBackOrderGuid = Random.nextLong()
        sequencer.addBackToBackOrder(backToBackOrderGuid, market1, market2, BigDecimal("10").toFundamentalUnits(market1.quoteDecimals).toIntegerValue(), taker, Order.Type.MarketBuy).also { response ->
            assertEquals(SequencerError.ExceedsLimit, response.error)
        }

        // different bridge assets
        sequencer.addBackToBackOrder(backToBackOrderGuid, market2, market3, BigDecimal("0.5").toFundamentalUnits(market2.baseDecimals).toIntegerValue(), taker, Order.Type.MarketBuy).also { response ->
            assertEquals(SequencerError.InvalidBackToBackOrder, response.error)
        }

        // base/quote the same
        sequencer.addBackToBackOrder(backToBackOrderGuid, market2, market4, BigDecimal("0.5").toFundamentalUnits(market2.baseDecimals).toIntegerValue(), taker, Order.Type.MarketBuy).also { response ->
            assertEquals(SequencerError.InvalidBackToBackOrder, response.error)
        }

        // place one where order is below min fee
        sequencer.addBackToBackOrder(backToBackOrderGuid, market1, market2, BigDecimal("0.00001").toFundamentalUnits(market2.baseDecimals).toIntegerValue(), taker, Order.Type.MarketBuy).also { response ->
            assertEquals(SequencerError.None, response.error)
            assertEquals(response.ordersChangedList[0].disposition, OrderDisposition.Rejected)
        }

        sequencer.withdrawal(taker, btcChain2, BigDecimal.ZERO, null)
        sequencer.withdrawal(taker, btcChain1, BigDecimal.ZERO, expectedAmount = null)
        sequencer.withdrawal(taker, ethChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("5"))

        sequencer.withdrawal(maker, btcChain2, BigDecimal.ZERO, expectedAmount = BigDecimal("1"))
        sequencer.withdrawal(maker, btcChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("1"))
        sequencer.withdrawal(maker, ethChain1, BigDecimal.ZERO, expectedAmount = null)
    }

    @Test
    fun `Test market quote to base`() {
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = BigDecimal("0.01"), taker = BigDecimal("0.02")))

        val market1 = sequencer.createMarket(MarketId("btcChain2/btcChain1"), quoteDecimals = 8, baseDecimals = 8)
        val market2 = sequencer.createMarket(MarketId("btcChain1/ethChain1"))
        val btcChain2 = market1.baseAsset
        val btcChain1 = market1.quoteAsset
        val ethChain1 = market2.quoteAsset

        val maker = generateUser()
        sequencer.deposit(maker, btcChain1, BigDecimal("2")).also { response ->
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker.account, market1.id, base = BigInteger.ZERO, quote = BigDecimal("2").inSats()),
                    ExpectedLimitsUpdate(maker.account, market2.id, base = BigDecimal("2").inSats(), quote = BigInteger.ZERO),
                ),
            )
        }
        sequencer.deposit(maker, ethChain1, BigDecimal("20")).also { response ->
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker.account, market2.id, base = BigDecimal("2").inSats(), quote = BigDecimal("20").inWei()),
                ),
            )
        }

        // place a limit buy
        val makerBuyOrder1Guid = sequencer.addOrder(market1, BigDecimal("1"), BigDecimal("0.950"), maker, Order.Type.LimitBuy).let { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker.account, market1.id, base = BigInteger.ZERO, quote = BigDecimal("1.0405").inSats()),
                ),
            )
            response.ordersChangedList.first()
        }.guid

        // place a limit buy
        val makerBuyOrder2Guid = sequencer.addOrder(market2, BigDecimal("1"), BigDecimal("18.000"), maker, Order.Type.LimitBuy).let { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker.account, market2.id, base = BigDecimal("2").inSats(), quote = BigDecimal("1.82").inWei()),
                ),
            )
            response.ordersChangedList.first()
        }.guid

        val taker = generateUser()
        sequencer.deposit(taker, btcChain2, BigDecimal("0.6")).also { response ->
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(taker.account, market1.id, base = BigDecimal("0.6").inSats(), quote = BigInteger.ZERO),
                ),
            )
        }

        // swap BTC:CHAIN2 for ETH:CHAIN1
        val backToBackOrderGuid = Random.nextLong()
        sequencer.addBackToBackOrder(backToBackOrderGuid, market1, market2, BigDecimal("0.5").toFundamentalUnits(market1.baseDecimals).toIntegerValue(), taker, Order.Type.MarketSell).also { response ->
            assertEquals(3, response.ordersChangedCount)

            val takerOrder = response.ordersChangedList.first { it.guid == backToBackOrderGuid }
            assertEquals(OrderDisposition.Filled, takerOrder.disposition)

            val makerOrder1 = response.ordersChangedList.first { it.guid == makerBuyOrder1Guid }
            assertEquals(OrderDisposition.PartiallyFilled, makerOrder1.disposition)

            val makerOrder2 = response.ordersChangedList.first { it.guid == makerBuyOrder2Guid }
            assertEquals(OrderDisposition.PartiallyFilled, makerOrder2.disposition)

            assertEquals(5, response.balancesChangedCount)
            // taker balance deltas
            assertEquals(
                BigDecimal("0.5").toFundamentalUnits(btcChain2.decimals).negate(),
                response.balancesChangedList.first { it.account == taker.account.value && it.asset == btcChain2.name }.delta.toBigInteger(),
            )
            assertEquals(
                BigDecimal("8.379").toFundamentalUnits(ethChain1.decimals),
                response.balancesChangedList.first { it.account == taker.account.value && it.asset == ethChain1.name }.delta.toBigInteger(),
            )

            // maker balance deltas
            assertEquals(
                BigDecimal("0.5").toFundamentalUnits(btcChain2.decimals),
                response.balancesChangedList.first { it.account == maker.account.value && it.asset == btcChain2.name }.delta.toBigInteger(),
            )
            assertEquals(
                BigDecimal("0.01425").toFundamentalUnits(btcChain1.decimals).negate(),
                response.balancesChangedList.first { it.account == maker.account.value && it.asset == btcChain1.name }.delta.toBigInteger(),
            )
            assertEquals(
                BigDecimal("8.46279").toFundamentalUnits(ethChain1.decimals).negate(),
                response.balancesChangedList.first { it.account == maker.account.value && it.asset == ethChain1.name }.delta.toBigInteger(),
            )

            assertEquals(2, response.tradesCreatedCount)

            // first trade is selling base for bridge asset
            response.assertTrade(
                market1,
                ExpectedTrade(
                    buyOrderGuid = makerBuyOrder1Guid,
                    sellOrderGuid = backToBackOrderGuid,
                    price = BigDecimal("0.95"),
                    amount = BigDecimal("0.5"),
                    buyerFee = BigDecimal("0.00475"),
                    sellerFee = BigDecimal("0.0095"),
                ),
                0,
            )

            response.assertTrade(
                market2,
                ExpectedTrade(
                    buyOrderGuid = makerBuyOrder2Guid,
                    sellOrderGuid = backToBackOrderGuid,
                    price = BigDecimal("18.00"),
                    amount = BigDecimal("0.4655"),
                    buyerFee = BigDecimal("0.08379"),
                    sellerFee = BigDecimal.ZERO,
                ),
                1,
            )

            response.assertLimits(
                listOf(
                    // maker's ETH limit of 1.82 is 11.3645 on balance minus 9.5445 locked in order2
                    ExpectedLimitsUpdate(maker.account, market2.id, base = BigDecimal("1.98575").inSats(), quote = BigDecimal("1.82").inWei()),
                    // maker's BTC limit of 1.5155 is 1.99525 on balance minus 0.47975 locked in order1
                    ExpectedLimitsUpdate(maker.account, market1.id, base = BigDecimal("0.5").inSats(), quote = BigDecimal("1.506").inSats()),
                    ExpectedLimitsUpdate(taker.account, market2.id, base = BigInteger.ZERO, quote = BigDecimal("8.379").inWei()),
                    ExpectedLimitsUpdate(taker.account, market1.id, base = BigDecimal("0.1").inSats(), quote = BigInteger.ZERO),
                ),
            )
        }

        sequencer.withdrawal(taker, btcChain2, BigDecimal.ZERO, expectedAmount = BigDecimal("0.1"))
        sequencer.withdrawal(taker, btcChain1, BigDecimal.ZERO, expectedAmount = null)
        sequencer.withdrawal(taker, ethChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("8.379"))

        sequencer.withdrawal(maker, btcChain2, BigDecimal.ZERO, expectedAmount = BigDecimal("0.5"))
        sequencer.withdrawal(maker, btcChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("1.98575"))
        sequencer.withdrawal(maker, ethChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("11.53721"))
    }

    @Test
    fun `Test market quote to base - max swap (100 percent)`() {
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = BigDecimal("0.01"), taker = BigDecimal("0.02")))

        val market1 = sequencer.createMarket(MarketId("BTC:CHAIN2/BTC:CHAIN1"), quoteDecimals = 8, baseDecimals = 8)
        val market2 = sequencer.createMarket(MarketId("BTC:CHAIN1/ETH:CHAIN1"))
        val btcChain2 = market1.baseAsset
        val btcChain1 = market1.quoteAsset
        val ethChain1 = market2.quoteAsset

        val maker = generateUser()
        sequencer.deposit(maker, btcChain1, BigDecimal("2"))
        sequencer.deposit(maker, ethChain1, BigDecimal("20"))

        // place a limit buy
        val makerBuyOrder1Guid = sequencer.addOrderAndVerifyAccepted(market1, BigDecimal("1"), BigDecimal("0.950"), maker, Order.Type.LimitBuy).guid

        // place a limit buy
        val makerBuyOrder2Guid = sequencer.addOrderAndVerifyAccepted(market2, BigDecimal("1"), BigDecimal("18.000"), maker, Order.Type.LimitBuy).guid

        val taker = generateUser()
        sequencer.deposit(taker, btcChain2, BigDecimal("0.6"))

        // swap BTC:CHAIN2 for ETH:CHAIN1
        val backToBackOrderGuid = Random.nextLong()
        sequencer.addBackToBackOrder(backToBackOrderGuid, market1, market2, BigInteger.ZERO.toIntegerValue(), taker, Order.Type.MarketSell, Percentage.MAX_VALUE).also { response ->
            assertEquals(3, response.ordersChangedCount)

            val takerOrder = response.ordersChangedList.first { it.guid == backToBackOrderGuid }
            assertEquals(OrderDisposition.Filled, takerOrder.disposition)

            val makerOrder1 = response.ordersChangedList.first { it.guid == makerBuyOrder1Guid }
            assertEquals(OrderDisposition.PartiallyFilled, makerOrder1.disposition)

            val makerOrder2 = response.ordersChangedList.first { it.guid == makerBuyOrder2Guid }
            assertEquals(OrderDisposition.PartiallyFilled, makerOrder2.disposition)

            assertEquals(5, response.balancesChangedCount)
            // taker balance deltas
            assertEquals(
                BigDecimal("0.6").toFundamentalUnits(btcChain2.decimals).negate(),
                response.balancesChangedList.first { it.account == taker.account.value && it.asset == btcChain2.name }.delta.toBigInteger(),
            )
            // 0.6 * 0.95 * 18 - 0.2052 (fee)
            assertEquals(
                BigDecimal("10.0548").toFundamentalUnits(ethChain1.decimals),
                response.balancesChangedList.first { it.account == taker.account.value && it.asset == ethChain1.name }.delta.toBigInteger(),
            )

            // maker balance deltas
            assertEquals(
                BigDecimal("0.6").toFundamentalUnits(btcChain2.decimals),
                response.balancesChangedList.first { it.account == maker.account.value && it.asset == btcChain2.name }.delta.toBigInteger(),
            )
            assertEquals(
                BigDecimal("0.0171").toFundamentalUnits(btcChain1.decimals).negate(),
                response.balancesChangedList.first { it.account == maker.account.value && it.asset == btcChain1.name }.delta.toBigInteger(),
            )
            assertEquals(
                BigDecimal("10.155348").toFundamentalUnits(ethChain1.decimals).negate(),
                response.balancesChangedList.first { it.account == maker.account.value && it.asset == ethChain1.name }.delta.toBigInteger(),
            )

            assertEquals(2, response.tradesCreatedCount)

            // first trade is selling base for bridge asset
            response.assertTrade(
                market1,
                ExpectedTrade(
                    buyOrderGuid = makerBuyOrder1Guid,
                    sellOrderGuid = backToBackOrderGuid,
                    price = BigDecimal("0.95"),
                    amount = BigDecimal("0.6"),
                    buyerFee = BigDecimal("0.0057"),
                    sellerFee = BigDecimal("0.0114"),
                ),
                0,
            )

            response.assertTrade(
                market2,
                ExpectedTrade(
                    buyOrderGuid = makerBuyOrder2Guid,
                    sellOrderGuid = backToBackOrderGuid,
                    price = BigDecimal("18.00"),
                    amount = BigDecimal("0.5586"),
                    buyerFee = BigDecimal("0.100548"),
                    sellerFee = BigDecimal.ZERO,
                ),
                1,
            )
        }

        sequencer.withdrawal(taker, btcChain2, BigDecimal.ZERO, expectedAmount = null)
        sequencer.withdrawal(taker, btcChain1, BigDecimal.ZERO, expectedAmount = null)
        sequencer.withdrawal(taker, ethChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("10.0548"))

        sequencer.withdrawal(maker, btcChain2, BigDecimal.ZERO, expectedAmount = BigDecimal("0.6"))
        sequencer.withdrawal(maker, btcChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("1.9829"))
        sequencer.withdrawal(maker, ethChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("9.844652"))
    }

    @ParameterizedTest
    @MethodSource("orderAmounts")
    fun `Test market quote to base - partial fill`(order1Amount: String, order2Amount: String, order1Disposition: OrderDisposition, order2Disposition: OrderDisposition, hasExcess: String) {
        val excessAmount = if (hasExcess == "1") "0.1" else "0"
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = BigDecimal("0.01"), taker = BigDecimal("0.02")))

        val market1 = sequencer.createMarket(MarketId("BTC:CHAIN2/BTC:CHAIN1"), quoteDecimals = 8, baseDecimals = 8)
        val market2 = sequencer.createMarket(MarketId("BTC:CHAIN1/ETH:CHAIN1"), baseDecimals = 8)
        val btcChain2 = market1.baseAsset
        val btcChain1 = market1.quoteAsset
        val ethChain1 = market2.quoteAsset

        val maker = generateUser()
        sequencer.deposit(maker, btcChain1, BigDecimal("2"))
        sequencer.deposit(maker, ethChain1, BigDecimal("20"))
        sequencer.deposit(maker, btcChain2, BigDecimal("0.1"))

        // place a limit buy
        val makerBuyOrder1Guid = sequencer.addOrderAndVerifyAccepted(market1, BigDecimal(order1Amount), BigDecimal("1.000"), maker, Order.Type.LimitBuy).guid

        // place a limit buy
        val makerBuyOrder2Guid = sequencer.addOrderAndVerifyAccepted(market2, BigDecimal(order2Amount), BigDecimal("18.000"), maker, Order.Type.LimitBuy).guid

        // place a limit sell to recover any leftover assets
        sequencer.addOrderAndVerifyAccepted(market1, BigDecimal("0.1"), BigDecimal("19.00"), maker, Order.Type.LimitSell).guid

        val taker = generateUser()
        sequencer.deposit(taker, btcChain2, BigDecimal("0.6"))

        // swap BTC:CHAIN2 for ETH:CHAIN1
        val backToBackOrderGuid = Random.nextLong()
        sequencer.addBackToBackOrder(backToBackOrderGuid, market1, market2, BigDecimal("0.5").toFundamentalUnits(market1.baseDecimals).toIntegerValue(), taker, Order.Type.MarketSell).also { response ->
            assertEquals(if (hasExcess == "1") 4 else 3, response.ordersChangedCount)

            val takerOrder = response.ordersChangedList.first { it.guid == backToBackOrderGuid }
            assertEquals(OrderDisposition.PartiallyFilled, takerOrder.disposition)

            val makerOrder1 = response.ordersChangedList.first { it.guid == makerBuyOrder1Guid }
            assertEquals(order1Disposition, makerOrder1.disposition)

            val makerOrder2 = response.ordersChangedList.first { it.guid == makerBuyOrder2Guid }
            assertEquals(order2Disposition, makerOrder2.disposition)

            assertEquals(if (hasExcess == "1") 3 else 2, response.tradesCreatedCount)

            // first trade is selling base for bridge asset
            response.assertTrade(
                market1,
                ExpectedTrade(
                    buyOrderGuid = makerBuyOrder1Guid,
                    sellOrderGuid = backToBackOrderGuid,
                    price = BigDecimal("1.00"),
                    amount = BigDecimal("0.4") + BigDecimal(excessAmount),
                    buyerFee = BigDecimal("0.004") + BigDecimal(excessAmount) * BigDecimal("0.01"),
                    sellerFee = BigDecimal("0.008") + BigDecimal(excessAmount) * BigDecimal("0.02"),
                ),
                0,
            )

            response.assertTrade(
                market2,
                ExpectedTrade(
                    buyOrderGuid = makerBuyOrder2Guid,
                    sellOrderGuid = backToBackOrderGuid,
                    price = BigDecimal("18.00"),
                    amount = if (hasExcess == "1") BigDecimal("0.4") else BigDecimal("0.392"),
                    buyerFee = if (hasExcess == "1") BigDecimal("0.072") else BigDecimal("0.07056"),
                    sellerFee = BigDecimal.ZERO,
                ),
                1,
            )
        }

        if (hasExcess == "1") {
            sequencer.withdrawal(taker, btcChain2, BigDecimal.ZERO, expectedAmount = BigDecimal("0.19"))
            sequencer.withdrawal(taker, btcChain1, BigDecimal.ZERO, expectedAmount = null)
            sequencer.withdrawal(taker, ethChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("7.2"))

            sequencer.withdrawal(maker, btcChain2, BigDecimal.ZERO, expectedAmount = BigDecimal("0.51"))
            sequencer.withdrawal(maker, btcChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("3.5879"))
            sequencer.withdrawal(maker, ethChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("12.728"))
        } else {
            sequencer.withdrawal(taker, btcChain2, BigDecimal.ZERO, expectedAmount = BigDecimal("0.2"))
            sequencer.withdrawal(taker, btcChain1, BigDecimal.ZERO, expectedAmount = null)
            sequencer.withdrawal(taker, ethChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("7.056"))

            sequencer.withdrawal(maker, btcChain2, BigDecimal.ZERO, expectedAmount = BigDecimal("0.5"))
            sequencer.withdrawal(maker, btcChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("1.988"))
            sequencer.withdrawal(maker, ethChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("12.87344"))
        }
    }

    @Test
    fun `Test market sell - errors`() {
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = BigDecimal("0.01"), taker = BigDecimal("0.02")))

        val market1 = sequencer.createMarket(MarketId("BTC:CHAIN2/BTC:CHAIN1"), quoteDecimals = 8, baseDecimals = 8, minFee = BigDecimal("0.01"))
        val market2 = sequencer.createMarket(MarketId("BTC:CHAIN1/ETH:CHAIN1"))
        val btcChain2 = market1.baseAsset
        val btcChain1 = market1.quoteAsset
        val ethChain1 = market2.quoteAsset

        val maker = generateUser()
        sequencer.deposit(maker, btcChain1, BigDecimal("2"))
        sequencer.deposit(maker, ethChain1, BigDecimal("20"))

        // place a limit buy on first market
        sequencer.addOrderAndVerifyAccepted(market1, BigDecimal("1"), BigDecimal("1.000"), maker, Order.Type.LimitBuy).guid

        // place a limit buy on 2nd market
        sequencer.addOrderAndVerifyAccepted(market2, BigDecimal("1"), BigDecimal("18.000"), maker, Order.Type.LimitBuy).guid

        val taker = generateUser()
        sequencer.deposit(taker, btcChain2, BigDecimal("0.4"))

        // swap BTC:CHAIN2 for ETH:CHAIN1
        val backToBackOrderGuid = Random.nextLong()
        sequencer.addBackToBackOrder(backToBackOrderGuid, market1, market2, BigDecimal("0.5").toFundamentalUnits(market1.baseDecimals).toIntegerValue(), taker, Order.Type.MarketSell).also { response ->
            assertEquals(SequencerError.ExceedsLimit, response.error)
        }
        // place one where order is below min fee
        sequencer.addBackToBackOrder(backToBackOrderGuid, market1, market2, BigDecimal("0.0001").toFundamentalUnits(market1.baseDecimals).toIntegerValue(), taker, Order.Type.MarketSell).also { response ->
            assertEquals(SequencerError.None, response.error)
            assertEquals(response.ordersChangedList[0].disposition, OrderDisposition.Rejected)
        }

        // verify the remaining balances for taker
        sequencer.withdrawal(taker, btcChain2, BigDecimal.ZERO, expectedAmount = BigDecimal("0.4"))
        sequencer.withdrawal(taker, btcChain1, BigDecimal.ZERO, expectedAmount = null)
        sequencer.withdrawal(taker, ethChain1, BigDecimal.ZERO, expectedAmount = null)

        sequencer.withdrawal(maker, btcChain2, BigDecimal.ZERO, expectedAmount = null)
        sequencer.withdrawal(maker, btcChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("2"))
        sequencer.withdrawal(maker, ethChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("20"))
    }

    private val rnd = Random(0)
    private fun generateUser(): SequencerClient.SequencerUser {
        return SequencerClient.SequencerUser(
            account = UserId.generate().toSequencerId(),
            wallet = rnd.nextLong().toWalletAddress(),
        )
    }
}
