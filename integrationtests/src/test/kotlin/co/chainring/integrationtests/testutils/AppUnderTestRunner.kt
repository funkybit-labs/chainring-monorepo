package co.chainring.integrationtests.testutils

import co.chainring.apps.api.ApiApp
import co.chainring.apps.api.ApiAppConfig
import co.chainring.core.blockchain.BlockchainClientConfig
import co.chainring.core.blockchain.ContractType
import co.chainring.core.db.DbConfig
import co.chainring.core.model.db.DeployedSmartContractEntity
import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.model.db.WithdrawalStatus
import co.chainring.sequencer.apps.GatewayApp
import co.chainring.sequencer.apps.GatewayConfig
import co.chainring.sequencer.apps.SequencerApp
import co.chainring.tasks.fixtures.localDevFixtures
import co.chainring.tasks.seedDatabase
import io.github.oshai.kotlinlogging.KotlinLogging
import org.awaitility.kotlin.await
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource
import java.lang.System.getenv
import java.time.Duration

// This extension allows us to start the app under test only once
class AppUnderTestRunner : BeforeAllCallback {
    override fun beforeAll(context: ExtensionContext) {
        context
            .root
            .getStore(ExtensionContext.Namespace.GLOBAL)
            .getOrComputeIfAbsent("app under test") {
                object : CloseableResource {
                    val logger = KotlinLogging.logger {}
                    private val dbConfig = DbConfig()
                    private val apiApp = ApiApp(ApiAppConfig(httpPort = 9999, dbConfig = dbConfig))
                    private val gatewayApp = GatewayApp(GatewayConfig(port = 5337))
                    private val sequencerApp = SequencerApp(
                        // we want sequencer to start from the clean-slate
                        checkpointsPath = null,
                    )
                    private val blockchainClient = TestBlockchainClient(BlockchainClientConfig())

                    private val isIntegrationRun = (getenv("INTEGRATION_RUN") ?: "0") == "1"

                    init {
                        if (!isIntegrationRun) {
                            apiApp.startServer()
                            transaction {
                                DeployedSmartContractEntity.findLastDeployedContractByNameAndChain(ContractType.Exchange.name, blockchainClient.chainId)?.deprecated = true
                                WithdrawalEntity.findPending().forEach { it.update(WithdrawalStatus.Failed, "restarting test") }
                            }
                            apiApp.updateContracts()
                            sequencerApp.start()
                            gatewayApp.start()
                        }
                        // wait for contracts to load
                        await
                            .pollInSameThread()
                            .pollDelay(Duration.ofMillis(100))
                            .pollInterval(Duration.ofMillis(100))
                            .atMost(Duration.ofMillis(30000L))
                            .until {
                                transaction { DeployedSmartContractEntity.validContracts(blockchainClient.chainId).map { it.name } == listOf(ContractType.Exchange.name) }
                            }

                        seedDatabase(localDevFixtures, blockchainClient.config.url, blockchainClient.config.privateKeyHex)
                    }

                    @Throws(Throwable::class)
                    override fun close() {
                        if (!isIntegrationRun) {
                            apiApp.stop()
                            sequencerApp.stop()
                            gatewayApp.stop()
                        }
                    }
                }
            }
    }
}
