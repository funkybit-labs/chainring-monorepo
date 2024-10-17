package xyz.funkybit

import io.github.oshai.kotlinlogging.KotlinLogging
import xyz.funkybit.apps.api.ApiApp
import xyz.funkybit.apps.ring.RingApp
import xyz.funkybit.apps.telegrambot.BotApp
import xyz.funkybit.core.telemetry.SentryUtils
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) {
    logger.info { "Starting with args: ${args.joinToString(" ")}" }

    SentryUtils.init()

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
