package xyz.funkybit.apps.ring

import xyz.funkybit.core.blockchain.evm.EvmClient
import xyz.funkybit.core.sequencer.SequencerClient

class EvmChainWorker(
    evmClient: EvmClient,
    sequencerClient: SequencerClient,
) {
    private val evmContractsPublisher = EvmContractsPublisher(evmClient)
    private val evmBlockProcessor = EvmBlockProcessor(evmClient, sequencerClient)
    private val evmTransactionHandler = EvmTransactionHandler(evmClient, sequencerClient)
    private val evmDepositHandler = EvmDepositHandler(evmClient, sequencerClient)

    fun start() {
        evmContractsPublisher.updateContracts()
        evmBlockProcessor.start()
        evmTransactionHandler.start()
        evmDepositHandler.start()
    }

    fun stop() {
        evmDepositHandler.stop()
        evmTransactionHandler.stop()
        evmBlockProcessor.stop()
    }
}
