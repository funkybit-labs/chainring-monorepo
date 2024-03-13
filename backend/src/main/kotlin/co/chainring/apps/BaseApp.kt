package co.chainring.apps

import co.chainring.core.db.DbConfig
import co.chainring.core.db.connect
import co.chainring.core.db.migrations
import co.chainring.core.db.upgrade
import io.github.oshai.kotlinlogging.KLogger
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager

abstract class BaseApp(dbConfig: DbConfig) {
    abstract val logger: KLogger

    protected val db = Database.connect(dbConfig)

    init {
        TransactionManager.defaultDatabase = db
    }

    open fun start() {
        db.upgrade(migrations, logger)
    }

    open fun stop() {
    }
}
