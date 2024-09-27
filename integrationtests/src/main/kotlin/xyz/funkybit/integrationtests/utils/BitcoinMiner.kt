package xyz.funkybit.integrationtests.utils

import io.github.oshai.kotlinlogging.KotlinLogging
import xyz.funkybit.core.blockchain.bitcoin.BitcoinClient
import xyz.funkybit.core.blockchain.bitcoin.bitcoinConfig
import kotlin.concurrent.thread

object BitcoinMiner {

    val logger = KotlinLogging.logger {}
    private var miningThread: Thread? = null

    fun start() {
        if (!bitcoinConfig.enabled || miningThread != null) {
            return
        }

        miningThread = thread(start = true, name = "bitcoin mining", isDaemon = true) {
            while (true) {
                try {
                    BitcoinClient.mine(1)
                    Thread.sleep(1000)
                } catch (ie: InterruptedException) {
                    logger.warn { "Exiting bitcoin mining thread thread" }
                    return@thread
                } catch (e: Exception) {
                    logger.error(e) { "Unhandled exception in bitcoin mining thread" }
                }
            }
        }
    }

    fun stop() {
        miningThread?.let {
            it.interrupt()
            it.join(1000)
        }
        miningThread = null
    }
}
