package co.chainring.integrationtests.testutils

import co.chainring.apps.api.ApiApp
import co.chainring.apps.api.ApiAppConfig
import co.chainring.apps.api.TestRoutes
import co.chainring.core.blockchain.BlockchainClientConfig
import co.chainring.core.blockchain.ContractType
import co.chainring.core.db.DbConfig
import co.chainring.core.model.db.BalanceLogTable
import co.chainring.core.model.db.BalanceTable
import co.chainring.core.model.db.BlockchainTransactionTable
import co.chainring.core.model.db.DeployedSmartContractEntity
import co.chainring.core.model.db.DepositTable
import co.chainring.core.model.db.ExchangeTransactionTable
import co.chainring.core.model.db.OrderExecutionTable
import co.chainring.core.model.db.OrderTable
import co.chainring.core.model.db.TradeTable
import co.chainring.core.model.db.WalletTable
import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.model.db.WithdrawalStatus
import co.chainring.core.model.db.WithdrawalTable
import co.chainring.sequencer.apps.GatewayApp
import co.chainring.sequencer.apps.GatewayConfig
import co.chainring.sequencer.apps.SequencerApp
import co.chainring.tasks.fixtures.localDevFixtures
import co.chainring.tasks.migrateDatabase
import co.chainring.tasks.seedBlockchain
import co.chainring.tasks.seedDatabase
import io.github.oshai.kotlinlogging.KotlinLogging
import org.awaitility.kotlin.await
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource
import java.lang.System.getenv
import java.time.Duration

// This extension allows us to start the app under test only once
class AppUnderTestRunner : BeforeAllCallback, BeforeEachCallback {
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
                        inSandboxMode = true,
                    )
                    private val blockchainClient = TestBlockchainClient(BlockchainClientConfig())

                    private val isIntegrationRun = (getenv("INTEGRATION_RUN") ?: "0") == "1"

                    init {
                        migrateDatabase()

                        // activate auto mining to speeding up blockchain seeding
                        blockchainClient.setAutoMining(true)

                        if (!isIntegrationRun) {
                            sequencerApp.start()
                            gatewayApp.start()
                            transaction {
                                DeployedSmartContractEntity.findLastDeployedContractByNameAndChain(ContractType.Exchange.name, blockchainClient.chainId)?.deprecated = true
                                WithdrawalEntity.findPending().forEach { it.update(WithdrawalStatus.Failed, "restarting test") }
                            }
                            apiApp.start()
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

                        val symbolContractAddresses = seedBlockchain(localDevFixtures, blockchainClient.config.url, blockchainClient.config.privateKeyHex)
                        seedDatabase(localDevFixtures, symbolContractAddresses)

                        // during tests block will be mined manually
                        blockchainClient.setAutoMining(false)
                    }

                    @Throws(Throwable::class)
                    override fun close() {
                        if (!isIntegrationRun) {
                            apiApp.stop()
                            sequencerApp.stop()
                            gatewayApp.stop()
                        }

                        // revert back to interval mining for `test` env to work normally
                        blockchainClient.setIntervalMining()
                    }
                }
            }
    }

    override fun beforeEach(context: ExtensionContext) {
        ApiClient.resetSequencer()

        localDevFixtures.markets.forEach { market ->
            val baseSymbol = localDevFixtures.symbols.first { it.id == market.baseSymbol }
            val quoteSymbol = localDevFixtures.symbols.first { it.id == market.quoteSymbol }

            ApiClient.createMarketInSequencer(
                TestRoutes.Companion.CreateMarketInSequencer(
                    id = "${baseSymbol.name}/${quoteSymbol.name}",
                    tickSize = market.tickSize,
                    marketPrice = market.marketPrice,
                    quoteDecimals = quoteSymbol.decimals,
                    baseDecimals = baseSymbol.decimals,
                ),
            )
        }

        transaction {
            OrderExecutionTable.deleteAll()
            TradeTable.deleteAll()
            OrderTable.deleteAll()
            BalanceLogTable.deleteAll()
            BalanceTable.deleteAll()
            DepositTable.deleteAll()
            WithdrawalTable.deleteAll()
            WalletTable.deleteAll()
            ExchangeTransactionTable.deleteAll()
            BlockchainTransactionTable.deleteAll()
        }
    }
}
