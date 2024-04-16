package co.chainring

import co.chainring.apps.api.ApiApp
import co.chainring.core.sequencer.SequencerClient
import co.chainring.tasks.fixtures.localDevFixtures
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
            val symbolContractAddresses = seedBlockchain(
                fixtures = localDevFixtures,
                chainRpcUrl = "http://localhost:8545",
                privateKey = "0x92db14e403b83dfe3df233f83dfa3a0d7096f21ca9b0d6d6b8d88b2b4ec1564e"
            )
            seedDatabase(
                fixtures = localDevFixtures,
                symbolContractAddresses
            )
            seedSequencer(
                fixtures = localDevFixtures,
                sequencerClient = SequencerClient()
            )
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


