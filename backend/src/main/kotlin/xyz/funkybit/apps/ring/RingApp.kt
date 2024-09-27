package xyz.funkybit.apps.ring

import io.github.oshai.kotlinlogging.KotlinLogging
import xyz.funkybit.apps.api.BaseApp
import xyz.funkybit.core.blockchain.evm.EvmChainManager
import xyz.funkybit.core.db.DbConfig
import xyz.funkybit.core.db.migrations
import xyz.funkybit.core.db.upgrade
import xyz.funkybit.core.repeater.Repeater
import xyz.funkybit.core.sequencer.SequencerClient

data class RingAppConfig(
    val dbConfig: DbConfig = DbConfig(),
    val repeaterAutomaticTaskScheduling: Boolean = System.getenv("REPEATER_AUTOMATIC_TASK_SCHEDULING")?.toBoolean() ?: true,
)

class RingApp(config: RingAppConfig = RingAppConfig()) : BaseApp(config.dbConfig) {
    override val logger = KotlinLogging.logger {}
    private val evmClients = EvmChainManager.getEvmClients()
    private val sequencerClient = SequencerClient()

    private val evmChainWorkers = evmClients.map { EvmChainWorker(it, sequencerClient) }
    private val archChainWorker = ArchChainWorker(sequencerClient)
    private val settlementCoordinator = SettlementCoordinator(evmClients, sequencerClient)

    private val repeater = Repeater(db, config.repeaterAutomaticTaskScheduling)

    override fun start() {
        logger.info { "Starting" }
        super.start()

        db.upgrade(migrations, logger)

        evmChainWorkers.forEach(EvmChainWorker::start)
        archChainWorker.start()
        settlementCoordinator.start()
        repeater.start()

        logger.info { "Started" }
    }

    override fun stop() {
        logger.info { "Stopping" }

        repeater.stop()
        settlementCoordinator.stop()
        evmChainWorkers.forEach(EvmChainWorker::stop)
        archChainWorker.stop()

        super.stop()
        logger.info { "Stopped" }
    }
}
