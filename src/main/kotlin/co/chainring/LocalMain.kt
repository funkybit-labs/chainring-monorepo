package co.chainring

import co.chainring.apps.api.ApiApp
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

fun main(@Suppress("UNUSED_PARAMETER") args: Array<String>) {
    logger.info { "Starting all apps" }

    try {
        ApiApp().start()
    } catch (e: Throwable) {
        logger.error(e) { "Failed to start" }
        exitProcess(1)
    }
}
