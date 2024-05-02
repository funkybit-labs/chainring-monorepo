package co.chainring.sequencer

import co.chainring.core.utils.GCStatsProvider
import co.chainring.sequencer.apps.GatewayApp
import co.chainring.sequencer.apps.QueueProcessorApp
import co.chainring.sequencer.apps.SequencerApp
import co.chainring.sequencer.core.queueHome
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) {
    logger.info { "Starting with args: ${args.joinToString(" ")}" }

    try {
        val gcStatsProvider = GCStatsProvider()
        val sequencer = SequencerApp(
            checkpointsPath = if (System.getenv("CHECKPOINTS_ENABLED").toBoolean()) {
                Path.of(queueHome, "checkpoints")
            } else {
                null
            },
        )
        sequencer.start()
        val queueProcessorApp = QueueProcessorApp(
            sequencer.inputQueue,
            sequencer.outputQueue,
        )
        queueProcessorApp.start()
        try {
            val gateway = GatewayApp()
            gateway.start()
            gcStatsProvider.start()
            logger.info { "Started up" }
            gateway.blockUntilShutdown()
        } catch (e: Exception) {
            logger.error(e) { "Failed, stopping sequencer and exiting" }
        } finally {
            gcStatsProvider.stop()
            sequencer.stop()
            queueProcessorApp.stop()
        }
    } catch (e: Throwable) {
        logger.error(e) { "Failed to start" }
        exitProcess(1)
    }
}
