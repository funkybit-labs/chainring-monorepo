package co.chainring.sequencer

import co.chainring.sequencer.apps.GatewayApp
import co.chainring.sequencer.apps.SequencerApp
import co.chainring.sequencer.apps.SequencerResponseProcessorApp
import co.chainring.sequencer.core.checkpointsQueue
import co.chainring.sequencer.core.inputQueue
import co.chainring.sequencer.core.outputQueue
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

enum class Mode {
    All,
    Sequencer,
    Gateway,
    ResponseProcessor,
    NotSequencer,
    None,
}

fun main(args: Array<String>) {
    val mode = when {
        args.isEmpty() -> Mode.All
        else -> when (args.first()) {
            "sequencer" -> Mode.Sequencer
            "gateway" -> Mode.Gateway
            "response-processor" -> Mode.ResponseProcessor
            "not-sequencer" -> Mode.NotSequencer
            else -> Mode.None
        }
    }
    logger.info { "Starting in mode $mode" }

    try {
        val sequencer = when (mode) {
            Mode.All, Mode.Sequencer -> SequencerApp(
                checkpointsQueue = if (System.getenv("CHECKPOINTS_ENABLED").toBoolean()) {
                    checkpointsQueue
                } else {
                    null
                },
            )
            else -> null
        }
        val gateway = when (mode) {
            Mode.All, Mode.Gateway, Mode.NotSequencer -> GatewayApp()
            else -> null
        }
        val sequencerResponseProcessorApp = when (mode) {
            Mode.All, Mode.ResponseProcessor, Mode.NotSequencer ->
                SequencerResponseProcessorApp(
                    sequencer?.inputQueue ?: inputQueue,
                    sequencer?.outputQueue ?: outputQueue,
                    onAbnormalStop = {
                        gateway?.stop()
                        sequencer?.stop()
                    },
                )
            else -> null
        }

        try {
            sequencer?.start()
            sequencerResponseProcessorApp?.start()
            gateway?.start()
            gateway?.blockUntilShutdown()
            sequencerResponseProcessorApp?.blockUntilShutdown()
            sequencer?.blockUntilShutdown()
        } catch (e: Exception) {
            logger.error(e) { "Failed, stopping sequencer and exiting" }
        } finally {
            sequencer?.stop()
            sequencerResponseProcessorApp?.stop()
        }
    } catch (e: Throwable) {
        logger.error(e) { "Failed to start" }
        exitProcess(1)
    }
}
