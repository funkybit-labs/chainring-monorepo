package xyz.funkybit.core.utils

import io.github.oshai.kotlinlogging.KotlinLogging
import org.awaitility.kotlin.await
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.statements.jdbc.JdbcConnectionImpl
import org.postgresql.PGConnection
import org.postgresql.PGNotification
import java.time.Duration
import kotlin.concurrent.thread

class PgListener(
    private val database: Database,
    private val threadName: String,
    private val channel: String,
    onReconnect: () -> Unit,
    onNotifyLogic: (PGNotification) -> Unit,
) {
    private var reconnectDebounceMs = 1000L

    private val logger = KotlinLogging.logger {}
    private var isRunning: Boolean = false

    private val listener = thread(start = false, name = threadName) {
        logger.debug { "TID [${Thread.currentThread().id}] starting for $channel" }
        while (true) {
            try {
                val listenerConn = (database.connector() as JdbcConnectionImpl).connection
                val pgListenerConn = listenerConn.unwrap(PGConnection::class.java)
                listenerConn.createStatement().also { it.execute("LISTEN $channel") }.close()
                isRunning = true
                try {
                    while (true) {
                        if (Thread.interrupted()) {
                            throw InterruptedException()
                        }

                        val notifications = pgListenerConn.getNotifications(100)
                        if (notifications != null && notifications.isNotEmpty()) {
                            logger.debug { "TID [${Thread.currentThread().id}] $channel listener got ${notifications.size} notifications" }
                            notifications.forEach {
                                logger.debug { "TID [${Thread.currentThread().id}] performing $threadName task for '${it.name}' notification with param '${it.parameter}'" }
                                try {
                                    onNotifyLogic(it)
                                } catch (e: Exception) {
                                    logger.error(e) { "Exception processing notification" }
                                }
                            }
                        }
                    }
                } catch (_: InterruptedException) {
                    try {
                        listenerConn.createStatement().also { it.execute("UNLISTEN $channel") }.close()
                        listenerConn.close()
                        isRunning = false
                    } catch (t: Throwable) {
                        logger.error(t) { "Caught exception trying to unlisten from $channel" }
                    }
                    return@thread
                }
            } catch (t: Throwable) {
                logger.error(t) { "Caught an exception" }
                Thread.sleep(reconnectDebounceMs)
                onReconnect()
            }
        }
    }

    fun start() {
        if (!listener.isAlive) {
            listener.start()
        }
        await
            .pollInSameThread()
            .pollDelay(Duration.ofMillis(10))
            .pollInterval(Duration.ofMillis(10))
            .atMost(Duration.ofSeconds(30))
            .until {
                isRunning
            }
    }

    fun stop() {
        listener.interrupt()
        listener.join()
        isRunning = false
    }
}
