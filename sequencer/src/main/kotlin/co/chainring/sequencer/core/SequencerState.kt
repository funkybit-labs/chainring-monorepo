package co.chainring.sequencer.core

import co.chainring.sequencer.proto.Checkpoint
import co.chainring.sequencer.proto.CheckpointKt.balance
import co.chainring.sequencer.proto.checkpoint
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.nio.file.Path

typealias BalanceByAsset = MutableMap<Asset, BigInteger>

data class SequencerState(
    val markets: MutableMap<MarketId, Market> = mutableMapOf(),
    val balances: MutableMap<WalletAddress, BalanceByAsset> = mutableMapOf(),
) {
    companion object {
        fun load(source: Path): SequencerState {
            return FileInputStream(source.toFile()).use { inputStream ->
                val checkpoint = Checkpoint.parseFrom(inputStream)

                val balances = mutableMapOf<WalletAddress, BalanceByAsset>()
                checkpoint.balancesList.forEach { balanceCheckpoint ->
                    val walletBalances = balances.getOrPut(WalletAddress(balanceCheckpoint.wallet)) { mutableMapOf() }
                    walletBalances[Asset(balanceCheckpoint.asset)] = balanceCheckpoint.amount.toBigInteger()
                }

                val markets = mutableMapOf<MarketId, Market>()
                checkpoint.marketsList.forEach { marketCheckpoint ->
                    val market = Market.fromCheckpoint(marketCheckpoint)
                    markets[market.id] = market
                }
                SequencerState(
                    balances = balances,
                    markets = markets,
                )
            }
        }
    }

    fun persist(destination: Path) {
        FileOutputStream(destination.toFile()).use { outputStream ->
            val balancesMap = balances
            val marketsMap = markets

            checkpoint {
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
                marketsMap.forEach { (_, market) ->
                    this.markets.add(market.toCheckpoint())
                }
            }.writeTo(outputStream)
        }
    }
}
