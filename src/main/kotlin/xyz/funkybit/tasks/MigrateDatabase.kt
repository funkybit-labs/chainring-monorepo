package xyz.funkybit.tasks

import xyz.funkybit.core.db.DbConfig
import xyz.funkybit.core.db.connect
import xyz.funkybit.core.db.migrations
import xyz.funkybit.core.db.upgrade
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager

private val logger = KotlinLogging.logger {}

fun migrateDatabase() {
    val db = Database.connect(DbConfig())
    TransactionManager.defaultDatabase = db
    db.upgrade(migrations, logger)
}
