package xyz.funkybit.core.repeater.tasks

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.blockchain.bitcoin.ArchNetworkClient
import xyz.funkybit.core.blockchain.bitcoin.BitcoinClient
import xyz.funkybit.core.model.UtxoId
import xyz.funkybit.core.model.db.ArchStateUtxoEntity
import xyz.funkybit.core.model.db.StateUtxoStatus
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.services.UtxoSelectionService
import xyz.funkybit.core.utils.bitcoin.ArchUtils
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
            ArchStateUtxoEntity.findExchangeStateUtxo() ?: initiateOnboarding(null)
        }

        stateUtxoEntity?.let { stateUtxo ->
            if (stateUtxo.status == StateUtxoStatus.Onboarding) {
                transaction { checkIfOnboarded(stateUtxo) }
            }
            if (stateUtxo.status == StateUtxoStatus.Onboarded) {
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
        // we almost always just need 1 utxo, but the 263 estimate assumes 2 ins, so we overestimate the
        // fee given to the utxo selection algorithm. We will recalculate once we get the number of utxos returned.
        val feeAmount = ArchUtils.calculateFee(263)

        return try {
            val selectedUtxos = UtxoSelectionService.selectUtxos(config.submitterAddress, stateUtxoAmount, feeAmount)
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