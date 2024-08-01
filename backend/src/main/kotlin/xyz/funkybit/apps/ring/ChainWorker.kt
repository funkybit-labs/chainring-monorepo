package xyz.funkybit.apps.ring

import xyz.funkybit.core.blockchain.BlockchainClient
import xyz.funkybit.core.sequencer.SequencerClient

class ChainWorker(
    blockchainClient: BlockchainClient,
    sequencerClient: SequencerClient,
) {
    private val contractsPublisher = ContractsPublisher(blockchainClient)

    private val blockProcessor = BlockProcessor(blockchainClient, sequencerClient)
    private val blockchainTransactionHandler =
        BlockchainTransactionHandler(blockchainClient, sequencerClient)
    private val blockchainDepositHandler =
        BlockchainDepositHandler(blockchainClient, sequencerClient)

    fun start() {
        contractsPublisher.updateContracts()
        blockProcessor.start()
        blockchainTransactionHandler.start()
        blockchainDepositHandler.start()
    }

    fun stop() {
        blockchainDepositHandler.stop()
        blockchainTransactionHandler.stop()
        blockProcessor.stop()
    }
}
