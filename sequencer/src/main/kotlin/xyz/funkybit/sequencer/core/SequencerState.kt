package xyz.funkybit.sequencer.core

import io.github.oshai.kotlinlogging.KotlinLogging
import net.openhft.chronicle.queue.impl.RollingChronicleQueue
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.utils.humanReadableNanoseconds
import xyz.funkybit.sequencer.proto.BalancesCheckpoint
import xyz.funkybit.sequencer.proto.BalancesCheckpointKt.BalanceKt.consumption
import xyz.funkybit.sequencer.proto.BalancesCheckpointKt.balance
import xyz.funkybit.sequencer.proto.MarketCheckpoint
import xyz.funkybit.sequencer.proto.StateDump
import xyz.funkybit.sequencer.proto.WithdrawalFee
import xyz.funkybit.sequencer.proto.balancesCheckpoint
import xyz.funkybit.sequencer.proto.feeRates
import xyz.funkybit.sequencer.proto.stateDump
import xyz.funkybit.sequencer.proto.withdrawalFee
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
    val balances: MutableMap<AccountGuid, BalanceByAsset> = mutableMapOf(),
    val consumed: MutableMap<AccountGuid, ConsumedByAsset> = mutableMapOf(),
    var feeRates: FeeRates = FeeRates(maker = FeeRate.zero, taker = FeeRate.zero),
    var withdrawalFees: MutableMap<Symbol, BigInteger> = mutableMapOf(),
) {
    private val logger = KotlinLogging.logger {}
    private val marketIdsByAsset = mutableMapOf<Asset, MutableList<MarketId>>()

    fun addMarket(market: Market) {
        markets[market.id] = market
        val (baseAsset, quoteAsset) = market.id.assets()
        marketIdsByAsset.getOrPut(baseAsset) { mutableListOf() }.add(market.id)
        marketIdsByAsset.getOrPut(quoteAsset) { mutableListOf() }.add(market.id)
    }

    fun getMarketIdsByAsset(asset: Asset): List<MarketId> =
        marketIdsByAsset.getOrElse(asset) { mutableListOf() }.toList()

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
                        val accountGuid = balanceCheckpoint.account.toAccountGuid()
                        val asset = balanceCheckpoint.asset.toAsset()
                        balances.getOrPut(accountGuid) { mutableMapOf() }[asset] = balanceCheckpoint.amount.toBigInteger()
                        if (balanceCheckpoint.consumedCount > 0) {
                            consumed.getOrPut(accountGuid) { mutableMapOf() }.getOrPut(asset) { mutableMapOf() }.putAll(
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
                    wire.read("markets").sequence(markets) { _, v ->
                        while (v.hasNextSequenceItem()) {
                            var market: Market
                            measureNanoTime {
                                val marketCheckpoint = MarketCheckpoint.parseFrom(v.bytes())
                                market = Market.fromCheckpoint(marketCheckpoint)
                                addMarket(market)
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
            balancesMap.forEach { (account, accountBalances) ->
                accountBalances.forEach { (asset, amount) ->
                    this.balances.add(
                        balance {
                            this.account = account.value
                            this.asset = asset.value
                            this.amount = amount.toIntegerValue()
                            this.consumed.addAll(
                                this@SequencerState.consumed.getOrDefault(account, mapOf()).getOrDefault(asset, mapOf()).map {
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
