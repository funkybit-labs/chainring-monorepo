package co.chainring.apps.ring

import co.chainring.core.blockchain.BlockchainClient
import co.chainring.core.sequencer.SequencerClient

class ChainWorker(
    blockchainClient: BlockchainClient,
    sequencerClient: SequencerClient,
) {
    private val contractsPublisher = ContractsPublisher(blockchainClient)

//    private val blockProcessor = BlockProcessor(blockchainClient)
    private val blockchainTransactionHandler =
        BlockchainTransactionHandler(blockchainClient, sequencerClient)
    private val blockchainDepositHandler =
        BlockchainDepositHandler(blockchainClient, sequencerClient)

    fun start() {
        contractsPublisher.updateContracts()
//        blockProcessor.start()
        blockchainTransactionHandler.start()
        blockchainDepositHandler.start()
    }

    fun stop() {
        blockchainDepositHandler.stop()
        blockchainTransactionHandler.stop()
//        blockProcessor.stop()
    }
}
