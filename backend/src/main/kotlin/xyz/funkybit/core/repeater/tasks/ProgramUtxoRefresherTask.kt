package xyz.funkybit.core.repeater.tasks

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.blockchain.bitcoin.bitcoinConfig
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.db.DeployedSmartContractEntity
import xyz.funkybit.core.services.UtxoManager
import xyz.funkybit.core.utils.HttpClient
import kotlin.time.Duration.Companion.seconds

class ProgramUtxoRefresherTask : RepeaterBaseTask(
    invokePeriod = 30.seconds,
) {
    val logger = KotlinLogging.logger {}

    override val name: String = "program_utxo_refresher"

    override fun runWithLock() {
        try {
            logger.info { "Refreshing program UTXOs" }
            HttpClient.setQuietModeForThread(true)

            transaction {
                DeployedSmartContractEntity.validContracts(bitcoinConfig.chainId).firstOrNull()?.let {
                    UtxoManager.refreshUtxos(it.proxyAddress as BitcoinAddress)
                }
            }
        } finally {
            HttpClient.setQuietModeForThread(false)
        }
    }
}
