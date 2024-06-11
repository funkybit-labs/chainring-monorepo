package co.chainring.apps

import co.chainring.core.db.DbConfig
import co.chainring.core.db.connect
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
    }

    open fun stop() {
    }
}
