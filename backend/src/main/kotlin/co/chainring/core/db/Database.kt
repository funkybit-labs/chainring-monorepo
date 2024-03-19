package co.chainring.core.db

import co.chainring.core.model.db.migrations.V1_DeployedSmartContract
import co.chainring.core.model.db.migrations.V2_ERC20Token
import co.chainring.core.model.db.migrations.V3_UpdateDeployedSmartContract
import co.chainring.core.model.db.migrations.V4_AddDecimalsToERC20Token
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.commons.dbcp2.BasicDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.Transaction
import java.lang.System.getenv
import java.time.Duration

@Serializable
data class DbCredentials(
    val username: String,
    val password: String,
)

data class DbConfig(
    val credentials: DbCredentials =
        getenv("DB_CREDENTIALS")?.let { Json.decodeFromString<DbCredentials>(it) }
            ?: DbCredentials(
                username = "chainring",
                password = "chainring",
            ),
    val name: String = getenv("DB_NAME") ?: "chainring",
    val driver: String = "org.postgresql.Driver",
    val host: String = getenv("DB_HOST") ?: "localhost",
    val port: Int = getenv("DB_PORT")?.toIntOrNull() ?: 5432,
    val initialPoolSize: Int = getenv("DB_INITIAL_POOL_SIZE")?.toIntOrNull() ?: 1,
    val minIdleConnections: Int = getenv("DB_MIN_IDLE_CONNECTIONS")?.toIntOrNull() ?: 15,
    val maxIdleConnections: Int = getenv("DB_MAX_IDLE_CONNECTIONS")?.toIntOrNull() ?: 25,
    val maxConnections: Int = getenv("DB_MAX_CONNECTIONS")?.toIntOrNull() ?: 25,
    val maxConnectionWaitingTimeMs: Long = getenv("DB_MAX_CONNECTION_WAITING_TIME_MS")?.toLongOrNull() ?: 10_0000,
    val validationQuery: String = getenv("DB_VALIDATION_QUERY") ?: "select 1",
)

fun Database.Companion.connect(config: DbConfig): Database =
    connect(
        BasicDataSource().apply {
            driverClassName = config.driver
            url = "jdbc:postgresql://${config.host}:${config.port}/${config.name}"
            this.password = config.credentials.password
            this.username = config.credentials.username
            validationQuery = config.validationQuery
            initialSize = config.initialPoolSize
            minIdle = config.minIdleConnections
            maxIdle = config.maxIdleConnections
            maxTotal = config.maxConnections
            setMaxWait(Duration.ofMillis(config.maxConnectionWaitingTimeMs))
        },
        databaseConfig = DatabaseConfig.Companion.invoke {
            defaultRepetitionAttempts = 1
            useNestedTransactions = true
        },
    )

fun Transaction.notifyDbListener(
    channel: String,
    payload: String? = null,
) {
    exec(
        listOfNotNull(
            "NOTIFY $channel",
            payload?.let { "'$payload'" },
        ).joinToString(", "),
    )
}

val migrations = listOf(
    V1_DeployedSmartContract(),
    V2_ERC20Token(),
    V3_UpdateDeployedSmartContract(),
    V4_AddDecimalsToERC20Token(),
)
