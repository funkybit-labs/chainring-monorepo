package co.chainring

import co.chainring.core.Maker
import co.chainring.core.Taker
import co.chainring.core.model.db.MarketId
import co.chainring.core.toFundamentalUnits
import co.chainring.integrationtests.utils.TraceRecorder
import java.math.BigDecimal
import java.util.Timer
import kotlin.concurrent.timerTask
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes


val interval = 10.minutes
const val initialTakers = 5
const val maxTakers = 100
val newTakerInterval = interval / (maxTakers - initialTakers)

val statsInterval = 1.minutes

fun main() {
    val timer = Timer()

    // maker
    val maker = Maker(
        tightness = 5, skew = 0, levels = 10,
        native = BigDecimal.TEN.movePointRight(18).toBigInteger(),
        assets = mapOf(
            //"ETH" to 200.toFundamentalUnits(18),
            "USDC" to 10000.toFundamentalUnits(6),
            "DAI" to 5000.toFundamentalUnits(18)
        )
    )
    val usdcDai = MarketId("USDC/DAI")
    val btcEth = MarketId("BTC/ETH")
    maker.start(listOf(usdcDai))


    // takers
    val takers = mutableListOf<Taker>()
    (1..initialTakers).map {
        takers.add(startTaker(usdcDai))
    }
    timer.scheduleAtFixedRate(timerTask {
        takers.add(startTaker(usdcDai))
    }, newTakerInterval.inWholeMilliseconds, newTakerInterval.inWholeMilliseconds)

    // print metrics
    val statsTask = timerTask {
        TraceRecorder.full.printStatsAndFlush()
    }
    timer.scheduleAtFixedRate(statsTask, statsInterval.inWholeMilliseconds, statsInterval.inWholeMilliseconds)

    // tear down
    Thread.sleep(interval.inWholeMilliseconds)
    statsTask.cancel()
    maker.stop()
    takers.forEach {
        it.stop()
    }
    timer.cancel()
}

private fun startTaker(usdcDai: MarketId): Taker {
    val taker = Taker(
        rate = Random.nextLong(5000, 20000),
        sizeFactor = Random.nextDouble(5.0, 20.0),
        native = null,
        assets = mapOf(
            "USDC" to 100.toFundamentalUnits(6),
            "DAI" to 50.toFundamentalUnits(18)
        )
    )
    taker.start(listOf(usdcDai))
    return taker
}