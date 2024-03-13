package co.chainring.core.db

import co.chainring.core.model.db.migrations.V1_DeployedSmartContract
import org.apache.commons.dbcp2.BasicDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.Transaction
import java.lang.System.getenv
import java.time.Duration

data class DbConfig(
    val user: String = getenv("DB_USER") ?: "chainring",
    val password: String = getenv("DB_PASSWORD") ?: "chainring",
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
            this.password = config.password
            this.username = config.user
            validationQuery = config.validationQuery
            initialSize = config.initialPoolSize
            minIdle = config.minIdleConnections
            maxIdle = config.maxIdleConnections
            maxTotal = config.maxConnections
            setMaxWait(Duration.ofMillis(config.maxConnectionWaitingTimeMs))
        },
        databaseConfig =
            DatabaseConfig.Companion.invoke {
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

val migrations =
    listOf(
        V1_DeployedSmartContract(),
    )
