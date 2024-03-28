package co.chainring

import co.chainring.apps.GatewayApp
import co.chainring.apps.SequencerApp
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) {
    logger.info { "Starting with args: ${args.joinToString(" ")}" }

    try {
        val sequencer = SequencerApp()
        sequencer.start()
        val gateway = GatewayApp()
        gateway.start()
        logger.info { "Started up" }
        gateway.blockUntilShutdown()
        sequencer.stop()
    } catch (e: Throwable) {
        logger.error(e) { "Failed to start" }
        exitProcess(1)
    }
}
