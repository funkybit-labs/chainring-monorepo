package co.chainring

import co.chainring.core.model.Symbol
import co.chainring.sequencer.apps.GatewayApp
import co.chainring.sequencer.apps.GatewayConfig
import co.chainring.sequencer.apps.SequencerApp
import co.chainring.sequencer.core.Asset
import co.chainring.sequencer.core.FeeRate
import co.chainring.sequencer.core.FeeRates
import co.chainring.sequencer.core.LevelOrder
import co.chainring.sequencer.core.Market
import co.chainring.sequencer.core.MarketId
import co.chainring.sequencer.core.OrderGuid
import co.chainring.sequencer.core.SequencerState
import co.chainring.sequencer.core.WalletAddress
import co.chainring.sequencer.core.queueHome
import co.chainring.sequencer.core.toBigDecimal
import co.chainring.sequencer.core.toBigInteger
import co.chainring.sequencer.core.toDecimalValue
import co.chainring.sequencer.core.toIntegerValue
import co.chainring.sequencer.core.toMarketId
import co.chainring.sequencer.core.toWalletAddress
import co.chainring.sequencer.proto.GatewayGrpcKt
import co.chainring.sequencer.proto.MarketCheckpoint
import co.chainring.sequencer.proto.MetaInfoCheckpoint
import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.Order.Type
import co.chainring.sequencer.proto.OrderDisposition
import co.chainring.sequencer.proto.SequencerResponse
import co.chainring.sequencer.proto.balanceBatch
import co.chainring.sequencer.proto.cancelOrder
import co.chainring.sequencer.proto.deposit
import co.chainring.sequencer.proto.feeRates
import co.chainring.sequencer.proto.market
import co.chainring.sequencer.proto.marketMinFee
import co.chainring.sequencer.proto.order
import co.chainring.sequencer.proto.orderBatch
import co.chainring.sequencer.proto.setFeeRatesRequest
import co.chainring.sequencer.proto.setMarketMinFeesRequest
import co.chainring.sequencer.proto.setWithdrawalFeesRequest
import co.chainring.sequencer.proto.withdrawal
import co.chainring.sequencer.proto.withdrawalFee
import co.chainring.testutils.inSats
import co.chainring.testutils.inWei
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
import java.io.FileInputStream
import java.lang.System.getenv
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.time.Duration.Companion.seconds

class TestSequencerCheckpoints {
    private val isOnCI = (getenv("CI_RUN") ?: "0") == "1"
    private val currentTime = AtomicLong(System.currentTimeMillis())
    private val testDirPath = Path.of(queueHome, "test")
    private val checkpointsPath = Path.of(testDirPath.toString(), "checkpoints")

    private val wallet1 = 123456789L.toWalletAddress()
    private val wallet2 = 555111555L.toWalletAddress()
    private val btc = Asset("BTC")
    private val eth = Asset("ETH")
    private val usdc = Asset("USDC")
    private val btcEthMarketId = MarketId("BTC/ETH")
    private val btcUsdcMarketId = MarketId("BTC/USDC")

    @BeforeEach
    fun beforeEach() {
        testDirPath.toFile().deleteRecursively()
        checkpointsPath.createDirectories()
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

        assertQueueFilesCount(inputQueue, 0)
        assertCheckpointsCount(checkpointsPath, 0)

        val gatewayApp = GatewayApp(GatewayConfig(port = 5339), inputQueue, outputQueue, sequencedQueue)
        val sequencerApp = SequencerApp(inputQueue, outputQueue, checkpointsPath)

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
                        this.tickSize = "0.05".toBigDecimal().toDecimalValue()
                        this.maxLevels = 1000
                        this.maxOrdersPerLevel = 1000
                        this.marketPrice = "17.525".toBigDecimal().toDecimalValue()
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
                                    this.wallet = wallet1.value
                                    this.amount = BigDecimal("1.01").inSats().toIntegerValue()
                                },
                                deposit {
                                    this.asset = btcEthMarketId.quoteAsset().value
                                    this.wallet = wallet1.value
                                    this.amount = BigDecimal("1.02").inWei().toIntegerValue()
                                },
                                deposit {
                                    this.asset = btcEthMarketId.quoteAsset().value
                                    this.wallet = wallet2.value
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
                        this.wallet = wallet1.value
                        this.ordersToAdd.add(
                            order {
                                this.guid = Random.nextLong()
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.550").toDecimalValue()
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
                        this.wallet = wallet1.value
                        this.ordersToAdd.add(
                            order {
                                this.guid = Random.nextLong()
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.560").toDecimalValue()
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
                        this.wallet = wallet1.value
                        this.ordersToAdd.add(
                            order {
                                this.guid = Random.nextLong()
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.570").toDecimalValue()
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
                                this.wallet = wallet1.value
                                this.amount = BigDecimal("0.01").inSats().toIntegerValue()
                                this.externalGuid = "guid1"
                            },
                            withdrawal {
                                this.asset = btcEthMarketId.quoteAsset().value
                                this.wallet = wallet1.value
                                this.amount = BigDecimal("0.02").inWei().toIntegerValue()
                                this.externalGuid = "guid2"
                            },
                            withdrawal {
                                this.asset = btcEthMarketId.quoteAsset().value
                                this.wallet = wallet2.value
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
                    this.wallet = wallet1.value
                    this.ordersToAdd.add(
                        order {
                            this.guid = Random.nextLong()
                            this.amount = BigDecimal("0.000005").inSats().toIntegerValue()
                            this.price = BigDecimal("17.570").toDecimalValue()
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
                    this.wallet = wallet2.value
                    this.ordersToAdd.add(
                        order {
                            this.guid = Random.nextLong()
                            this.amount = BigDecimal("0.0011").inSats().toIntegerValue()
                            this.price = BigDecimal.ZERO.toDecimalValue()
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
            assertCheckpointsCount(checkpointsPath, 1)
            assertOutputQueueContainsNoDuplicates(outputQueue, expectedMessagesCount = 11)

            currentTime.addAndGet(60.seconds.inWholeMilliseconds)

            assertTrue(
                gateway.applyOrderBatch(
                    orderBatch {
                        this.guid = UUID.randomUUID().toString()
                        this.marketId = btcEthMarketId.value
                        this.wallet = wallet1.value
                        this.ordersToAdd.add(
                            order {
                                this.guid = Random.nextLong()
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.550").toDecimalValue()
                                this.type = Order.Type.LimitSell
                            },
                        )
                    },
                ).success,
            )

            assertCheckpointsCount(checkpointsPath, 2)

            sequencerApp.stop()
            corruptLatestCheckpoint()
            sequencerApp.start()

            currentTime.addAndGet(1.seconds.inWholeMilliseconds)

            gateway.applyOrderBatch(
                orderBatch {
                    this.guid = UUID.randomUUID().toString()
                    this.marketId = btcEthMarketId.value
                    this.wallet = wallet2.value
                    this.ordersToAdd.add(
                        order {
                            this.guid = Random.nextLong()
                            this.amount = BigDecimal("0.0011").inSats().toIntegerValue()
                            this.price = BigDecimal.ZERO.toDecimalValue()
                            this.type = Order.Type.MarketBuy
                        },
                    )
                },
            ).also {
                assertTrue(it.success)
                assertEquals(3, it.sequencerResponse.ordersChangedCount)
            }

            assertCheckpointsCount(checkpointsPath, 2)
            assertOutputQueueContainsNoDuplicates(outputQueue, expectedMessagesCount = 13)
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

    private fun assertCheckpointsCount(path: Path, expectedCount: Long) {
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

    private fun corruptLatestCheckpoint() {
        // delete checkpoint for one of the markets
        Files
            .list(latestCheckpointPath())
            .filter { it.fileName.toString().startsWith("market_") }
            .toList()
            .first()
            .toFile()
            .delete()
    }

    private fun latestCheckpointPath(): Path =
        Files.list(checkpointsPath).toList()
            .map { it.fileName.name.toLong() }
            .maxOf { it }
            .toString()
            .let {
                Path.of(checkpointsPath.toString(), it)
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
                    wallet1 to mutableMapOf(
                        btc to BigDecimal("1").inSats(),
                        eth to BigDecimal("2").inWei(),
                    ),
                    wallet2 to mutableMapOf(
                        btc to BigDecimal("3").inSats(),
                    ),
                ),
                consumed = mutableMapOf(
                    wallet1 to mutableMapOf(
                        btc to mutableMapOf(btcEthMarketId to BigDecimal("1").inSats()),
                        eth to mutableMapOf(btcEthMarketId to BigDecimal("2").inWei()),
                    ),
                    wallet2 to mutableMapOf(
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
                        initialMarketPrice = BigDecimal("17.525"),
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
                        initialMarketPrice = BigDecimal("17.525"),
                        baseDecimals = 18,
                        quoteDecimals = 18,
                    ).also { market ->
                        listOf(
                            order {
                                this.guid = 1
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.550").toDecimalValue()
                                this.type = Order.Type.LimitSell
                            },
                            order {
                                this.guid = 2
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.560").toDecimalValue()
                                this.type = Order.Type.LimitSell
                            },
                            order {
                                this.guid = 3
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.600").toDecimalValue()
                                this.type = Order.Type.LimitSell
                            },
                        ).forEach { order ->
                            market.addOrder(
                                wallet1.value,
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
                        initialMarketPrice = BigDecimal("17.525"),
                        baseDecimals = 18,
                        quoteDecimals = 18,
                    ).also { market ->
                        listOf(
                            order {
                                this.guid = 1
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.3").toDecimalValue()
                                this.type = Order.Type.LimitBuy
                            },
                            order {
                                this.guid = 2
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.4").toDecimalValue()
                                this.type = Order.Type.LimitBuy
                            },
                            order {
                                this.guid = 3
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.5").toDecimalValue()
                                this.type = Order.Type.LimitBuy
                            },
                        ).forEach { order ->
                            market.addOrder(
                                wallet1.value,
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
                    wallet1 to mutableMapOf(
                        btc to BigDecimal("1").inSats(),
                        eth to BigDecimal("2").inWei(),
                        usdc to BigDecimal("10000").inWei(),
                    ),
                    wallet2 to mutableMapOf(
                        btc to BigDecimal("3").inSats(),
                        usdc to BigDecimal("10000").inWei(),
                    ),
                ),
                markets = mutableMapOf(
                    btcEthMarketId to Market(
                        id = btcEthMarketId,
                        tickSize = BigDecimal("0.05"),
                        maxLevels = 1000,
                        maxOrdersPerLevel = 1000,
                        initialMarketPrice = BigDecimal("17.525"),
                        baseDecimals = 18,
                        quoteDecimals = 18,
                    ).also { market ->
                        listOf(
                            order {
                                this.guid = 1
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.3").toDecimalValue()
                                this.type = Order.Type.LimitBuy
                            },
                            order {
                                this.guid = 2
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.4").toDecimalValue()
                                this.type = Order.Type.LimitBuy
                            },
                            order {
                                this.guid = 3
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.5").toDecimalValue()
                                this.type = Order.Type.LimitBuy
                            },
                            order {
                                this.guid = 4
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.550").toDecimalValue()
                                this.type = Order.Type.LimitSell
                            },
                            order {
                                this.guid = 5
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.560").toDecimalValue()
                                this.type = Order.Type.LimitSell
                            },
                            order {
                                this.guid = 6
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.600").toDecimalValue()
                                this.type = Order.Type.LimitSell
                            },
                        ).forEach { order ->
                            market.addOrder(
                                wallet1.value,
                                order,
                                FeeRates.fromPercents(maker = 1.0, taker = 2.0),
                            )
                        }
                    },
                    btcUsdcMarketId to Market(
                        id = btcUsdcMarketId,
                        tickSize = BigDecimal("1.00"),
                        maxLevels = 1000,
                        maxOrdersPerLevel = 1000,
                        initialMarketPrice = BigDecimal("70000"),
                        baseDecimals = 18,
                        quoteDecimals = 18,
                    ).also { market ->
                        listOf(
                            order {
                                this.guid = 1
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("69997").toDecimalValue()
                                this.type = Order.Type.LimitBuy
                            },
                            order {
                                this.guid = 2
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("69998").toDecimalValue()
                                this.type = Order.Type.LimitBuy
                            },
                            order {
                                this.guid = 3
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("69999").toDecimalValue()
                                this.type = Order.Type.LimitBuy
                            },
                            order {
                                this.guid = 4
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("70001").toDecimalValue()
                                this.type = Order.Type.LimitSell
                            },
                            order {
                                this.guid = 5
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("70002").toDecimalValue()
                                this.type = Order.Type.LimitSell
                            },
                            order {
                                this.guid = 6
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("70003").toDecimalValue()
                                this.type = Order.Type.LimitSell
                            },
                        ).forEach { order ->
                            market.addOrder(
                                wallet1.value,
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
        val price = when (orderType) {
            Type.LimitBuy -> BigDecimal("17.500").toDecimalValue()
            Type.LimitSell -> BigDecimal("17.550").toDecimalValue()
            else -> throw IllegalArgumentException("$orderType not supported")
        }

        verifySerialization(
            SequencerState(
                feeRates = feeRates,
                withdrawalFees = mutableMapOf(
                    Symbol("BTC") to BigInteger.TEN,
                ),
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
                        initialMarketPrice = BigDecimal("17.525"),
                        baseDecimals = 18,
                        quoteDecimals = 18,
                    ).also { market ->
                        // fill and remove data to set level's head and tail to the position 990
                        (0..990).map {
                            order {
                                this.guid = it.toLong()
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = price
                                this.type = orderType
                            }
                        }.also { orders ->
                            market.applyOrderBatch(
                                orderBatch {
                                    guid = UUID.randomUUID().toString()
                                    marketId = btcEthMarketId.value
                                    wallet = wallet1.value
                                    ordersToAdd.addAll(orders)
                                },
                                feeRates,
                            )

                            market.applyOrderBatch(
                                orderBatch {
                                    guid = UUID.randomUUID().toString()
                                    marketId = btcEthMarketId.value
                                    wallet = wallet1.value
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

                        // add 20 more orders to wrap level's buffer
                        (991..1010).map {
                            order {
                                this.guid = it.toLong()
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = price
                                this.type = orderType
                            }
                        }.forEach { order ->
                            market.addOrder(wallet1.value, order, feeRates)
                        }

                        // verify setup
                        val targetLevel = market.levels[market.levelIx(price.toBigDecimal())]
                        assertEquals(990, targetLevel.orderHead)
                        assertEquals(10, targetLevel.orderTail)
                    },
                ),
            ),
        )
    }

    private fun verifySerialization(initialState: SequencerState) {
        checkpointsPath.toFile().deleteRecursively()
        checkpointsPath.createDirectories()

        val checkpointPath = Path.of(checkpointsPath.toString(), "1")
        initialState.persist(checkpointPath)

        verifySerializedOrdersContent(initialState, checkpointPath)

        val restoredState = SequencerState().apply { load(checkpointPath) }

        initialState.markets.values.forEach { initialStateMarket ->
            val restoredStateMarket = restoredState.markets.getValue(initialStateMarket.id)

            initialStateMarket.levels.forEach { initialStateLevel ->
                initialStateLevel.orders.forEachIndexed { i, initialStateOrder ->
                    assertEquals(
                        initialStateOrder,
                        restoredStateMarket.levels[initialStateLevel.levelIx].orders[i],
                        "Order mismatch at levelIx=${initialStateLevel.levelIx}, orderIx=$i",
                    )
                }
            }

            assertContentEquals(
                initialStateMarket.levels,
                restoredStateMarket.levels,
                "Levels in market ${initialStateMarket.id} don't match",
            )
        }

        assertEquals(initialState, restoredState)
    }

    private fun verifySerializedOrdersContent(initialState: SequencerState, checkpointPath: Path) {
        val marketIds = FileInputStream(Path.of(checkpointPath.toString(), "metainfo").toFile()).use { inputStream ->
            MetaInfoCheckpoint.parseFrom(inputStream).marketsList.map(::MarketId)
        }
        assertEquals(initialState.markets.keys, marketIds.toSet())

        marketIds.forEach { marketId ->
            val marketCheckpointFileName = "market_${marketId.baseAsset()}_${marketId.quoteAsset()}"
            FileInputStream(Path.of(checkpointPath.toString(), marketCheckpointFileName).toFile()).use { inputStream ->
                val marketCheckpoint = MarketCheckpoint.parseFrom(inputStream)

                initialState.markets[marketId]!!.let { initialMarket ->
                    assertEquals(initialMarket.id, marketCheckpoint.id.toMarketId())
                    assertEquals(initialMarket.tickSize, marketCheckpoint.tickSize.toBigDecimal())
                    assertEquals(initialMarket.initialMarketPrice, marketCheckpoint.marketPrice.toBigDecimal())
                    assertEquals(initialMarket.maxLevels, marketCheckpoint.maxLevels)
                    assertEquals(initialMarket.maxOrdersPerLevel, marketCheckpoint.maxOrdersPerLevel)
                    assertEquals(initialMarket.baseDecimals, marketCheckpoint.baseDecimals)
                    assertEquals(initialMarket.quoteDecimals, marketCheckpoint.quoteDecimals)

                    // verify checkpoint contains exact number of orders
                    assertEquals(
                        initialMarket.ordersByGuid.map { (_, v) -> v }.toSet(),
                        marketCheckpoint.levelsList.flatMap { it.ordersList }.map {
                            LevelOrder(
                                OrderGuid(it.guid),
                                WalletAddress(it.wallet),
                                it.quantity.toBigInteger(),
                                FeeRate(it.feeRate),
                                it.levelIx,
                                it.originalQuantity.toBigInteger(),
                            )
                        }.toSet(),
                    )
                }
            }
        }
    }
}
