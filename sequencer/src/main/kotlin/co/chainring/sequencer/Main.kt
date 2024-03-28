package co.chainring.sequencer

import co.chainring.sequencer.apps.GatewayApp
import co.chainring.sequencer.apps.SequencerApp
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) {
    logger.info { "Starting with args: ${args.joinToString(" ")}" }

    try {
        val sequencer = SequencerApp()
        sequencer.start()
        try {
            val gateway = GatewayApp()
            gateway.start()
            logger.info { "Started up" }
            gateway.blockUntilShutdown()
        } catch (e: Exception) {
            logger.error(e) { "Failed, stopping sequencer and exiting" }
        } finally {
            sequencer.stop()
        }
    } catch (e: Throwable) {
        logger.error(e) { "Failed to start" }
        exitProcess(1)
    }
}
