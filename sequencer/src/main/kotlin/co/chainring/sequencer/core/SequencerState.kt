package co.chainring.sequencer.core

import co.chainring.core.model.Symbol
import co.chainring.core.utils.humanReadableNanoseconds
import co.chainring.sequencer.proto.BalancesCheckpoint
import co.chainring.sequencer.proto.BalancesCheckpointKt.BalanceKt.consumption
import co.chainring.sequencer.proto.BalancesCheckpointKt.balance
import co.chainring.sequencer.proto.MarketCheckpoint
import co.chainring.sequencer.proto.MetaInfoCheckpoint
import co.chainring.sequencer.proto.StateDump
import co.chainring.sequencer.proto.balancesCheckpoint
import co.chainring.sequencer.proto.feeRates
import co.chainring.sequencer.proto.metaInfoCheckpoint
import co.chainring.sequencer.proto.stateDump
import co.chainring.sequencer.proto.withdrawalFee
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.nio.file.Path
import kotlin.io.path.createDirectories
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

    fun load(sourceDir: Path) {
        measureNanoTime {
            FileInputStream(Path.of(sourceDir.toString(), "balances").toFile()).use { inputStream ->
                val balancesCheckpoint = BalancesCheckpoint.parseFrom(inputStream)
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
            }
        }.let {
            logger.debug { "load of balances took ${humanReadableNanoseconds(it)}" }
        }

        measureNanoTime {
            val metaInfoCheckpoint = FileInputStream(Path.of(sourceDir.toString(), "metainfo").toFile()).use { inputStream ->
                MetaInfoCheckpoint.parseFrom(inputStream)
            }

            feeRates = FeeRates(
                maker = FeeRate(metaInfoCheckpoint.makerFeeRate),
                taker = FeeRate(metaInfoCheckpoint.takerFeeRate),
            )

            val marketIds = metaInfoCheckpoint.marketsList.map(::MarketId)

            marketIds.forEach { marketId ->
                measureNanoTime {
                    val marketCheckpointFileName = "market_${marketId.baseAsset()}_${marketId.quoteAsset()}"
                    FileInputStream(Path.of(sourceDir.toString(), marketCheckpointFileName).toFile()).use { inputStream ->
                        val marketCheckpoint = MarketCheckpoint.parseFrom(inputStream)
                        val market = Market.fromCheckpoint(marketCheckpoint)
                        markets[market.id] = market
                    }
                }.let {
                    logger.debug { "load of marketId market took ${humanReadableNanoseconds(it)}" }
                }
            }

            withdrawalFees =
                metaInfoCheckpoint.withdrawalFeesList.associate { Symbol(it.asset) to it.value.toBigInteger() }
                    .toMutableMap()
        }.let {
            logger.debug { "load all ${markets.size} markets took ${humanReadableNanoseconds(it)}" }
        }
    }

    fun persist(destinationDir: Path) {
        destinationDir.createDirectories()

        // we are writing a list of markets into a separate file first so that
        // when loading we could be sure that checkpoint files for all markets are present
        FileOutputStream(Path.of(destinationDir.toString(), "metainfo").toFile()).use { outputStream ->
            val marketIds = this.markets.keys.map { it.value }.sorted()
            metaInfoCheckpoint {
                this.markets.addAll(marketIds)
                this.makerFeeRate = feeRates.maker.value
                this.takerFeeRate = feeRates.taker.value
                this.withdrawalFees.addAll(
                    this@SequencerState.withdrawalFees.map {
                        withdrawalFee {
                            this.asset = it.key.value
                            this.value = it.value.toIntegerValue()
                        }
                    },
                )
            }.writeTo(outputStream)
        }

        measureNanoTime {
            FileOutputStream(Path.of(destinationDir.toString(), "balances").toFile()).use { outputStream ->
                getBalancesCheckpoint().writeTo(outputStream)
            }
        }.let {
            logger.debug { "persist of balances took ${humanReadableNanoseconds(it)}" }
        }

        measureNanoTime {
            markets.forEach { (id, market) ->
                measureNanoTime {
                    val fileName = "market_${id.baseAsset()}_${id.quoteAsset()}"

                    FileOutputStream(Path.of(destinationDir.toString(), fileName).toFile()).use { outputStream ->
                        market.toCheckpoint().writeTo(outputStream)
                    }
                }.let {
                    logger.debug { "persist of $id market took ${humanReadableNanoseconds(it)}" }
                }
            }
        }.let {
            logger.debug { "persist all ${markets.size} markets took ${humanReadableNanoseconds(it)}" }
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
