package xyz.funkybit.apps.api

import io.github.oshai.kotlinlogging.KLogger
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import xyz.funkybit.core.db.DbConfig
import xyz.funkybit.core.db.connect

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
