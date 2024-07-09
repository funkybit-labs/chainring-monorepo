package co.chainring.apps.ring

import co.chainring.apps.BaseApp
import co.chainring.core.blockchain.ChainManager
import co.chainring.core.db.DbConfig
import co.chainring.core.db.migrations
import co.chainring.core.db.upgrade
import co.chainring.core.repeater.Repeater
import co.chainring.core.sequencer.SequencerClient
import io.github.oshai.kotlinlogging.KotlinLogging

data class RingAppConfig(
    val dbConfig: DbConfig = DbConfig(),
)

class RingApp(config: RingAppConfig = RingAppConfig()) : BaseApp(config.dbConfig) {
    override val logger = KotlinLogging.logger {}
    private val blockchainClients = ChainManager.getBlockchainClients()
    private val sequencerClient = SequencerClient()

    private val chainWorkers = blockchainClients.map { ChainWorker(it, sequencerClient) }
    private val settlementCoordinator = SettlementCoordinator(blockchainClients, sequencerClient)

    private val repeater = Repeater(db)

    override fun start() {
        logger.info { "Starting" }
        super.start()

        db.upgrade(migrations, logger)

        chainWorkers.forEach(ChainWorker::start)
        settlementCoordinator.start()
        repeater.start()

        logger.info { "Started" }
    }

    override fun stop() {
        logger.info { "Stopping" }

        repeater.stop()
        settlementCoordinator.stop()
        chainWorkers.forEach(ChainWorker::stop)

        super.stop()
        logger.info { "Stopped" }
    }
}
