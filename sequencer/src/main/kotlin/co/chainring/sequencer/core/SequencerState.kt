package co.chainring.sequencer.core

import co.chainring.sequencer.proto.BalancesCheckpoint
import co.chainring.sequencer.proto.BalancesCheckpointKt.BalanceKt.consumption
import co.chainring.sequencer.proto.BalancesCheckpointKt.balance
import co.chainring.sequencer.proto.MarketCheckpoint
import co.chainring.sequencer.proto.balancesCheckpoint
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.system.measureNanoTime

typealias BalanceByAsset = MutableMap<Asset, BigInteger>
typealias ConsumedByAsset = MutableMap<Asset, MutableMap<MarketId, BigInteger>>

data class SequencerState(
    val markets: MutableMap<MarketId, Market> = mutableMapOf(),
    val balances: MutableMap<WalletAddress, BalanceByAsset> = mutableMapOf(),
    val consumed: MutableMap<WalletAddress, ConsumedByAsset> = mutableMapOf(),
) {
    private val logger = KotlinLogging.logger {}

    fun load(sourceDir: Path) {
        balances.clear()
        markets.clear()
        consumed.clear()

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
            logger.debug { "load of balances took ${it / 1000}us" }
        }

        measureNanoTime {
            Files.list(sourceDir)
                .filter { it.name.startsWith("market_") }
                .forEach { marketCheckpointPath ->
                    FileInputStream(marketCheckpointPath.toFile()).use { inputStream ->
                        val marketCheckpoint = MarketCheckpoint.parseFrom(inputStream)
                        val market = Market.fromCheckpoint(marketCheckpoint)
                        markets[market.id] = market
                    }
                }
        }.let {
            logger.debug { "load of ${markets.size} markets took ${it / 1000}us" }
        }
    }

    fun persist(destinationDir: Path) {
        destinationDir.createDirectories()

        measureNanoTime {
            FileOutputStream(Path.of(destinationDir.toString(), "balances").toFile()).use { outputStream ->
                val balancesMap = balances

                balancesCheckpoint {
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
                }.writeTo(outputStream)
            }
        }.let {
            logger.debug { "persist of balances took ${it / 1000}us" }
        }

        measureNanoTime {
            markets.forEach { (id, market) ->
                val fileName = "market_${id.baseAsset()}_${id.quoteAsset()}"

                FileOutputStream(Path.of(destinationDir.toString(), fileName).toFile()).use { outputStream ->
                    market.toCheckpoint().writeTo(outputStream)
                }
            }
        }.let {
            logger.debug { "persist of ${markets.size} markets took ${it / 1000}us" }
        }
    }
}
