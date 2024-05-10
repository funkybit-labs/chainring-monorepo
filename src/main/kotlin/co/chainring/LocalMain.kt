package co.chainring

import co.chainring.apps.api.ApiApp
import co.chainring.tasks.blockchainClient
import co.chainring.tasks.fixtures.getFixtures
import co.chainring.tasks.migrateDatabase
import co.chainring.tasks.seedBlockchain
import co.chainring.tasks.seedDatabase
import co.chainring.tasks.seedSequencer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val logger = KotlinLogging.logger {}

    when (args.firstOrNull()) {
        "db:migrate" -> migrateDatabase()
        "db:seed" -> {
            val fixtures = getFixtures(blockchainClient.chainId)
            val symbolContractAddresses = seedBlockchain(fixtures)
            seedDatabase(fixtures, symbolContractAddresses)
            seedSequencer(fixtures)
        }
        else -> {
            logger.info { "Starting all apps" }

            try {
                ApiApp().start()
            } catch (e: Throwable) {
                logger.error(e) { "Failed to start" }
                exitProcess(1)
            }
        }
    }
}


