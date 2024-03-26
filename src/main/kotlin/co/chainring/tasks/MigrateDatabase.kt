package co.chainring.tasks

import co.chainring.core.db.DbConfig
import co.chainring.core.db.connect
import co.chainring.core.db.migrations
import co.chainring.core.db.upgrade
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager

private val logger = KotlinLogging.logger {}

fun migrateDatabase() {
    val db = Database.connect(DbConfig())
    TransactionManager.defaultDatabase = db
    db.upgrade(migrations, logger)
}
