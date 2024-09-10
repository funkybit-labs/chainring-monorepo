package xyz.funkybit.apps.ring

import io.github.oshai.kotlinlogging.KotlinLogging
import xyz.funkybit.core.blockchain.bitcoin.BitcoinClient
import xyz.funkybit.core.sequencer.SequencerClient
import kotlin.concurrent.thread

class ArchChainWorker(
    sequencerClient: SequencerClient,
) {
    val logger = KotlinLogging.logger {}

    private val depositHandler = BitcoinDepositHandler()
    private val blockchainTransactionHandler = ArchTransactionHandler(sequencerClient)

    fun start() {
        if (BitcoinClient.bitcoinConfig.enabled) {
            val contractDeployerThread = thread(start = false, name = "arch_contract_deployer", isDaemon = false) {
                ArchContractsPublisher.updateContracts()
                depositHandler.start()
                blockchainTransactionHandler.start()
            }

            contractDeployerThread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, throwable ->
                logger.error(throwable) { "Failed to load bitcoin contract" }
                stop()
            }
            contractDeployerThread.start()
        }
    }

    fun stop() {
        depositHandler.stop()
        blockchainTransactionHandler.stop()
    }
}
