package co.chainring.apps.api

import co.chainring.apps.BaseApp
import co.chainring.apps.api.middleware.HttpTransactionLogger
import co.chainring.apps.api.middleware.RequestProcessingExceptionHandler
import co.chainring.core.blockchain.BlockchainClient
import co.chainring.core.blockchain.BlockchainClientConfig
import co.chainring.core.db.DbConfig
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
import java.time.Duration.ofSeconds

data class ApiAppConfig(
    val httpPort: Int = System.getenv("HTTP_PORT")?.toIntOrNull() ?: 9000,
    val dbConfig: DbConfig = DbConfig(),
    val blockchainClientConfig: BlockchainClientConfig = BlockchainClientConfig(),
)

val requestContexts = RequestContexts()

class ApiApp(config: ApiAppConfig = ApiAppConfig()) : BaseApp(config.dbConfig) {
    override val logger = KotlinLogging.logger {}

    private val corsPolicy = CorsPolicy(
        originPolicy = OriginPolicy.AllowAll(),
        headers = listOf(),
        methods = listOf(Method.GET, Method.POST),
    )

    private val server = ServerFilters.InitialiseRequestContext(requestContexts)
        .then(ServerFilters.Cors(corsPolicy))
        .then(HttpTransactionLogger(logger))
        .then(RequestProcessingExceptionHandler(logger))
        .then(
            routes(
                "/health" bind Method.GET to { Response(Status.OK) },
                "/v1" bind
                    contract {
                        routes +=
                            listOf(
                                ConfigRoutes.getConfiguration(),
                                OrderRoutes.createOrder(),
                                OrderRoutes.updateOrder(),
                                OrderRoutes.cancelOrder(),
                                OrderRoutes.getOrder(),
                                OrderRoutes.listOrders(),
                                OrderRoutes.cancelOpenOrders(),
                                OrderRoutes.batchOrders(),
                                OrderRoutes.listTrades(),
                                BalanceRoutes.getBalances(),

                                // http api + websocket
                                // GET /v1/market/market_id/order-book
                                // GET /v1/market/market_id/transactions
                            )
                    },
            ),
        )
        .asServer(Netty(config.httpPort, ServerConfig.StopMode.Graceful(ofSeconds(1))))

    private val blockchainClient = BlockchainClient(config.blockchainClientConfig)

    override fun start() {
        logger.info { "Starting" }
        super.start()
        server.start()
        blockchainClient.updateContracts()
        logger.info { "Started" }
    }

    override fun stop() {
        logger.info { "Stopping" }
        super.stop()
        server.stop()
        logger.info { "Stopped" }
    }
}
