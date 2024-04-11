package co.chainring.sequencer.core

import co.chainring.sequencer.proto.BalancesCheckpoint
import co.chainring.sequencer.proto.BalancesCheckpointKt.balance
import co.chainring.sequencer.proto.MarketCheckpoint
import co.chainring.sequencer.proto.balancesCheckpoint
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.name

typealias BalanceByAsset = MutableMap<Asset, BigInteger>

data class SequencerState(
    val markets: MutableMap<MarketId, Market> = mutableMapOf(),
    val balances: MutableMap<WalletAddress, BalanceByAsset> = mutableMapOf(),
) {
    fun load(sourceDir: Path) {
        balances.clear()
        markets.clear()

        FileInputStream(Path.of(sourceDir.toString(), "balances").toFile()).use { inputStream ->
            BalancesCheckpoint.parseFrom(inputStream).balancesList.forEach { balanceCheckpoint ->
                val walletBalances = balances.getOrPut(WalletAddress(balanceCheckpoint.wallet)) { mutableMapOf() }
                walletBalances[Asset(balanceCheckpoint.asset)] = balanceCheckpoint.amount.toBigInteger()
            }
        }

        Files.list(sourceDir)
            .filter { it.name.startsWith("market_") }
            .forEach { marketCheckpointPath ->
                FileInputStream(marketCheckpointPath.toFile()).use { inputStream ->
                    val marketCheckpoint = MarketCheckpoint.parseFrom(inputStream)
                    val market = Market.fromCheckpoint(marketCheckpoint)
                    markets[market.id] = market
                }
            }
    }

    fun persist(destinationDir: Path) {
        destinationDir.createDirectories()

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
                            },
                        )
                    }
                }
            }.writeTo(outputStream)
        }

        markets.forEach { (id, market) ->
            val fileName = "market_${id.baseAsset()}_${id.quoteAsset()}"

            FileOutputStream(Path.of(destinationDir.toString(), fileName).toFile()).use { outputStream ->
                market.toCheckpoint().writeTo(outputStream)
            }
        }
    }
}
