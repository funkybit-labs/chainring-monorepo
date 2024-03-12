package co.chainring.apps.api

import co.chainring.apps.BaseApp
import co.chainring.apps.api.ConfigRoutes.CONTRACT_ADDRESS_KEY
import co.chainring.apps.api.middleware.HttpTransactionLogger
import co.chainring.apps.api.middleware.RequestProcessingExceptionHandler
import co.chainring.core.blockchain.BlockchainClient
import co.chainring.core.db.DbConfig
import co.chainring.core.model.db.KeyValueStore
import io.github.oshai.kotlinlogging.KotlinLogging
import org.http4k.contract.contract
import org.http4k.core.Method
import org.http4k.core.RequestContexts
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.filter.AllowAll
import org.http4k.filter.CorsPolicy
import org.http4k.filter.OriginPolicy
import org.http4k.filter.ServerFilters
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.server.Netty
import org.http4k.server.ServerConfig
import org.http4k.server.asServer
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Duration.ofSeconds

data class ApiAppConfig(
    val httpPort: Int = System.getenv("HTTP_PORT")?.toIntOrNull() ?: 9000,
    val db: DbConfig = DbConfig(),
)

val requestContexts = RequestContexts()

class ApiApp(config: ApiAppConfig = ApiAppConfig()) : BaseApp(config.db) {
    override val logger = KotlinLogging.logger {}

    private val server = ServerFilters.InitialiseRequestContext(requestContexts)
        .then(HttpTransactionLogger(logger))
        .then(RequestProcessingExceptionHandler(logger))
        .then(
            routes(
                "/health" bind Method.GET to { Response(Status.OK) },
                "/v1" bind contract {
                    routes += listOf(
                        ConfigRoutes.getConfiguration(),
                    )
                },
            ),
        )
        .asServer(Netty(config.httpPort, ServerConfig.StopMode.Graceful(ofSeconds(1))))

    override fun start() {
        logger.info { "Starting" }
        super.start()
        server.start()
        logger.info { "Started" }

        logger.info { "Deploying chainring contract" }
        val address = BlockchainClient().deployChainringContract()

        transaction {
            KeyValueStore.setValue(CONTRACT_ADDRESS_KEY, address)
        }

        logger.info { "Chainring contract deployed" }
    }

    override fun stop() {
        logger.info { "Stopping" }
        super.stop()
        server.stop()
        logger.info { "Stopped" }
    }
}
