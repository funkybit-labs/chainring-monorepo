package co.chainring

import co.chainring.apps.api.ApiApp
import co.chainring.apps.ring.RingApp
import co.chainring.apps.telegrambot.BotApp
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) {
    logger.info { "Starting with args: ${args.joinToString(" ")}" }

    try {
        val appName = args.firstOrNull()
        when (appName) {
            "api" -> ApiApp().start()
            "ring" -> RingApp().start()
            "telegrambot" -> BotApp().start()
            else -> {
                RingApp().start()
                ApiApp().start()
                BotApp().start()
            }
        }
    } catch (e: Throwable) {
        logger.error(e) { "Failed to start" }
        exitProcess(1)
    }
}
