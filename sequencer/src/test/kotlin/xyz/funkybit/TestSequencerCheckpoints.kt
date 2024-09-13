package xyz.funkybit

import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.test.runTest
import net.openhft.chronicle.queue.ChronicleQueue
import net.openhft.chronicle.queue.RollCycles
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.sequencer.apps.GatewayApp
import xyz.funkybit.sequencer.apps.GatewayConfig
import xyz.funkybit.sequencer.apps.SequencerApp
import xyz.funkybit.sequencer.core.Asset
import xyz.funkybit.sequencer.core.Clock
import xyz.funkybit.sequencer.core.FeeRates
import xyz.funkybit.sequencer.core.Market
import xyz.funkybit.sequencer.core.MarketId
import xyz.funkybit.sequencer.core.OrderGuid
import xyz.funkybit.sequencer.core.SequencerState
import xyz.funkybit.sequencer.core.checkpointsQueue
import xyz.funkybit.sequencer.core.queueHome
import xyz.funkybit.sequencer.core.toAccountGuid
import xyz.funkybit.sequencer.core.toBigDecimal
import xyz.funkybit.sequencer.core.toBigInteger
import xyz.funkybit.sequencer.core.toDecimalValue
import xyz.funkybit.sequencer.core.toIntegerValue
import xyz.funkybit.sequencer.core.toMarketId
import xyz.funkybit.sequencer.proto.GatewayGrpcKt
import xyz.funkybit.sequencer.proto.Order
import xyz.funkybit.sequencer.proto.Order.Type
import xyz.funkybit.sequencer.proto.OrderDisposition
import xyz.funkybit.sequencer.proto.SequencerResponse
import xyz.funkybit.sequencer.proto.balanceBatch
import xyz.funkybit.sequencer.proto.cancelOrder
import xyz.funkybit.sequencer.proto.deposit
import xyz.funkybit.sequencer.proto.feeRates
import xyz.funkybit.sequencer.proto.market
import xyz.funkybit.sequencer.proto.marketMinFee
import xyz.funkybit.sequencer.proto.order
import xyz.funkybit.sequencer.proto.orderBatch
import xyz.funkybit.sequencer.proto.setFeeRatesRequest
import xyz.funkybit.sequencer.proto.setMarketMinFeesRequest
import xyz.funkybit.sequencer.proto.setWithdrawalFeesRequest
import xyz.funkybit.sequencer.proto.withdrawal
import xyz.funkybit.sequencer.proto.withdrawalFee
import xyz.funkybit.testutils.inSats
import xyz.funkybit.testutils.inWei
import java.lang.System.getenv
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class TestSequencerCheckpoints {
    private val isOnCI = (getenv("CI_RUN") ?: "0") == "1"
    private val currentTime = AtomicLong(System.currentTimeMillis())
    private val testDirPath = Path.of(queueHome, "test")

    private val account1 = 123456789L.toAccountGuid()
    private val wallet1 = 223456789L
    private val account2 = 555111555L.toAccountGuid()
    private val wallet2 = 655111555L
    private val btc = Asset("BTC")
    private val eth = Asset("ETH")
    private val usdc = Asset("USDC")

    private val btcEthMarketId = MarketId("BTC/ETH")
    private val btcEthMarketTickSize = "0.05".toBigDecimal()

    private val btcUsdcMarketId = MarketId("BTC/USDC")
    private val btcUsdcMarketTickSize = "1".toBigDecimal()

    @BeforeEach
    fun beforeEach() {
        testDirPath.toFile().deleteRecursively()
    }

    @Test
    fun `test checkpoints`() = runTest {
        Assumptions.assumeFalse(isOnCI)

        val inputQueue = ChronicleQueue.singleBuilder(Path.of(testDirPath.toString(), "input"))
            .rollCycle(RollCycles.MINUTELY)
            .timeProvider(currentTime::get)
            .build()

        val outputQueue = ChronicleQueue.singleBuilder(Path.of(testDirPath.toString(), "output"))
            .rollCycle(RollCycles.MINUTELY)
            .timeProvider(currentTime::get)
            .build()

        val sequencedQueue = ChronicleQueue.singleBuilder(Path.of(testDirPath.toString(), "sequenced"))
            .rollCycle(RollCycles.MINUTELY)
            .timeProvider(currentTime::get)
            .build()

        val checkpointsQueue = ChronicleQueue.singleBuilder(Path.of(testDirPath.toString(), "checkpoints"))
            .build()

        assertQueueFilesCount(inputQueue, 0)
        assertCheckpointsCount(checkpointsQueue, 0)

        val gatewayApp = GatewayApp(GatewayConfig(port = 5339), inputQueue, outputQueue, sequencedQueue)
        val sequencerApp = SequencerApp(Clock(), inputQueue, outputQueue, checkpointsQueue)

        try {
            sequencerApp.start()
            gatewayApp.start()

            val gateway = GatewayGrpcKt.GatewayCoroutineStub(
                ManagedChannelBuilder.forAddress("localhost", 5339).usePlaintext().build(),
            )

            assertTrue(
                gateway.addMarket(
                    market {
                        this.guid = UUID.randomUUID().toString()
                        this.marketId = btcEthMarketId.value
                        this.tickSize = btcEthMarketTickSize.toDecimalValue()
                        this.maxOrdersPerLevel = 1000
                        this.baseDecimals = 8
                        this.quoteDecimals = 18
                    },
                ).success,
            )

            gateway.setMarketMinFees(
                setMarketMinFeesRequest {
                    this.guid = UUID.randomUUID().toString()
                    this.marketMinFees.addAll(
                        listOf(
                            marketMinFee {
                                this.marketId = btcEthMarketId.value
                                this.minFee = BigDecimal("0.0000003").inWei().toIntegerValue()
                            },
                        ),
                    )
                },
            ).also {
                assertTrue(it.success)
                assertEquals(it.sequencerResponse.marketMinFeesSetCount, 1)
            }

            assertTrue(
                gateway.setFeeRates(
                    setFeeRatesRequest {
                        this.guid = UUID.randomUUID().toString()
                        this.feeRates = feeRates {
                            this.maker = 100
                            this.taker = 200
                        }
                    },
                ).success,
            )

            val btcWithdrawalFee = BigDecimal("0.00002").inSats().toIntegerValue()
            val ethWithdrawalFee = BigDecimal("0.001").inWei().toIntegerValue()
            gateway.setWithdrawalFees(
                setWithdrawalFeesRequest {
                    this.guid = UUID.randomUUID().toString()
                    this.withdrawalFees.addAll(
                        listOf(
                            withdrawalFee {
                                this.asset = btcEthMarketId.baseAsset().value
                                this.value = btcWithdrawalFee
                            },
                            withdrawalFee {
                                this.asset = btcEthMarketId.quoteAsset().value
                                this.value = ethWithdrawalFee
                            },
                        ),
                    )
                },
            ).also {
                assertTrue(it.success)
                assertEquals(it.sequencerResponse.withdrawalFeesSetList.size, 2)
            }

            // set balances
            assertTrue(
                gateway.applyBalanceBatch(
                    balanceBatch {
                        this.guid = UUID.randomUUID().toString()
                        this.deposits.addAll(
                            listOf(
                                deposit {
                                    this.asset = btcEthMarketId.baseAsset().value
                                    this.account = account1.value
                                    this.amount = BigDecimal("1.01").inSats().toIntegerValue()
                                },
                                deposit {
                                    this.asset = btcEthMarketId.quoteAsset().value
                                    this.account = account1.value
                                    this.amount = BigDecimal("1.02").inWei().toIntegerValue()
                                },
                                deposit {
                                    this.asset = btcEthMarketId.quoteAsset().value
                                    this.account = account2.value
                                    this.amount = BigDecimal("1.03").inWei().toIntegerValue()
                                },
                            ),
                        )
                    },
                ).success,
            )

            currentTime.addAndGet(1.seconds.inWholeMilliseconds)

            // limit sell 1
            assertTrue(
                gateway.applyOrderBatch(
                    orderBatch {
                        this.guid = UUID.randomUUID().toString()
                        this.marketId = btcEthMarketId.value
                        this.account = account1.value
                        this.wallet = wallet1
                        this.ordersToAdd.add(
                            order {
                                this.guid = Random.nextLong()
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.levelIx = "17.550".levelIx(btcEthMarketTickSize)
                                this.type = Order.Type.LimitSell
                            },
                        )
                    },
                ).success,
            )

            currentTime.addAndGet(60.seconds.inWholeMilliseconds)
            // next request will trigger a queue rollover

            // limit sell 2
            assertTrue(
                gateway.applyOrderBatch(
                    orderBatch {
                        this.guid = UUID.randomUUID().toString()
                        this.marketId = btcEthMarketId.value
                        this.account = account1.value
                        this.wallet = wallet1
                        this.ordersToAdd.add(
                            order {
                                this.guid = Random.nextLong()
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.levelIx = "17.560".levelIx(btcEthMarketTickSize)
                                this.type = Order.Type.LimitSell
                            },
                        )
                    },
                ).success,
            )

            // limit sell 3
            assertTrue(
                gateway.applyOrderBatch(
                    orderBatch {
                        this.guid = UUID.randomUUID().toString()
                        this.marketId = btcEthMarketId.value
                        this.account = account1.value
                        this.wallet = wallet1
                        this.ordersToAdd.add(
                            order {
                                this.guid = Random.nextLong()
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.levelIx = "17.570".levelIx(btcEthMarketTickSize)
                                this.type = Order.Type.LimitSell
                            },
                        )
                    },
                ).success,
            )

            // restart sequencer, it should recover from checkpoint
            sequencerApp.stop()
            sequencerApp.start()

            gateway.applyBalanceBatch(
                balanceBatch {
                    this.guid = UUID.randomUUID().toString()
                    this.withdrawals.addAll(
                        listOf(
                            withdrawal {
                                this.asset = btcEthMarketId.baseAsset().value
                                this.account = account1.value
                                this.amount = BigDecimal("0.01").inSats().toIntegerValue()
                                this.externalGuid = "guid1"
                            },
                            withdrawal {
                                this.asset = btcEthMarketId.quoteAsset().value
                                this.account = account1.value
                                this.amount = BigDecimal("0.02").inWei().toIntegerValue()
                                this.externalGuid = "guid2"
                            },
                            withdrawal {
                                this.asset = btcEthMarketId.quoteAsset().value
                                this.account = account2.value
                                this.amount = BigDecimal("0.03").inWei().toIntegerValue()
                                this.externalGuid = "guid3"
                            },
                        ),
                    )
                },
            ).also {
                assertTrue(it.success)
                assertEquals(it.sequencerResponse.withdrawalsCreatedList.size, 3)

                it.sequencerResponse.withdrawalsCreatedList[0].also { withdrawal ->
                    assertEquals("guid1", withdrawal.externalGuid)
                    assertEquals(btcWithdrawalFee, withdrawal.fee)
                }
                it.sequencerResponse.withdrawalsCreatedList[1].also { withdrawal ->
                    assertEquals("guid2", withdrawal.externalGuid)
                    assertEquals(ethWithdrawalFee, withdrawal.fee)
                }
                it.sequencerResponse.withdrawalsCreatedList[2].also { withdrawal ->
                    assertEquals("guid3", withdrawal.externalGuid)
                    assertEquals(ethWithdrawalFee, withdrawal.fee)
                }
            }

            // limit sell - too small
            gateway.applyOrderBatch(
                orderBatch {
                    this.guid = UUID.randomUUID().toString()
                    this.marketId = btcEthMarketId.value
                    this.account = account1.value
                    this.wallet = wallet1
                    this.ordersToAdd.add(
                        order {
                            this.guid = Random.nextLong()
                            this.amount = BigDecimal("0.000005").inSats().toIntegerValue()
                            this.levelIx = "17.570".levelIx(btcEthMarketTickSize)
                            this.type = Order.Type.LimitSell
                        },
                    )
                },
            ).also {
                assertTrue(it.success)
                assertEquals(1, it.sequencerResponse.ordersChangedCount)
                it.sequencerResponse.ordersChangedList[0].also { order ->
                    assertEquals(OrderDisposition.Rejected, order.disposition)
                }
            }

            // market buy, should be matched
            gateway.applyOrderBatch(
                orderBatch {
                    this.guid = UUID.randomUUID().toString()
                    this.marketId = btcEthMarketId.value
                    this.account = account2.value
                    this.wallet = wallet2
                    this.ordersToAdd.add(
                        order {
                            this.guid = Random.nextLong()
                            this.amount = BigDecimal("0.0011").inSats().toIntegerValue()
                            this.levelIx = 0
                            this.type = Order.Type.MarketBuy
                        },
                    )
                },
            ).also {
                assertTrue(it.success)
                assertEquals(4, it.sequencerResponse.ordersChangedCount)

                it.sequencerResponse.ordersChangedList[0].also { marketBuyOrder ->
                    assertEquals(OrderDisposition.Filled, marketBuyOrder.disposition)
                }

                it.sequencerResponse.ordersChangedList[1].also { limitSellOrder1 ->
                    assertEquals(OrderDisposition.Filled, limitSellOrder1.disposition)
                }

                it.sequencerResponse.ordersChangedList[2].also { limitSellOrder2 ->
                    assertEquals(OrderDisposition.Filled, limitSellOrder2.disposition)
                }

                it.sequencerResponse.ordersChangedList[3].also { limitSellOrder3 ->
                    assertEquals(OrderDisposition.PartiallyFilled, limitSellOrder3.disposition)
                }

                assertEquals(3, it.sequencerResponse.tradesCreatedCount)
                it.sequencerResponse.tradesCreatedList.forEach { trade ->
                    assertTrue(trade.buyerFee.toBigInteger() > BigInteger.ZERO)
                    assertTrue(trade.sellerFee.toBigInteger() > BigInteger.ZERO)
                }
            }

            assertQueueFilesCount(inputQueue, 2)
            assertCheckpointsCount(checkpointsQueue, 1)
            assertOutputQueueContainsNoDuplicates(outputQueue, expectedMessagesCount = 11)

            currentTime.addAndGet(60.seconds.inWholeMilliseconds)

            assertTrue(
                gateway.applyOrderBatch(
                    orderBatch {
                        this.guid = UUID.randomUUID().toString()
                        this.marketId = btcEthMarketId.value
                        this.account = account1.value
                        this.wallet = wallet1
                        this.ordersToAdd.add(
                            order {
                                this.guid = Random.nextLong()
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.levelIx = "17.550".levelIx(btcEthMarketTickSize)
                                this.type = Order.Type.LimitSell
                            },
                        )
                    },
                ).success,
            )

            assertCheckpointsCount(checkpointsQueue, 2)

            // now move the cycle without the checkpoint queue being set
            currentTime.addAndGet(60.seconds.inWholeMilliseconds)
            val sequencerCheckpointsQueue = sequencerApp.checkpointsQueue
            sequencerApp.checkpointsQueue = null
            assertTrue(
                gateway.applyOrderBatch(
                    orderBatch {
                        this.guid = UUID.randomUUID().toString()
                        this.marketId = btcEthMarketId.value
                        this.account = account1.value
                        this.wallet = wallet1
                        this.ordersToAdd.add(
                            order {
                                this.guid = Random.nextLong()
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.levelIx = "17.550".levelIx(btcEthMarketTickSize)
                                this.type = Order.Type.LimitSell
                            },
                        )
                    },
                ).success,
            )
            sequencerApp.checkpointsQueue = sequencerCheckpointsQueue
            sequencerApp.stop()
            sequencerApp.start()

            assertQueueFilesCount(inputQueue, 4)
            assertCheckpointsCount(checkpointsQueue, 2)
            assertOutputQueueContainsNoDuplicates(outputQueue, expectedMessagesCount = 13)

            assertTrue(
                gateway.applyOrderBatch(
                    orderBatch {
                        this.guid = UUID.randomUUID().toString()
                        this.marketId = btcEthMarketId.value
                        this.account = account1.value
                        this.wallet = wallet1
                        this.ordersToAdd.add(
                            order {
                                this.guid = Random.nextLong()
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.levelIx = "17.550".levelIx(btcEthMarketTickSize)
                                this.type = Order.Type.LimitSell
                            },
                        )
                    },
                ).success,
            )
        } finally {
            gatewayApp.stop()
            sequencerApp.stop()
        }
    }

    private fun assertQueueFilesCount(queue: ChronicleQueue, expectedCount: Long) {
        Files.list(Path.of(queue.fileAbsolutePath())).use { list ->
            assertEquals(
                expectedCount,
                list.filter { p ->
                    p.toString().endsWith(SingleChronicleQueue.SUFFIX)
                }.count(),
            )
        }
    }

    private fun assertCheckpointsCount(checkpointsQueue: ChronicleQueue, expectedCount: Long) {
        assertEquals(
            expectedCount,
            if (checkpointsQueue.lastIndex() == -1L) {
                0
            } else {
                (checkpointsQueue as SingleChronicleQueue).countExcerpts(checkpointsQueue.firstIndex(), checkpointsQueue.lastIndex()) + 1
            },
        )
    }

    private fun assertOutputQueueContainsNoDuplicates(outputQueue: ChronicleQueue, expectedMessagesCount: Int) {
        val processedRequestGuids = mutableListOf<Long>()
        val outputTailer = outputQueue.createTailer()
        val lastIndex = outputTailer.toEnd().index()
        outputTailer.toStart()
        while (true) {
            if (outputTailer.index() == lastIndex) {
                break
            }
            outputTailer.readingDocument().use {
                if (it.isPresent) {
                    it.wire()?.read()?.bytes { bytes ->
                        processedRequestGuids.add(
                            SequencerResponse.parseFrom(bytes.toByteArray()).sequence,
                        )
                    }
                }
            }
        }

        assertEquals(
            processedRequestGuids.distinct(),
            processedRequestGuids,
            "Output queue contains duplicate responses",
        )

        assertEquals(expectedMessagesCount, processedRequestGuids.size)
    }

    @Test
    fun `test state storing and loading - empty`() {
        verifySerialization(
            SequencerState(),
        )
    }

    @Test
    fun `test state storing and loading - balances and consumptions`() {
        verifySerialization(
            SequencerState(
                balances = mutableMapOf(
                    account1 to mutableMapOf(
                        btc to BigDecimal("1").inSats(),
                        eth to BigDecimal("2").inWei(),
                    ),
                    account2 to mutableMapOf(
                        btc to BigDecimal("3").inSats(),
                    ),
                ),
                consumed = mutableMapOf(
                    account1 to mutableMapOf(
                        btc to mutableMapOf(btcEthMarketId to BigDecimal("1").inSats()),
                        eth to mutableMapOf(btcEthMarketId to BigDecimal("2").inWei()),
                    ),
                    account2 to mutableMapOf(
                        btc to mutableMapOf(btcEthMarketId to BigDecimal("3").inSats()),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `test state storing and loading - single empty market`() {
        verifySerialization(
            SequencerState(
                feeRates = FeeRates.fromPercents(maker = 1.0, taker = 2.0),
                balances = mutableMapOf(
                    account1 to mutableMapOf(
                        btc to BigDecimal("1").inSats(),
                        eth to BigDecimal("2").inWei(),
                    ),
                    account2 to mutableMapOf(
                        btc to BigDecimal("3").inSats(),
                    ),
                ),
                markets = mutableMapOf(
                    btcEthMarketId to Market(
                        id = btcEthMarketId,
                        tickSize = BigDecimal("0.05"),
                        maxOrdersPerLevel = 1000,
                        baseDecimals = 18,
                        quoteDecimals = 18,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `test state storing and loading - market with no buy orders`() {
        verifySerialization(
            SequencerState(
                feeRates = FeeRates.fromPercents(maker = 1.0, taker = 2.0),
                balances = mutableMapOf(
                    account1 to mutableMapOf(
                        btc to BigDecimal("1").inSats(),
                        eth to BigDecimal("2").inWei(),
                    ),
                    account2 to mutableMapOf(
                        btc to BigDecimal("3").inSats(),
                    ),
                ),
                markets = mutableMapOf(
                    btcEthMarketId to Market(
                        id = btcEthMarketId,
                        tickSize = BigDecimal("0.05"),
                        maxOrdersPerLevel = 1000,
                        baseDecimals = 18,
                        quoteDecimals = 18,
                    ).also { market ->
                        listOf(
                            order {
                                this.guid = 1
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.levelIx = "17.550".levelIx(btcEthMarketTickSize)
                                this.type = Order.Type.LimitSell
                            },
                            order {
                                this.guid = 2
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.levelIx = "17.560".levelIx(btcEthMarketTickSize)
                                this.type = Order.Type.LimitSell
                            },
                            order {
                                this.guid = 3
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.levelIx = "17.600".levelIx(btcEthMarketTickSize)
                                this.type = Order.Type.LimitSell
                            },
                        ).forEach { order ->
                            market.addOrder(
                                account1.value,
                                order,
                                FeeRates.fromPercents(maker = 1.0, taker = 2.0),
                            )
                        }
                    },
                ),
            ),
        )
    }

    @Test
    fun `test state storing and loading - market with no sell orders`() {
        verifySerialization(
            SequencerState(
                feeRates = FeeRates.fromPercents(maker = 1.0, taker = 2.0),
                balances = mutableMapOf(
                    account1 to mutableMapOf(
                        btc to BigDecimal("1").inSats(),
                        eth to BigDecimal("2").inWei(),
                    ),
                    account2 to mutableMapOf(
                        btc to BigDecimal("3").inSats(),
                    ),
                ),
                markets = mutableMapOf(
                    btcEthMarketId to Market(
                        id = btcEthMarketId,
                        tickSize = BigDecimal("0.05"),
                        maxOrdersPerLevel = 1000,
                        baseDecimals = 18,
                        quoteDecimals = 18,
                    ).also { market ->
                        listOf(
                            order {
                                this.guid = 1
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.levelIx = "17.3".levelIx(btcEthMarketTickSize)
                                this.type = Order.Type.LimitBuy
                            },
                            order {
                                this.guid = 2
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.levelIx = "17.4".levelIx(btcEthMarketTickSize)
                                this.type = Order.Type.LimitBuy
                            },
                            order {
                                this.guid = 3
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.levelIx = "17.5".levelIx(btcEthMarketTickSize)
                                this.type = Order.Type.LimitBuy
                            },
                        ).forEach { order ->
                            market.addOrder(
                                account1.value,
                                order,
                                FeeRates.fromPercents(maker = 1.0, taker = 2.0),
                            )
                        }
                    },
                ),
            ),
        )
    }

    @Test
    fun `test state storing and loading - markets buy and sell orders`() {
        verifySerialization(
            SequencerState(
                feeRates = FeeRates.fromPercents(maker = 1.0, taker = 2.0),
                balances = mutableMapOf(
                    account1 to mutableMapOf(
                        btc to BigDecimal("1").inSats(),
                        eth to BigDecimal("2").inWei(),
                        usdc to BigDecimal("10000").inWei(),
                    ),
                    account2 to mutableMapOf(
                        btc to BigDecimal("3").inSats(),
                        usdc to BigDecimal("10000").inWei(),
                    ),
                ),
                markets = mutableMapOf(
                    btcEthMarketId to Market(
                        id = btcEthMarketId,
                        tickSize = BigDecimal("0.05"),
                        maxOrdersPerLevel = 1000,
                        baseDecimals = 18,
                        quoteDecimals = 18,
                    ).also { market ->
                        listOf(
                            order {
                                this.guid = 1
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.levelIx = "17.3".levelIx(btcEthMarketTickSize)
                                this.type = Order.Type.LimitBuy
                            },
                            order {
                                this.guid = 2
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.levelIx = "17.4".levelIx(btcEthMarketTickSize)
                                this.type = Order.Type.LimitBuy
                            },
                            order {
                                this.guid = 3
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.levelIx = "17.5".levelIx(btcEthMarketTickSize)
                                this.type = Order.Type.LimitBuy
                            },
                            order {
                                this.guid = 4
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.levelIx = "17.550".levelIx(btcEthMarketTickSize)
                                this.type = Order.Type.LimitSell
                            },
                            order {
                                this.guid = 5
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.levelIx = "17.560".levelIx(btcEthMarketTickSize)
                                this.type = Order.Type.LimitSell
                            },
                            order {
                                this.guid = 6
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.levelIx = "17.600".levelIx(btcEthMarketTickSize)
                                this.type = Order.Type.LimitSell
                            },
                        ).forEach { order ->
                            market.addOrder(
                                account1.value,
                                order,
                                FeeRates.fromPercents(maker = 1.0, taker = 2.0),
                            )
                        }
                    },
                    btcUsdcMarketId to Market(
                        id = btcUsdcMarketId,
                        tickSize = BigDecimal("1.00"),
                        maxOrdersPerLevel = 1000,
                        baseDecimals = 18,
                        quoteDecimals = 18,
                    ).also { market ->
                        listOf(
                            order {
                                this.guid = 1
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.levelIx = "69997".levelIx(btcUsdcMarketTickSize)
                                this.type = Order.Type.LimitBuy
                            },
                            order {
                                this.guid = 2
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.levelIx = "69998".levelIx(btcUsdcMarketTickSize)
                                this.type = Order.Type.LimitBuy
                            },
                            order {
                                this.guid = 3
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.levelIx = "69999".levelIx(btcUsdcMarketTickSize)
                                this.type = Order.Type.LimitBuy
                            },
                            order {
                                this.guid = 4
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.levelIx = "70001".levelIx(btcUsdcMarketTickSize)
                                this.type = Order.Type.LimitSell
                            },
                            order {
                                this.guid = 5
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.levelIx = "70002".levelIx(btcUsdcMarketTickSize)
                                this.type = Order.Type.LimitSell
                            },
                            order {
                                this.guid = 6
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.levelIx = "70003".levelIx(btcUsdcMarketTickSize)
                                this.type = Order.Type.LimitSell
                            },
                        ).forEach { order ->
                            market.addOrder(
                                account1.value,
                                order,
                                FeeRates.fromPercents(maker = 1.0, taker = 2.0),
                            )
                        }
                    },
                ),
            ),
        )
    }

    @Test
    fun `test state storing and loading - order level circular buffer - limit sells`() {
        `test state storing and loading - order level circular buffer`(Type.LimitSell)
    }

    @Test
    fun `test state storing and loading - order level circular buffer - limit buys`() {
        `test state storing and loading - order level circular buffer`(Type.LimitBuy)
    }

    private fun `test state storing and loading - order level circular buffer`(orderType: Type) {
        val feeRates = FeeRates.fromPercents(maker = 1.0, taker = 2.0)
        val levelIx = when (orderType) {
            Type.LimitBuy -> "17.500".levelIx(btcEthMarketTickSize)
            Type.LimitSell -> "17.550".levelIx(btcEthMarketTickSize)
            else -> throw IllegalArgumentException("$orderType not supported")
        }

        verifySerialization(
            SequencerState(
                feeRates = feeRates,
                withdrawalFees = mutableMapOf(
                    Symbol("BTC") to BigInteger.TEN,
                ),
                balances = mutableMapOf(
                    account1 to mutableMapOf(
                        btc to BigDecimal("1").inSats(),
                        eth to BigDecimal("2").inWei(),
                    ),
                    account2 to mutableMapOf(
                        btc to BigDecimal("3").inSats(),
                    ),
                ),
                markets = mutableMapOf(
                    btcEthMarketId to Market(
                        id = btcEthMarketId,
                        tickSize = BigDecimal("0.05"),
                        maxOrdersPerLevel = 1000,
                        baseDecimals = 18,
                        quoteDecimals = 18,
                    ).also { market ->
                        // fill and remove data to set level's head and tail to the position 990
                        (0..990).map {
                            order {
                                this.guid = it.toLong()
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.levelIx = levelIx
                                this.type = orderType
                            }
                        }.also { orders ->
                            market.applyOrderBatch(
                                orderBatch {
                                    guid = UUID.randomUUID().toString()
                                    marketId = btcEthMarketId.value
                                    account = account1.value
                                    wallet = wallet1
                                    ordersToAdd.addAll(orders)
                                },
                                feeRates,
                            )

                            market.applyOrderBatch(
                                orderBatch {
                                    guid = UUID.randomUUID().toString()
                                    marketId = btcEthMarketId.value
                                    account = account1.value
                                    wallet = wallet1
                                    ordersToCancel.addAll(
                                        // remove all except 1 to keep level on the book
                                        orders.take(orders.size - 1).map {
                                            cancelOrder {
                                                this.guid = it.guid
                                            }
                                        },
                                    )
                                },
                                feeRates,
                            )
                        }

                        // add 20 more orders to wrap level's buffer
                        (991..1010).map {
                            order {
                                this.guid = it.toLong()
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.levelIx = levelIx
                                this.type = orderType
                            }
                        }.forEach { order ->
                            market.addOrder(account1.value, order, feeRates)
                        }

                        // verify setup
                        val targetLevel = market.levels.get(levelIx)!!
                        assertEquals(990, targetLevel.orderHead)
                        assertEquals(11, targetLevel.orderTail)
                    },
                ),
            ),
        )
    }

    @Test
    fun `test state storing and loading - empty levels are excluded from the snapshot`() {
        val feeRates = FeeRates.fromPercents(maker = 1.0, taker = 2.0)
        verifySerialization(
            SequencerState(
                feeRates = feeRates,
                withdrawalFees = mutableMapOf(
                    Symbol("BTC") to BigInteger.TEN,
                ),
                markets = mutableMapOf(
                    btcEthMarketId to Market(
                        id = btcEthMarketId,
                        tickSize = BigDecimal("0.05"),
                        maxOrdersPerLevel = 1000,
                        baseDecimals = 18,
                        quoteDecimals = 18,
                    ).also { market ->
                        // fill and remove data to set level's head and tail to the position 990
                        (1..1000).map {
                            order {
                                this.guid = it.toLong()
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.levelIx = it
                                this.type = if (Random.nextBoolean()) Type.LimitBuy else Type.LimitSell
                            }
                        }.also { orders ->
                            market.applyOrderBatch(
                                orderBatch {
                                    guid = UUID.randomUUID().toString()
                                    marketId = btcEthMarketId.value
                                    account = account1.value
                                    wallet = wallet1
                                    ordersToAdd.addAll(orders)
                                },
                                feeRates,
                            )

                            market.applyOrderBatch(
                                orderBatch {
                                    guid = UUID.randomUUID().toString()
                                    marketId = btcEthMarketId.value
                                    account = account1.value
                                    wallet = wallet1
                                    ordersToCancel.addAll(
                                        orders.map {
                                            cancelOrder {
                                                this.guid = it.guid
                                            }
                                        },
                                    )
                                },
                                feeRates,
                            )
                        }

                        // no levels should be in the market
                        assertNull(market.levels.first())
                    },
                ),
            ),
        )
    }

    @Test
    fun `restoring from checkpoint throws on cycle mismatch`() {
        val initialState = SequencerState()
        initialState.persist(checkpointsQueue, 1)

        assertThrows<RuntimeException>("Invalid cycle in the checkpoint. Expected 2, got 1") {
            SequencerState().apply { load(checkpointsQueue, 2) }
        }
    }

    private fun verifySerialization(initialState: SequencerState) {
        verifyMarketsCheckpoints(initialState)

        initialState.persist(checkpointsQueue, 1)

        val restoredState = SequencerState().apply { load(checkpointsQueue, 1) }

        initialState.markets.values.forEach { initialStateMarket ->
            val restoredStateMarket = restoredState.markets.getValue(initialStateMarket.id)

            var initialNode = initialStateMarket.levels.first()
            var restoredNode = restoredStateMarket.levels.first()

            while (initialNode != null && restoredNode != null) {
                val initialStateLevel = initialNode
                val restoredStateLevel = restoredNode

                // Compare the level indices
                assertEquals(
                    initialStateLevel.ix,
                    restoredStateLevel.ix,
                    "Level index mismatch at initial levelIx=${initialStateLevel.ix} vs restored levelIx=${restoredStateLevel.ix}",
                )

                // Compare the orders in each level
                initialStateLevel.orders.forEachIndexed { i, initialStateOrder ->
                    assertEquals(
                        initialStateOrder,
                        restoredStateLevel.orders[i],
                        "Order mismatch at levelIx=${initialStateLevel.ix}, orderIx=$i",
                    )
                }

                // Move to the next node
                initialNode = initialNode.next()
                restoredNode = restoredNode.next()
            }

            // Ensure both iterators are fully consumed, indicating both structures are of the same size
            assert(initialNode == null && restoredNode == null) {
                "Levels in market ${initialStateMarket.id} don't match"
            }
        }

        assertEquals(initialState, restoredState)
    }

    private fun verifyMarketsCheckpoints(initialState: SequencerState) {
        initialState.markets.values.forEach { market ->
            val marketCheckpoint = market.toCheckpoint()

            assertEquals(market.id, marketCheckpoint.id.toMarketId())
            assertEquals(market.tickSize, marketCheckpoint.tickSize.toBigDecimal())
            assertEquals(market.maxOrdersPerLevel, marketCheckpoint.maxOrdersPerLevel)
            assertEquals(market.baseDecimals, marketCheckpoint.baseDecimals)
            assertEquals(market.quoteDecimals, marketCheckpoint.quoteDecimals)

            // verify checkpoint contains exact number of orders
            assertEquals(
                market.ordersByGuid.map { (_, v) -> v.guid }.toSet(),
                marketCheckpoint.levelsList.map { level ->
                    level.ordersList.map {
                        OrderGuid(it.guid)
                    }
                }.flatten().toSet(),
            )
        }
    }

    private fun String.levelIx(tickSize: BigDecimal): Int {
        return BigDecimal(this).divideToIntegralValue(tickSize).toInt()
    }
}
