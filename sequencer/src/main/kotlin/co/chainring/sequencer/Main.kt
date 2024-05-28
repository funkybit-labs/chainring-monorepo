package co.chainring.sequencer

import co.chainring.sequencer.apps.GatewayApp
import co.chainring.sequencer.apps.SequencerApp
import co.chainring.sequencer.apps.SequencerResponseProcessorApp
import co.chainring.sequencer.core.queueHome
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) {
    logger.info { "Starting with args: ${args.joinToString(" ")}" }

    try {
        val sequencer = SequencerApp(
            checkpointsPath = if (System.getenv("CHECKPOINTS_ENABLED").toBoolean()) {
                Path.of(queueHome, "checkpoints")
            } else {
                null
            },
        )
        val gateway = GatewayApp()
        val sequencerResponseProcessorApp = SequencerResponseProcessorApp(
            sequencer.inputQueue,
            sequencer.outputQueue,
            onAbnormalStop = {
                gateway.stop()
                sequencer.stop()
            },
        )

        try {
            sequencer.start()
            sequencerResponseProcessorApp.start()
            gateway.start()
            gateway.blockUntilShutdown()
        } catch (e: Exception) {
            logger.error(e) { "Failed, stopping sequencer and exiting" }
        } finally {
            sequencer.stop()
            sequencerResponseProcessorApp.stop()
        }
    } catch (e: Throwable) {
        logger.error(e) { "Failed to start" }
        exitProcess(1)
    }
}
