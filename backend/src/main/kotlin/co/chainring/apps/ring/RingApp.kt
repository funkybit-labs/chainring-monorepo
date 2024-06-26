package co.chainring.apps.ring

import co.chainring.apps.BaseApp
import co.chainring.core.blockchain.BlockchainDepositHandler
import co.chainring.core.blockchain.BlockchainTransactionHandler
import co.chainring.core.blockchain.ChainManager
import co.chainring.core.blockchain.ContractsPublisher
import co.chainring.core.blockchain.SettlementCoordinator
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

    private val contractsPublishers = ChainManager.getBlockchainClients().map { ContractsPublisher(it) }

    private val sequencerClient = SequencerClient()
    private val settlementCoordinator = SettlementCoordinator(
        ChainManager.getBlockchainClients(),
        sequencerClient,
    )
    private val blockchainTransactionHandlers = ChainManager.getBlockchainClients().map { BlockchainTransactionHandler(it, sequencerClient) }
    private val blockchainDepositHandlers = ChainManager.getBlockchainClients().map { BlockchainDepositHandler(it, sequencerClient) }
    private val repeaterApp = Repeater(db)
    override fun start() {
        logger.info { "Starting" }
        super.start()
        db.upgrade(migrations, logger)
        contractsPublishers.forEach {
            it.updateContracts()
        }
        blockchainTransactionHandlers.forEach {
            it.start()
        }
        blockchainDepositHandlers.forEach {
            it.start()
        }
        settlementCoordinator.start()
        repeaterApp.start()
        logger.info { "Started" }
    }

    override fun stop() {
        logger.info { "Stopping" }
        repeaterApp.stop()
        settlementCoordinator.stop()
        blockchainDepositHandlers.forEach {
            it.stop()
        }
        blockchainTransactionHandlers.forEach {
            it.stop()
        }
        super.stop()
        logger.info { "Stopped" }
    }
}
