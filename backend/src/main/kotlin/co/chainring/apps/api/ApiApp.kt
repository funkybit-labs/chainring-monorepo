package co.chainring.apps.api

import co.chainring.apps.BaseApp
import co.chainring.apps.api.middleware.HttpTransactionLogger
import co.chainring.apps.api.middleware.RequestProcessingExceptionHandler
import co.chainring.apps.api.middleware.Tracer
import co.chainring.apps.api.services.ExchangeApiService
import co.chainring.core.blockchain.ChainManager
import co.chainring.core.db.DbConfig
import co.chainring.core.sequencer.SequencerClient
import co.chainring.core.websocket.Broadcaster
import io.github.oshai.kotlinlogging.KotlinLogging
import org.http4k.contract.contract
import org.http4k.contract.openapi.ApiInfo
import org.http4k.contract.openapi.v3.ApiServer
import org.http4k.contract.openapi.v3.OpenApi3
import org.http4k.core.Method
import org.http4k.core.RequestContexts
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.Uri
import org.http4k.core.then
import org.http4k.filter.AllowAll
import org.http4k.filter.CorsPolicy
import org.http4k.filter.OriginPolicy
import org.http4k.filter.ServerFilters
import org.http4k.filter.ZipkinTraces
import org.http4k.format.Argo
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.routing.websockets
import org.http4k.server.Netty
import org.http4k.server.PolyHandler
import org.http4k.server.ServerConfig
import org.http4k.server.asServer
import org.slf4j.MDC
import java.time.Duration.ofSeconds

data class ApiAppConfig(
    val httpPort: Int = System.getenv("HTTP_PORT")?.toIntOrNull() ?: 9000,
    val dbConfig: DbConfig = DbConfig(),
)

val requestContexts = RequestContexts()

enum class FaucetMode {
    Off,
    OnlyNative,
    OnlyERC20,
    AllSymbols,
}

class ApiApp(config: ApiAppConfig = ApiAppConfig()) : BaseApp(config.dbConfig) {
    override val logger = KotlinLogging.logger {}

    private val corsPolicy = CorsPolicy(
        originPolicy = OriginPolicy.AllowAll(),
        headers = listOf("Content-Type", "Authorization"),
        methods = listOf(
            Method.DELETE,
            Method.GET,
            Method.PATCH,
            Method.POST,
            Method.PUT,
        ),
    )

    private val enableTestRoutes = System.getenv("ENABLE_TEST_ROUTES")?.toBoolean() ?: true
    private val enableAdminRoutes = System.getenv("ENABLE_ADMIN_ROUTES")?.toBoolean() ?: true
    private val faucetMode = System.getenv("FAUCET_MODE")
        ?.let { FaucetMode.valueOf(it) }
        ?: FaucetMode.AllSymbols

    private val sequencerClient = SequencerClient()
    private val broadcaster = Broadcaster(db)

    private val exchangeApiService = ExchangeApiService(sequencerClient)

    private val configRoutes = ConfigRoutes(faucetMode)
    private val depositRoutes = DepositRoutes(exchangeApiService)
    private val withdrawalRoutes = WithdrawalRoutes(exchangeApiService)
    private val balanceRoutes = BalanceRoutes()
    private val orderRoutes = OrderRoutes(exchangeApiService)
    private val faucetRoutes = FaucetRoutes(faucetMode, ChainManager.getBlockchainClients())

    private val httpHandler = ServerFilters.InitialiseRequestContext(requestContexts)
        .then(ServerFilters.Cors(corsPolicy))
        .then(
            ServerFilters.RequestTracing(
                startReportFn = { _, z: ZipkinTraces ->
                    MDC.put("traceId", z.traceId.value)
                },
                endReportFn = { _, _, _ ->
                    MDC.remove("traceId")
                    Tracer.reset()
                },
            ),
        )
        .then(HttpTransactionLogger())
        .then(RequestProcessingExceptionHandler(logger))
        .then(
            routes(
                "/health" bind Method.GET to { Response(Status.OK) },
                "/v1" bind
                    contract {
                        renderer = OpenApi3(
                            ApiInfo("ChainRing API", "0.1.0"),
                            Argo,
                            servers = listOf(ApiServer(Uri.of("https://api.chainring.finance"))),
                        )
                        descriptionPath = "/openapi.json"
                        routes += listOf(
                            configRoutes.getConfiguration,
                            configRoutes.getAccountConfiguration,
                            configRoutes.markSymbolAsAdded,
                            orderRoutes.createOrder(),
                            orderRoutes.updateOrder(),
                            orderRoutes.cancelOrder(),
                            orderRoutes.getOrder(),
                            orderRoutes.listOrders(),
                            orderRoutes.cancelOpenOrders(),
                            orderRoutes.batchOrders(),
                            orderRoutes.listTrades(),
                            balanceRoutes.getBalances(),
                            depositRoutes.getDeposit,
                            depositRoutes.createDeposit,
                            depositRoutes.listDeposits,
                            withdrawalRoutes.getWithdrawal,
                            withdrawalRoutes.createWithdrawal,
                            withdrawalRoutes.listWithdrawals,
                        )

                        if (enableTestRoutes) {
                            routes += TestRoutes(sequencerClient).routes
                        }
                        if (enableAdminRoutes) {
                            routes += AdminRoutes(sequencerClient).routes
                        }
                        if (faucetMode != FaucetMode.Off) {
                            routes += faucetRoutes.faucet
                        }
                    },
                "/tma/v1" bind
                    contract {
                        routes += listOf(
                            TelegramMiniAppRoutes.getUser,
                            TelegramMiniAppRoutes.signUp,
                            TelegramMiniAppRoutes.claimReward,
                            TelegramMiniAppRoutes.recordReactionTime,
                        )
                    },
            ),
        )

    private val websocketApi = WebsocketApi(broadcaster)

    private val server = PolyHandler(
        httpHandler,
        websockets(websocketApi.connect()),
    ).asServer(Netty(config.httpPort, ServerConfig.StopMode.Graceful(ofSeconds(1))))

    override fun start() {
        logger.info { "Starting" }
        super.start()
        server.start()
        broadcaster.start()
        logger.info { "Started" }
    }

    override fun stop() {
        logger.info { "Stopping" }
        super.stop()
        broadcaster.stop()
        server.stop()
        logger.info { "Stopped" }
    }
}
