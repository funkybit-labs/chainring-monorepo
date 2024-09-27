package xyz.funkybit.apps.ring

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.blockchain.bitcoin.bitcoinConfig
import xyz.funkybit.core.model.db.BitcoinUtxoAddressMonitorEntity
import xyz.funkybit.core.sequencer.SequencerClient
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

class ArchChainWorker(
    sequencerClient: SequencerClient,
) {
    val logger = KotlinLogging.logger {}

    private val depositHandler = BitcoinDepositHandler()
    private val blockchainTransactionHandler = ArchTransactionHandler(sequencerClient)
    private var contractDeployerThread: Thread? = null

    fun start() {
        if (bitcoinConfig.enabled) {
            transaction { BitcoinUtxoAddressMonitorEntity.createIfNotExists(bitcoinConfig.feePayerAddress) }
            startContractDeployer()
        }
    }

    private fun startContractDeployer() {
        contractDeployerThread = thread(start = false, name = "arch_contract_deployer", isDaemon = false) {
            ArchContractsPublisher.updateContracts()
            depositHandler.start()
            blockchainTransactionHandler.start()
        }.also { thread ->
            thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, throwable ->
                logger.error(throwable) { "Failed to load bitcoin contract" }
                Thread.sleep(5.seconds.inWholeMilliseconds)
                startContractDeployer()
            }
            thread.start()
        }
    }

    fun stop() {
        depositHandler.stop()
        blockchainTransactionHandler.stop()
        contractDeployerThread?.also {
            it.interrupt()
            it.join()
        }
    }
}
