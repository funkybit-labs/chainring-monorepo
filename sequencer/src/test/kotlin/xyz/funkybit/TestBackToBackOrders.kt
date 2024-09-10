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
            Arguments.of("0.4", "1", OrderDisposition.Filled, OrderDisposition.PartiallyFilled),
            Arguments.of("1", "0.4", OrderDisposition.PartiallyFilled, OrderDisposition.Filled),
        )
    }

    @Test
    fun `Test market buy`() {
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))

        val market1 = sequencer.createMarket(MarketId("BTC:CHAIN2/BTC:CHAIN1"), quoteDecimals = 8, baseDecimals = 8)
        val market2 = sequencer.createMarket(MarketId("BTC:CHAIN1/ETH:CHAIN1"))
        val btcChain2 = market1.baseAsset
        val btcChain1 = market1.quoteAsset
        val ethChain1 = market2.quoteAsset

        val maker = generateUser()
        sequencer.deposit(maker, btcChain2, BigDecimal("1")).also { response ->
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker.account, market1.id, base = BigDecimal("1").inSats(), quote = BigInteger.ZERO),
                ),
            )
        }
        sequencer.deposit(maker, btcChain1, BigDecimal("1")).also { response ->
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker.account, market1.id, base = BigDecimal("1").inSats(), quote = BigDecimal("1").inSats()),
                    ExpectedLimitsUpdate(maker.account, market2.id, base = BigDecimal("1").inSats(), quote = BigInteger.ZERO),
                ),
            )
        }

        // place a limit sell
        val makerSellOrder1Guid = sequencer.addOrder(market1, BigDecimal("1"), BigDecimal("1.050"), maker, Order.Type.LimitSell).let { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker.account, market1.id, base = BigInteger.ZERO, quote = BigDecimal("1").inSats()),
                ),
            )
            response.ordersChangedList.first()
        }.guid

        // place a limit sell
        val makerSellOrder2Guid = sequencer.addOrder(market2, BigDecimal("1"), BigDecimal("18.000"), maker, Order.Type.LimitSell).let { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(maker.account, market2.id, base = BigInteger.ZERO, quote = BigInteger.ZERO),
                ),
            )
            response.ordersChangedList.first()
        }.guid

        val taker = generateUser()
        sequencer.deposit(taker, ethChain1, BigDecimal("10")).also { response ->
            response.assertLimits(
                listOf(
                    ExpectedLimitsUpdate(taker.account, market2.id, base = BigInteger.ZERO, quote = BigDecimal("10").inWei()),
                ),
            )
        }

        // swap ETH:CHAIN1 for BTC:CHAIN2
        val backToBackOrderGuid = Random.nextLong()
        sequencer.addBackToBackOrder(backToBackOrderGuid, market1, market2, BigDecimal("0.5"), taker, Order.Type.MarketBuy).also { response ->
            assertEquals(3, response.ordersChangedCount)

            val takerOrder = response.ordersChangedList.first { it.guid == backToBackOrderGuid }
            assertEquals(OrderDisposition.Filled, takerOrder.disposition)

            val makerOrder1 = response.ordersChangedList.first { it.guid == makerSellOrder1Guid }
            assertEquals(OrderDisposition.PartiallyFilled, makerOrder1.disposition)
            assertEquals(BigDecimal("0.5").inSats(), makerOrder1.newQuantity.toBigInteger())

            val makerOrder2 = response.ordersChangedList.first { it.guid == makerSellOrder2Guid }
            assertEquals(OrderDisposition.PartiallyFilled, makerOrder2.disposition)

            assertEquals(5, response.balancesChangedCount)

            // taker balance deltas
            assertEquals(
                BigDecimal("0.5").toFundamentalUnits(btcChain2.decimals),
                response.balancesChangedList.first { it.account == taker.account.value && it.asset == btcChain2.name }.delta.toBigInteger(),
            )
            // 0.5 * 1.05 * 18 + 0.189 (fee)
            assertEquals(
                BigDecimal("9.639").toFundamentalUnits(ethChain1.decimals).negate(),
                response.balancesChangedList.first { it.account == taker.account.value && it.asset == ethChain1.name }.delta.toBigInteger(),
            )

            // maker balance deltas
            assertEquals(
                BigDecimal("0.5").toFundamentalUnits(btcChain2.decimals).negate(),
                response.balancesChangedList.first { it.account == maker.account.value && it.asset == btcChain2.name }.delta.toBigInteger(),
            )
            // fee 0.00525
            assertEquals(
                BigDecimal("0.00525").toFundamentalUnits(btcChain1.decimals).negate(),
                response.balancesChangedList.first { it.account == maker.account.value && it.asset == btcChain1.name }.delta.toBigInteger(),
            )
            // 18 * 0.525 - 0.0945
            assertEquals(
                BigDecimal("9.3555").toFundamentalUnits(ethChain1.decimals),
                response.balancesChangedList.first { it.account == maker.account.value && it.asset == ethChain1.name }.delta.toBigInteger(),
            )

            assertEquals(2, response.tradesCreatedCount)

            // first trade is buying the bridge asset
            response.assertTrade(
                market2,
                ExpectedTrade(
                    buyOrderGuid = backToBackOrderGuid,
                    sellOrderGuid = makerSellOrder2Guid,
                    price = BigDecimal("18.00"),
                    amount = BigDecimal("0.525"),
                    buyerFee = BigDecimal("0.189"),
                    sellerFee = BigDecimal("0.0945"),
                ),
                0,
            )

            response.assertTrade(
                market1,
                ExpectedTrade(
                    buyOrderGuid = backToBackOrderGuid,
                    sellOrderGuid = makerSellOrder1Guid,
                    price = BigDecimal("1.05"),
                    amount = BigDecimal("0.500"),
                    buyerFee = BigDecimal.ZERO,
                    sellerFee = BigDecimal("0.00525"),
                ),
                1,
            )

            response.assertLimits(
                listOf(
                    // maker has 0.475 BTC left allocated in the order 2 and 0.51975 BTC is unallocated
                    // 0.51975 BTC comes from selling 0.525 BTC for ETH with a fee of 0.00525 BTC
                    // and then getting 0.525 BTC back from selling BTC2 for BTC
                    // Maker's BTC balance is 1 - 0.525 - 0.00525 + 0.525 = 0.99475,
                    // limit is balance minus allocated, hence 0.99475 - 0.475 = 0.51975
                    ExpectedLimitsUpdate(maker.account, market2.id, base = BigDecimal("0.51975").inSats(), quote = BigDecimal("9.3555").inWei()),
                    ExpectedLimitsUpdate(maker.account, market1.id, base = BigInteger.ZERO, quote = BigDecimal("0.99475").inSats()),
                    ExpectedLimitsUpdate(taker.account, market2.id, base = BigInteger.ZERO, quote = BigDecimal("0.361").inWei()),
                    ExpectedLimitsUpdate(taker.account, market1.id, base = BigDecimal("0.5").inSats(), quote = BigInteger.ZERO),
                ),
            )
        }

        sequencer.withdrawal(taker, btcChain2, BigDecimal.ZERO, expectedAmount = BigDecimal("0.5"))
        sequencer.withdrawal(taker, btcChain1, BigDecimal.ZERO, expectedAmount = null)
        sequencer.withdrawal(taker, ethChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("0.361")) // 10 - 0.5 * 1.05 * 18 - 0.189 (fee)

        sequencer.withdrawal(maker, btcChain2, BigDecimal.ZERO, expectedAmount = BigDecimal("0.5"))
        sequencer.withdrawal(maker, btcChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("0.99475")) // 1 - 0.00525
        sequencer.withdrawal(maker, ethChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("9.3555")) // 18 * 0.525 - 0.0945 (fee)
    }

    @Test
    fun `Test market buy - max swap (100 percent)`() {
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))

        val market1 = sequencer.createMarket(MarketId("BTC:CHAIN2/BTC:CHAIN1"), quoteDecimals = 8, baseDecimals = 8)
        val market2 = sequencer.createMarket(MarketId("BTC:CHAIN1/ETH:CHAIN1"))
        val btcChain2 = market1.baseAsset
        val btcChain1 = market1.quoteAsset
        val ethChain1 = market2.quoteAsset

        val maker = generateUser()
        sequencer.deposit(maker, btcChain2, BigDecimal("1"))
        sequencer.deposit(maker, btcChain1, BigDecimal("1"))

        // place a limit sell
        val makerSellOrder1Guid = sequencer.addOrderAndVerifyAccepted(market1, BigDecimal("1"), BigDecimal("1.050"), maker, Order.Type.LimitSell).guid

        // place a limit sell
        val makerSellOrder2Guid = sequencer.addOrderAndVerifyAccepted(market2, BigDecimal("1"), BigDecimal("18.000"), maker, Order.Type.LimitSell).guid

        val taker = generateUser()
        sequencer.deposit(taker, ethChain1, BigDecimal("10"))

        // swap max ETH:CHAIN1 for BTC:CHAIN2
        val backToBackOrderGuid = Random.nextLong()
        sequencer.addBackToBackOrder(backToBackOrderGuid, market1, market2, BigDecimal.ZERO, taker, Order.Type.MarketBuy, Percentage.MAX_VALUE).also { response ->
            assertEquals(3, response.ordersChangedCount)

            val takerOrder = response.ordersChangedList.first { it.guid == backToBackOrderGuid }
            assertEquals(OrderDisposition.Filled, takerOrder.disposition)

            val makerOrder1 = response.ordersChangedList.first { it.guid == makerSellOrder1Guid }
            assertEquals(OrderDisposition.PartiallyFilled, makerOrder1.disposition)

            val makerOrder2 = response.ordersChangedList.first { it.guid == makerSellOrder2Guid }
            assertEquals(OrderDisposition.PartiallyFilled, makerOrder2.disposition)

            // taker balance deltas
            assertEquals(
                BigDecimal("0.518726").toFundamentalUnits(btcChain2.decimals),
                response.balancesChangedList.first { it.account == taker.account.value && it.asset == btcChain2.name }.delta.toBigInteger(),
            )
            // 100% of quote
            assertEquals(
                BigDecimal("10").toFundamentalUnits(ethChain1.decimals).negate(),
                response.balancesChangedList.first { it.account == taker.account.value && it.asset == ethChain1.name }.delta.toBigInteger(),
            )

            // maker balance deltas
            assertEquals(
                BigDecimal("0.518726").toFundamentalUnits(btcChain2.decimals).negate(),
                response.balancesChangedList.first { it.account == maker.account.value && it.asset == btcChain2.name }.delta.toBigInteger(),
            )
            // fee 0.00544662
            assertEquals(
                BigDecimal("0.00544662").toFundamentalUnits(btcChain1.decimals).negate(),
                response.balancesChangedList.first { it.account == maker.account.value && it.asset == btcChain1.name }.delta.toBigInteger(),
            )
            // 18 * 0.5446623 - 0.098039214
            assertEquals(
                BigDecimal("9.705882186").toFundamentalUnits(ethChain1.decimals),
                response.balancesChangedList.first { it.account == maker.account.value && it.asset == ethChain1.name }.delta.toBigInteger(),
            )

            assertEquals(2, response.tradesCreatedCount)

            // first trade is buying the bridge asset
            response.assertTrade(
                market2,
                ExpectedTrade(
                    buyOrderGuid = backToBackOrderGuid,
                    sellOrderGuid = makerSellOrder2Guid,
                    price = BigDecimal("18.00"),
                    amount = BigDecimal("0.5446623"),
                    buyerFee = BigDecimal("0.1960786"),
                    sellerFee = BigDecimal("0.098039214"),
                ),
                0,
            )

            response.assertTrade(
                market1,
                ExpectedTrade(
                    buyOrderGuid = backToBackOrderGuid,
                    sellOrderGuid = makerSellOrder1Guid,
                    price = BigDecimal("1.05"),
                    amount = BigDecimal("0.518726"),
                    buyerFee = BigDecimal.ZERO,
                    sellerFee = BigDecimal("0.00544662"),
                ),
                1,
            )
        }

        sequencer.withdrawal(taker, btcChain2, BigDecimal.ZERO, expectedAmount = BigDecimal("0.518726")) // 0.5446623 / 1.05
        sequencer.withdrawal(taker, btcChain1, BigDecimal.ZERO, expectedAmount = null)
        sequencer.withdrawal(taker, ethChain1, BigDecimal.ZERO, expectedAmount = null)

        sequencer.withdrawal(maker, btcChain2, BigDecimal.ZERO, expectedAmount = BigDecimal("0.481274"))
        sequencer.withdrawal(maker, btcChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("0.99455338")) // 1 - 0.00544662
        sequencer.withdrawal(maker, ethChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("9.705882186")) // 18 * 0.5446623 - 0.098039214 (fee)
    }

    @ParameterizedTest
    @MethodSource("orderAmounts")
    fun `Test market buy - partial fill`(order1Amount: String, order2Amount: String, order1Disposition: OrderDisposition, order2Disposition: OrderDisposition) {
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))

        val market1 = sequencer.createMarket(MarketId("BTC:CHAIN2/BTC:CHAIN1"), quoteDecimals = 8, baseDecimals = 8)
        val market2 = sequencer.createMarket(MarketId("BTC:CHAIN1/ETH:CHAIN1"))
        val btcChain2 = market1.baseAsset
        val btcChain1 = market1.quoteAsset
        val ethChain1 = market2.quoteAsset

        val maker = generateUser()
        sequencer.deposit(maker, btcChain2, BigDecimal("1"))
        sequencer.deposit(maker, btcChain1, BigDecimal("1"))

        // place a limit sell
        val makerSellOrder1Guid = sequencer.addOrderAndVerifyAccepted(market1, BigDecimal(order1Amount), BigDecimal("1.000"), maker, Order.Type.LimitSell).guid

        // place a limit sell
        val makerSellOrder2Guid = sequencer.addOrderAndVerifyAccepted(market2, BigDecimal(order2Amount), BigDecimal("18.000"), maker, Order.Type.LimitSell).guid

        val taker = generateUser()
        sequencer.deposit(taker, ethChain1, BigDecimal("10"))

        // swap ETH:CHAIN1 for BTC:CHAIN2
        val backToBackOrderGuid = Random.nextLong()
        sequencer.addBackToBackOrder(backToBackOrderGuid, market1, market2, BigDecimal("0.5"), taker, Order.Type.MarketBuy).also { response ->
            assertEquals(3, response.ordersChangedCount)

            val takerOrder = response.ordersChangedList.first { it.guid == backToBackOrderGuid }
            assertEquals(OrderDisposition.PartiallyFilled, takerOrder.disposition)

            val makerOrder1 = response.ordersChangedList.first { it.guid == makerSellOrder1Guid }
            assertEquals(order1Disposition, makerOrder1.disposition)

            val makerOrder2 = response.ordersChangedList.first { it.guid == makerSellOrder2Guid }
            assertEquals(order2Disposition, makerOrder2.disposition)

            assertEquals(2, response.tradesCreatedCount)

            // first trade is buying the bridge asset
            response.assertTrade(
                market2,
                ExpectedTrade(
                    buyOrderGuid = backToBackOrderGuid,
                    sellOrderGuid = makerSellOrder2Guid,
                    price = BigDecimal("18.00"),
                    amount = BigDecimal("0.4"),
                    buyerFee = BigDecimal("0.144"),
                    sellerFee = BigDecimal("0.072"),
                ),
                0,
            )

            response.assertTrade(
                market1,
                ExpectedTrade(
                    buyOrderGuid = backToBackOrderGuid,
                    sellOrderGuid = makerSellOrder1Guid,
                    price = BigDecimal("1.00"),
                    amount = BigDecimal("0.4"),
                    buyerFee = BigDecimal.ZERO,
                    sellerFee = BigDecimal("0.004"),
                ),
                1,
            )
        }

        sequencer.withdrawal(taker, btcChain2, BigDecimal.ZERO, expectedAmount = BigDecimal("0.4"))
        sequencer.withdrawal(taker, btcChain1, BigDecimal.ZERO, expectedAmount = null)
        sequencer.withdrawal(taker, ethChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("2.656")) // 10 - 0.4 * 18 - 0.144 (fee)

        sequencer.withdrawal(maker, btcChain2, BigDecimal.ZERO, expectedAmount = BigDecimal("0.6"))
        sequencer.withdrawal(maker, btcChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("0.996")) // 1 - 0.004
        sequencer.withdrawal(maker, ethChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("7.128")) // 18 * 0.4 - 0.072 (fee)
    }

    @Test
    fun `Test market buy - errors`() {
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))

        val market1 = sequencer.createMarket(MarketId("BTC:CHAIN2/BTC:CHAIN1"), quoteDecimals = 8, baseDecimals = 8)
        val market2 = sequencer.createMarket(MarketId("BTC:CHAIN1/ETH:CHAIN1"), minFee = BigDecimal("0.0001"))
        val market3 = sequencer.createMarket(MarketId("USDC:CHAIN1/ETH:CHAIN1"))
        val market4 = sequencer.createMarket(MarketId("BTC:CHAIN1/BTC:CHAIN2"))
        val btcChain2 = market1.baseAsset
        val btcChain1 = market1.quoteAsset
        val ethChain1 = market2.quoteAsset

        val maker = generateUser()
        sequencer.deposit(maker, btcChain2, BigDecimal("1"))
        sequencer.deposit(maker, btcChain1, BigDecimal("1"))

        // place a limit sell
        sequencer.addOrderAndVerifyAccepted(market1, BigDecimal("0.4"), BigDecimal("1.000"), maker, Order.Type.LimitSell).guid

        // place a limit sell
        sequencer.addOrderAndVerifyAccepted(market2, BigDecimal("1"), BigDecimal("18.000"), maker, Order.Type.LimitSell).guid

        val taker = generateUser()
        sequencer.deposit(taker, ethChain1, BigDecimal("5"))

        // place a market buy that exceeds the limit
        val backToBackOrderGuid = Random.nextLong()
        sequencer.addBackToBackOrder(backToBackOrderGuid, market1, market2, BigDecimal("0.5"), taker, Order.Type.MarketBuy).also { response ->
            assertEquals(SequencerError.ExceedsLimit, response.error)
        }

        // different bridge assets
        sequencer.addBackToBackOrder(backToBackOrderGuid, market1, market3, BigDecimal("0.5"), taker, Order.Type.MarketBuy).also { response ->
            assertEquals(SequencerError.InvalidBackToBackOrder, response.error)
        }

        // base/quote the same
        sequencer.addBackToBackOrder(backToBackOrderGuid, market1, market4, BigDecimal("0.5"), taker, Order.Type.MarketBuy).also { response ->
            assertEquals(SequencerError.InvalidBackToBackOrder, response.error)
        }

        // place one where order is below min fee
        sequencer.addBackToBackOrder(backToBackOrderGuid, market1, market2, BigDecimal("0.00001"), taker, Order.Type.MarketBuy).also { response ->
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
    fun `Test market sell`() {
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))

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
        sequencer.addBackToBackOrder(backToBackOrderGuid, market1, market2, BigDecimal("0.5"), taker, Order.Type.MarketSell).also { response ->
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
            // 0.5 * 0.95 * 18 - 0.171 (fee)
            assertEquals(
                BigDecimal("8.379").toFundamentalUnits(ethChain1.decimals),
                response.balancesChangedList.first { it.account == taker.account.value && it.asset == ethChain1.name }.delta.toBigInteger(),
            )

            // maker balance deltas
            assertEquals(
                BigDecimal("0.5").toFundamentalUnits(btcChain2.decimals),
                response.balancesChangedList.first { it.account == maker.account.value && it.asset == btcChain2.name }.delta.toBigInteger(),
            )
            // fee 0.00475
            assertEquals(
                BigDecimal("0.00475").toFundamentalUnits(btcChain1.decimals).negate(),
                response.balancesChangedList.first { it.account == maker.account.value && it.asset == btcChain1.name }.delta.toBigInteger(),
            )
            // 0.5 * 18 * 0.95 + 0.0855
            assertEquals(
                BigDecimal("8.6355").toFundamentalUnits(ethChain1.decimals).negate(),
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
                    sellerFee = BigDecimal.ZERO,
                ),
                0,
            )

            response.assertTrade(
                market2,
                ExpectedTrade(
                    buyOrderGuid = makerBuyOrder2Guid,
                    sellOrderGuid = backToBackOrderGuid,
                    price = BigDecimal("18.00"),
                    amount = BigDecimal("0.475"),
                    buyerFee = BigDecimal("0.0855"),
                    sellerFee = BigDecimal("0.171"),
                ),
                1,
            )

            response.assertLimits(
                listOf(
                    // maker's ETH limit of 1.82 is 11.3645 on balance minus 9.5445 locked in order2
                    ExpectedLimitsUpdate(maker.account, market2.id, base = BigDecimal("1.99525000").inSats(), quote = BigDecimal("1.82").inWei()),
                    // maker's BTC limit of 1.5155 is 1.99525 on balance minus 0.47975 locked in order1
                    ExpectedLimitsUpdate(maker.account, market1.id, base = BigDecimal("0.5").inSats(), quote = BigDecimal("1.5155").inSats()),
                    ExpectedLimitsUpdate(taker.account, market2.id, base = BigInteger.ZERO, quote = BigDecimal("8.379").inWei()),
                    ExpectedLimitsUpdate(taker.account, market1.id, base = BigDecimal("0.1").inSats(), quote = BigInteger.ZERO),
                ),
            )
        }

        sequencer.withdrawal(taker, btcChain2, BigDecimal.ZERO, expectedAmount = BigDecimal("0.1")) // 0.6 - 0.5
        sequencer.withdrawal(taker, btcChain1, BigDecimal.ZERO, expectedAmount = null)
        sequencer.withdrawal(taker, ethChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("8.379")) // 0.5 * 0.95 * 18 - 0.171 (fee)

        sequencer.withdrawal(maker, btcChain2, BigDecimal.ZERO, expectedAmount = BigDecimal("0.5"))
        sequencer.withdrawal(maker, btcChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("1.99525")) // 2 - 0.00475
        sequencer.withdrawal(maker, ethChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("11.3645")) // 20 - 0.5 * 18 * 0.95 - 0.0855 (fee)
    }

    @Test
    fun `Test market sell - max swap (100 percent)`() {
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))

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
        sequencer.addBackToBackOrder(backToBackOrderGuid, market1, market2, BigDecimal.ZERO, taker, Order.Type.MarketSell, Percentage.MAX_VALUE).also { response ->
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
            // fee for trade 1 was 0.0057
            assertEquals(
                BigDecimal("0.0057").toFundamentalUnits(btcChain1.decimals).negate(),
                response.balancesChangedList.first { it.account == maker.account.value && it.asset == btcChain1.name }.delta.toBigInteger(),
            )
            // 0.6 * 18 * 0.95 + 0.1026
            assertEquals(
                BigDecimal("10.3626").toFundamentalUnits(ethChain1.decimals).negate(),
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
                    sellerFee = BigDecimal.ZERO,
                ),
                0,
            )

            response.assertTrade(
                market2,
                ExpectedTrade(
                    buyOrderGuid = makerBuyOrder2Guid,
                    sellOrderGuid = backToBackOrderGuid,
                    price = BigDecimal("18.00"),
                    // 0.6 * 0.95
                    amount = BigDecimal("0.57"),
                    buyerFee = BigDecimal("0.1026"),
                    sellerFee = BigDecimal("0.2052"),
                ),
                1,
            )
        }

        sequencer.withdrawal(taker, btcChain2, BigDecimal.ZERO, expectedAmount = null) // 0.6 - 0.5
        sequencer.withdrawal(taker, btcChain1, BigDecimal.ZERO, expectedAmount = null)
        sequencer.withdrawal(taker, ethChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("10.0548")) // 0.6 * 0.95 * 18 - 0.2052 (fee)

        sequencer.withdrawal(maker, btcChain2, BigDecimal.ZERO, expectedAmount = BigDecimal("0.6"))
        sequencer.withdrawal(maker, btcChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("1.9943")) // 2 - 0.0057
        sequencer.withdrawal(maker, ethChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("9.6374")) // 20 - 0.6 * 18 * 0.95 - 0.1026 (fee)
    }

    @ParameterizedTest
    @MethodSource("orderAmounts")
    fun `Test market sell - partial fill`(order1Amount: String, order2Amount: String, order1Disposition: OrderDisposition, order2Disposition: OrderDisposition) {
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))

        val market1 = sequencer.createMarket(MarketId("BTC:CHAIN2/BTC:CHAIN1"), quoteDecimals = 8, baseDecimals = 8)
        val market2 = sequencer.createMarket(MarketId("BTC:CHAIN1/ETH:CHAIN1"))
        val btcChain2 = market1.baseAsset
        val btcChain1 = market1.quoteAsset
        val ethChain1 = market2.quoteAsset

        val maker = generateUser()
        sequencer.deposit(maker, btcChain1, BigDecimal("2"))
        sequencer.deposit(maker, ethChain1, BigDecimal("20"))

        // place a limit buy
        val makerBuyOrder1Guid = sequencer.addOrderAndVerifyAccepted(market1, BigDecimal(order1Amount), BigDecimal("1.000"), maker, Order.Type.LimitBuy).guid

        // place a limit buy
        val makerBuyOrder2Guid = sequencer.addOrderAndVerifyAccepted(market2, BigDecimal(order2Amount), BigDecimal("18.000"), maker, Order.Type.LimitBuy).guid

        val taker = generateUser()
        sequencer.deposit(taker, btcChain2, BigDecimal("0.6"))

        // swap BTC:CHAIN2 for ETH:CHAIN1
        val backToBackOrderGuid = Random.nextLong()
        sequencer.addBackToBackOrder(backToBackOrderGuid, market1, market2, BigDecimal("0.5"), taker, Order.Type.MarketSell).also { response ->
            assertEquals(3, response.ordersChangedCount)

            val takerOrder = response.ordersChangedList.first { it.guid == backToBackOrderGuid }
            assertEquals(OrderDisposition.PartiallyFilled, takerOrder.disposition)

            val makerOrder1 = response.ordersChangedList.first { it.guid == makerBuyOrder1Guid }
            assertEquals(order1Disposition, makerOrder1.disposition)

            val makerOrder2 = response.ordersChangedList.first { it.guid == makerBuyOrder2Guid }
            assertEquals(order2Disposition, makerOrder2.disposition)

            assertEquals(2, response.tradesCreatedCount)

            // first trade is selling base for bridge asset
            response.assertTrade(
                market1,
                ExpectedTrade(
                    buyOrderGuid = makerBuyOrder1Guid,
                    sellOrderGuid = backToBackOrderGuid,
                    price = BigDecimal("1.00"),
                    amount = BigDecimal("0.4"),
                    buyerFee = BigDecimal("0.004"),
                    sellerFee = BigDecimal.ZERO,
                ),
                0,
            )

            response.assertTrade(
                market2,
                ExpectedTrade(
                    buyOrderGuid = makerBuyOrder2Guid,
                    sellOrderGuid = backToBackOrderGuid,
                    price = BigDecimal("18.00"),
                    amount = BigDecimal("0.4"),
                    buyerFee = BigDecimal("0.072"),
                    sellerFee = BigDecimal("0.144"),
                ),
                1,
            )
        }

        sequencer.withdrawal(taker, btcChain2, BigDecimal.ZERO, expectedAmount = BigDecimal("0.2")) // 0.6 - 0.4
        sequencer.withdrawal(taker, btcChain1, BigDecimal.ZERO, expectedAmount = null)
        sequencer.withdrawal(taker, ethChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("7.056")) // 0.4 * 18 - 0.144 (fee)

        sequencer.withdrawal(maker, btcChain2, BigDecimal.ZERO, expectedAmount = BigDecimal("0.4"))
        sequencer.withdrawal(maker, btcChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("1.996")) // 2 - 0.004
        sequencer.withdrawal(maker, ethChain1, BigDecimal.ZERO, expectedAmount = BigDecimal("12.728")) // 20 - 0.4 * 18 * 1.0 - 0.072 (fee)
    }

    @Test
    fun `Test market sell - errors`() {
        val sequencer = SequencerClient(mockClock)
        sequencer.setFeeRates(FeeRates.fromPercents(maker = 1.0, taker = 2.0))

        val market1 = sequencer.createMarket(MarketId("BTC:CHAIN2/BTC:CHAIN1"), quoteDecimals = 8, baseDecimals = 8)
        val market2 = sequencer.createMarket(MarketId("BTC:CHAIN1/ETH:CHAIN1"), minFee = BigDecimal("0.01"))
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
        sequencer.addBackToBackOrder(backToBackOrderGuid, market1, market2, BigDecimal("0.5"), taker, Order.Type.MarketSell).also { response ->
            assertEquals(SequencerError.ExceedsLimit, response.error)
        }
        // place one where order is below min fee
        sequencer.addBackToBackOrder(backToBackOrderGuid, market1, market2, BigDecimal("0.00001"), taker, Order.Type.MarketSell).also { response ->
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
