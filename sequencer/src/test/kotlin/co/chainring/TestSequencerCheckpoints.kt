package co.chainring

import co.chainring.sequencer.apps.GatewayApp
import co.chainring.sequencer.apps.GatewayConfig
import co.chainring.sequencer.apps.SequencerApp
import co.chainring.sequencer.core.Asset
import co.chainring.sequencer.core.Market
import co.chainring.sequencer.core.MarketId
import co.chainring.sequencer.core.SequencerState
import co.chainring.sequencer.core.queueHome
import co.chainring.sequencer.core.toDecimalValue
import co.chainring.sequencer.core.toIntegerValue
import co.chainring.sequencer.core.toWalletAddress
import co.chainring.sequencer.proto.GatewayGrpcKt
import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.OrderDisposition
import co.chainring.sequencer.proto.SequencerResponse
import co.chainring.sequencer.proto.balanceBatch
import co.chainring.sequencer.proto.deposit
import co.chainring.sequencer.proto.market
import co.chainring.sequencer.proto.order
import co.chainring.sequencer.proto.orderBatch
import co.chainring.testutils.inSats
import co.chainring.testutils.inWei
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.test.runTest
import net.openhft.chronicle.queue.ChronicleQueue
import net.openhft.chronicle.queue.RollCycles
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createDirectories
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.time.Duration.Companion.seconds

class TestSequencerCheckpoints {
    private val currentTime = AtomicLong(System.currentTimeMillis())
    private val testDirPath = Path.of(queueHome, "test")
    private val checkpointsPath = Path.of(testDirPath.toString(), "checkpoints")

    @BeforeEach
    fun beforeEach() {
        testDirPath.toFile().deleteRecursively()
        checkpointsPath.createDirectories()
    }

    @Test
    fun `test checkpoints`() = runTest {
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

        assertQueueFilesCount(inputQueue, 0)
        assertCheckpointFilesCount(checkpointsPath, 0)

        val gatewayApp = GatewayApp(GatewayConfig(port = 5339), inputQueue, outputQueue, sequencedQueue)
        val sequencerApp = SequencerApp(inputQueue, outputQueue, checkpointsPath)

        try {
            sequencerApp.start()
            gatewayApp.start()

            val gateway = GatewayGrpcKt.GatewayCoroutineStub(
                ManagedChannelBuilder.forAddress("localhost", 5339).usePlaintext().build(),
            )

            val marketId = MarketId("BTC/ETH")
            val maker = 123456789L.toWalletAddress()
            val taker = 555111555L.toWalletAddress()

            assertTrue(
                gateway.addMarket(
                    market {
                        this.guid = UUID.randomUUID().toString()
                        this.marketId = marketId.value
                        this.tickSize = "0.05".toBigDecimal().toDecimalValue()
                        this.maxLevels = 1000
                        this.maxOrdersPerLevel = 1000
                        this.marketPrice = "17.525".toBigDecimal().toDecimalValue()
                        this.baseDecimals = 18
                        this.quoteDecimals = 18
                    },
                ).success,
            )

            // set balances
            assertTrue(
                gateway.applyBalanceBatch(
                    balanceBatch {
                        this.guid = UUID.randomUUID().toString()
                        this.deposits.addAll(
                            listOf(
                                deposit {
                                    this.asset = marketId.baseAsset().value
                                    this.wallet = maker.value
                                    this.amount = BigDecimal("1").inSats().toIntegerValue()
                                },
                                deposit {
                                    this.asset = marketId.quoteAsset().value
                                    this.wallet = maker.value
                                    this.amount = BigDecimal("1").inWei().toIntegerValue()
                                },
                                deposit {
                                    this.asset = marketId.quoteAsset().value
                                    this.wallet = taker.value
                                    this.amount = BigDecimal("1").inWei().toIntegerValue()
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
                        this.marketId = marketId.value
                        this.ordersToAdd.add(
                            order {
                                this.guid = Random.nextLong()
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.550").toDecimalValue()
                                this.wallet = maker.value
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
                        this.marketId = marketId.value
                        this.ordersToAdd.add(
                            order {
                                this.guid = Random.nextLong()
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.560").toDecimalValue()
                                this.wallet = maker.value
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
                        this.marketId = marketId.value
                        this.ordersToAdd.add(
                            order {
                                this.guid = Random.nextLong()
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.570").toDecimalValue()
                                this.wallet = maker.value
                                this.type = Order.Type.LimitSell
                            },
                        )
                    },
                ).success,
            )

            // restart sequencer, it should recover from checkpoint
            sequencerApp.stop()
            sequencerApp.start()

            // market buy, should be matched
            gateway.applyOrderBatch(
                orderBatch {
                    this.guid = UUID.randomUUID().toString()
                    this.marketId = marketId.value
                    this.ordersToAdd.add(
                        order {
                            this.guid = Random.nextLong()
                            this.amount = BigDecimal("0.0011").inSats().toIntegerValue()
                            this.price = BigDecimal.ZERO.toDecimalValue()
                            this.wallet = taker.value
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
            }

            assertQueueFilesCount(inputQueue, 2)
            assertCheckpointFilesCount(checkpointsPath, 1)
            assertOutputQueueContainsNoDuplicates(outputQueue, expectedMessagesCount = 6)
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

    private fun assertCheckpointFilesCount(path: Path, expectedCount: Long) {
        Files.list(path).use { list ->
            assertEquals(expectedCount, list.count())
        }
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
    fun `test state storing and loading`() {
        val wallet1 = 123456789L.toWalletAddress()
        val wallet2 = 555111555L.toWalletAddress()
        val btc = Asset("BTC")
        val eth = Asset("ETH")
        val btcEthMarketId = MarketId("BTC/ETH")

        val initialState = SequencerState(
            balances = mutableMapOf(
                wallet1 to mutableMapOf(
                    btc to BigDecimal("1").inSats(),
                    eth to BigDecimal("2").inWei(),
                ),
                wallet2 to mutableMapOf(
                    btc to BigDecimal("3").inSats(),
                ),
            ),
            markets = mutableMapOf(
                btcEthMarketId to Market(
                    id = btcEthMarketId,
                    tickSize = BigDecimal("0.05"),
                    maxLevels = 1000,
                    maxOrdersPerLevel = 1000,
                    marketPrice = BigDecimal("17.525"),
                    baseDecimals = 18,
                    quoteDecimals = 18,
                ).also {
                    it.orderBook.addOrder(
                        order {
                            this.guid = Random.nextLong()
                            this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                            this.price = BigDecimal("17.550").toDecimalValue()
                            this.wallet = wallet1.value
                            this.type = Order.Type.LimitSell
                        },
                    )
                },
            ),
        )

        // check that order book contains the order
        initialState.markets.getValue(btcEthMarketId).also { market ->
            assertEquals(1, market.orderBook.ordersByGuid.size)
            market.orderBook.ordersByGuid.values.first().also {
                assertEquals(wallet1, it.wallet)
                assertEquals(BigDecimal("0.0005").inSats(), it.quantity)
                assertEquals(350, it.levelIx)
            }

            market.orderBook.levels[350].also { level ->
                assertEquals(BigDecimal("17.550"), level.price)
                assertEquals(0, level.orderHead)
                assertEquals(1, level.orderTail)
            }
        }

        val checkpointPath = Path.of(checkpointsPath.toString(), "1.ckpt")
        initialState.persist(checkpointPath)

        val restoredState = SequencerState.load(checkpointPath)

        assertEquals(initialState, restoredState)
        assertContentEquals(
            initialState.markets.getValue(btcEthMarketId).orderBook.levels,
            restoredState.markets.getValue(btcEthMarketId).orderBook.levels,
        )
    }
}
