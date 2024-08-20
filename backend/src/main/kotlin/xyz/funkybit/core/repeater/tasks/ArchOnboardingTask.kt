package xyz.funkybit.core.repeater.tasks

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.blockchain.bitcoin.ArchNetworkClient
import xyz.funkybit.core.blockchain.bitcoin.BitcoinClient
import xyz.funkybit.core.model.bitcoin.ProgramInstruction
import xyz.funkybit.core.model.bitcoin.SerializedBitcoinTx
import xyz.funkybit.core.model.bitcoin.UtxoId
import xyz.funkybit.core.model.db.ArchStateUtxoEntity
import xyz.funkybit.core.model.db.StateUtxoStatus
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.rpc.ArchNetworkRpc
import xyz.funkybit.core.services.UtxoSelectionService
import xyz.funkybit.core.utils.bitcoin.ArchUtils
import xyz.funkybit.core.utils.bitcoin.BitcoinInsufficientFundsException
import java.math.BigInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ArchOnboardingTask : RepeaterBaseTask(
    invokePeriod = 30.seconds,
) {
    val logger = KotlinLogging.logger {}

    override val name: String = "arch_onboarding"

    override fun runWithLock() {
        if (!BitcoinClient.bitcoinConfig.enabled) {
            return
        }

        // onboard the exchange state UTXO first
        val stateUtxoEntity = transaction {
            ArchStateUtxoEntity.findProgramStateUtxo() ?: initiateOnboarding(null)
        }

        stateUtxoEntity?.let { stateUtxo ->
            if (stateUtxo.status == StateUtxoStatus.Onboarding) {
                transaction { checkIfOnboarded(stateUtxo) }
            }
            if (stateUtxo.status == StateUtxoStatus.Onboarded) {
                transaction { initializeStateUtxo(stateUtxo) }
            }
            if (stateUtxo.status == StateUtxoStatus.Initializing) {
                stateUtxo.initializationTxId?.let { archTxId ->
                    ArchNetworkClient.getProcessedTransaction(archTxId)?.let {
                        when (it.status) {
                            ArchNetworkRpc.Status.Success -> {
                                transaction {
                                    ArchUtils.refreshStateUtxoIds(it)
                                    stateUtxo.markAsComplete()
                                }
                                logger.debug { "Completed initialization for State Utxo ${stateUtxo.utxoId}" }
                            }
                            ArchNetworkRpc.Status.Failed -> {
                                logger.error { "Initializing State Utxo failed for ${stateUtxo.utxoId}" }
                                transaction { stateUtxo.markAsFailed() }
                            }
                            ArchNetworkRpc.Status.Processing -> {
                                logger.debug { "Processing initialization for State Utxo ${stateUtxo.utxoId}" }
                            }
                        }
                    }
                }
            }
            if (stateUtxo.status == StateUtxoStatus.Complete) {
                // onboard symbol utxos
                findSymbolsWithNoStateUtxo().forEach {
                    transaction { initiateOnboarding(it) }
                }
                transaction {
                    ArchStateUtxoEntity.findAllTokenStateUtxo().forEach {
                        if (it.status == StateUtxoStatus.Onboarding) {
                            checkIfOnboarded(it)
                        }
                        if (it.status == StateUtxoStatus.Onboarded) {
                            it.markAsComplete()
                        }
                    }
                }
            }
        }
    }

    private fun findSymbolsWithNoStateUtxo(): List<SymbolEntity> {
        return transaction {
            val allSymbols = SymbolEntity.forChain(BitcoinClient.chainId)
            val symbolsWithTokenState = ArchStateUtxoEntity.findAllTokenStateUtxo().map { it.symbolGuid }.toSet()
            allSymbols.filterNot { symbolsWithTokenState.contains(it.guid) }
        }
    }

    private fun initiateOnboarding(symbolEntity: SymbolEntity? = null): ArchStateUtxoEntity? {
        val config = BitcoinClient.bitcoinConfig

        val archNetworkAddress = ArchNetworkClient.getNetworkAddress()
        val stateUtxoAmount = BigInteger("1500")

        return try {
            val selectedUtxos = UtxoSelectionService.selectUtxos(
                config.submitterAddress,
                stateUtxoAmount,
                ArchUtils.calculateFee(ArchUtils.P2WPKH_2IN_2OUT_VSIZE),
            )
            val recalculatedFee = ArchUtils.estimateOnboardingTxFee(
                config.submitterEcKey,
                archNetworkAddress,
                config.submitterAddress,
                selectedUtxos,
            )

            val onboardingTx = ArchUtils.buildOnboardingTx(
                config.submitterEcKey,
                archNetworkAddress,
                config.submitterXOnlyPublicKey,
                stateUtxoAmount,
                config.submitterAddress,
                recalculatedFee,
                selectedUtxos,
            )

            val txId = BitcoinClient.sendRawTransaction(onboardingTx.toHexString())
            UtxoSelectionService.reserveUtxos(config.submitterAddress, selectedUtxos.map { it.utxoId }.toSet(), txId.value)
            ArchStateUtxoEntity.create(UtxoId.fromTxHashAndVout(txId, 1), txId, symbolEntity)
        } catch (e: Exception) {
            logger.warn(e) { "Unable to onboard state UTXO: ${e.message}" }
            null
        }
    }

    private fun initializeStateUtxo(stateUtxo: ArchStateUtxoEntity) {
        try {
            ArchUtils.signAndSendInstruction(
                listOf(stateUtxo.utxoId),
                ProgramInstruction.InitStateParams(
                    feeAccount = BitcoinClient.bitcoinConfig.feeAccountAddress,
                    txHex = SerializedBitcoinTx.empty(),
                ),
            ).also {
                stateUtxo.markAsInitializing(it)
            }
        } catch (e: BitcoinInsufficientFundsException) {
            logger.warn { "Insufficient utxos available for fee" }
        }
    }

    private fun checkIfOnboarded(stateUtxo: ArchStateUtxoEntity) {
        try {
            ArchNetworkClient.readUtxo(stateUtxo.utxoId)
            stateUtxo.markAsOnboarded()
        } catch (e: Exception) {
            logger.warn { "Utxo ${stateUtxo.utxoId} not onboarded" }
            // mark as failed and raise an alert if not onboarded after 1 hour
            if (stateUtxo.createdAt.plus(60.minutes) < Clock.System.now()) {
                logger.error { "Timed out trying to onboard utxo ${stateUtxo.utxoId}" }
                stateUtxo.markAsFailed()
            }
        }
    }
}
