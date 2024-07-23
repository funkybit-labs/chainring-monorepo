package co.chainring.sequencer.core

import co.chainring.core.model.Symbol
import co.chainring.core.utils.humanReadableNanoseconds
import co.chainring.sequencer.proto.BalancesCheckpoint
import co.chainring.sequencer.proto.BalancesCheckpointKt.BalanceKt.consumption
import co.chainring.sequencer.proto.BalancesCheckpointKt.balance
import co.chainring.sequencer.proto.MarketCheckpoint
import co.chainring.sequencer.proto.StateDump
import co.chainring.sequencer.proto.WithdrawalFee
import co.chainring.sequencer.proto.balancesCheckpoint
import co.chainring.sequencer.proto.feeRates
import co.chainring.sequencer.proto.stateDump
import co.chainring.sequencer.proto.withdrawalFee
import io.github.oshai.kotlinlogging.KotlinLogging
import net.openhft.chronicle.queue.impl.RollingChronicleQueue
import java.math.BigInteger
import kotlin.system.measureNanoTime

typealias BalanceByAsset = MutableMap<Asset, BigInteger>
typealias ConsumedByAsset = MutableMap<Asset, MutableMap<MarketId, BigInteger>>

data class FeeRates(
    val maker: FeeRate,
    val taker: FeeRate,
) {
    companion object {
        fun fromPercents(maker: Double, taker: Double): FeeRates =
            FeeRates(
                maker = FeeRate.fromPercents(maker),
                taker = FeeRate.fromPercents(taker),
            )
    }
}

data class SequencerState(
    val markets: MutableMap<MarketId, Market> = mutableMapOf(),
    val balances: MutableMap<WalletAddress, BalanceByAsset> = mutableMapOf(),
    val consumed: MutableMap<WalletAddress, ConsumedByAsset> = mutableMapOf(),
    var feeRates: FeeRates = FeeRates(maker = FeeRate.zero, taker = FeeRate.zero),
    var withdrawalFees: MutableMap<Symbol, BigInteger> = mutableMapOf(),
) {
    private val logger = KotlinLogging.logger {}

    fun clear() {
        balances.clear()
        markets.clear()
        consumed.clear()
        feeRates = FeeRates(maker = FeeRate.zero, taker = FeeRate.zero)
        withdrawalFees.clear()
    }

    fun load(checkpointsQueue: RollingChronicleQueue, expectedCycle: Int) {
        measureNanoTime {
            val checkpointsTailer = checkpointsQueue.createTailer()
            checkpointsTailer.moveToIndex(checkpointsQueue.lastIndex())
            checkpointsTailer.readingDocument().use { docCtx ->
                val wire = docCtx.wire()!!
                val cycle = wire.read("cycle").readInt()

                if (cycle != expectedCycle) {
                    throw RuntimeException("Invalid cycle in the checkpoint. Expected $expectedCycle, got $cycle")
                }

                logger.debug { "Restoring from checkpoint for cycle $cycle" }

                measureNanoTime {
                    feeRates = FeeRates(
                        maker = FeeRate(wire.read("makerFeeRate").readLong()),
                        taker = FeeRate(wire.read("takerFeeRate").readLong()),
                    )

                    wire.read("withdrawalFees").sequence(withdrawalFees) { map, v ->
                        while (v.hasNextSequenceItem()) {
                            val fees = WithdrawalFee.parseFrom(v.bytes())
                            map[Symbol(fees.asset)] = fees.value.toBigInteger()
                        }
                    }
                }.let {
                    logger.debug { "load of fee rates took ${humanReadableNanoseconds(it)}" }
                }

                measureNanoTime {
                    val balancesCheckpoint = BalancesCheckpoint.parseFrom(wire.read("balances").bytes())
                    balancesCheckpoint.balancesList.forEach { balanceCheckpoint ->
                        val walletAddress = balanceCheckpoint.wallet.toWalletAddress()
                        val asset = balanceCheckpoint.asset.toAsset()
                        balances.getOrPut(walletAddress) { mutableMapOf() }[asset] = balanceCheckpoint.amount.toBigInteger()
                        if (balanceCheckpoint.consumedCount > 0) {
                            consumed.getOrPut(walletAddress) { mutableMapOf() }.getOrPut(asset) { mutableMapOf() }.putAll(
                                balanceCheckpoint.consumedList.associate {
                                    it.marketId.toMarketId() to it.consumed.toBigInteger()
                                },
                            )
                        }
                    }
                }.let {
                    logger.debug { "load of balances took ${humanReadableNanoseconds(it)}" }
                }

                measureNanoTime {
                    wire.read("markets").sequence(markets) { map, v ->
                        while (v.hasNextSequenceItem()) {
                            var market: Market
                            measureNanoTime {
                                val marketCheckpoint = MarketCheckpoint.parseFrom(v.bytes())
                                market = Market.fromCheckpoint(marketCheckpoint)
                                map[market.id] = market
                            }.let {
                                logger.debug { "load of market ${market.id} took ${humanReadableNanoseconds(it)}" }
                            }
                        }
                    }
                }.let {
                    logger.debug { "load all ${markets.size} markets took ${humanReadableNanoseconds(it)}" }
                }
            }
        }.let {
            logger.debug { "load of checkpoint took ${humanReadableNanoseconds(it)}" }
        }
    }

    fun persist(checkpointsQueue: RollingChronicleQueue, currentCycle: Int) {
        measureNanoTime {
            val checkpointsAppender = checkpointsQueue.acquireAppender()

            checkpointsAppender.writingDocument().use { docCtx ->
                docCtx.wire()!!
                    .write("cycle")
                    .int32(currentCycle)
                    .write("makerFeeRate")!!
                    .int64(feeRates.maker.value)
                    .write("takerFeeRate")
                    .int64(feeRates.taker.value)
                    .write("withdrawalFees")
                    .sequence(
                        this@SequencerState.withdrawalFees.map {
                            withdrawalFee {
                                this.asset = it.key.value
                                this.value = it.value.toIntegerValue()
                            }.toByteArray()
                        },
                    )
                    .write("balances")
                    .bytes(getBalancesCheckpoint().toByteArray())
                    .write("markets")
                    .sequence(
                        this@SequencerState.markets.values.sortedBy { it.id.value }.map { market ->
                            market.toCheckpoint().toByteArray()
                        },
                    )
            }
        }.let {
            logger.debug { "persist took ${humanReadableNanoseconds(it)}" }
        }
    }

    fun getDump(): StateDump {
        val marketsMap = markets
        return stateDump {
            this.balances.addAll(getBalancesCheckpoint().balancesList)
            marketsMap.forEach { (_, market) ->
                this.markets.add(market.toCheckpoint())
            }
            this.feeRates = feeRates {
                this.maker = this@SequencerState.feeRates.maker.value
                this.taker = this@SequencerState.feeRates.taker.value
            }
            this.withdrawalFees.addAll(
                this@SequencerState.withdrawalFees.map {
                    withdrawalFee {
                        this.asset = it.key.value
                        this.value = it.value.toIntegerValue()
                    }
                },
            )
        }
    }

    private fun getBalancesCheckpoint(): BalancesCheckpoint {
        val balancesMap = balances

        return balancesCheckpoint {
            balancesMap.forEach { (wallet, walletBalances) ->
                walletBalances.forEach { (asset, amount) ->
                    this.balances.add(
                        balance {
                            this.wallet = wallet.value
                            this.asset = asset.value
                            this.amount = amount.toIntegerValue()
                            this.consumed.addAll(
                                this@SequencerState.consumed.getOrDefault(wallet, mapOf()).getOrDefault(asset, mapOf()).map {
                                    consumption {
                                        this.marketId = it.key.value
                                        this.consumed = it.value.toIntegerValue()
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}
