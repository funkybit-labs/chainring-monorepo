package co.chainring.telegrambot.app

import co.chainring.core.model.db.TelegramBotUserEntity
import co.chainring.core.model.tgbot.TelegramMessageId
import co.chainring.core.model.tgbot.TelegramUserId
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.concurrent.thread

class Bot(private val client: TelegramClient) {
    private val logger = KotlinLogging.logger { }

    interface OutputChannel {
        fun sendMessage(cmd: BotOutput.SendMessage): TelegramMessageId
        fun deleteMessage(cmd: BotOutput.DeleteMessage)
    }

    interface TelegramClient : OutputChannel {
        fun startPolling(updateHandler: (BotInput) -> Unit)
        fun stopPolling()
    }

    private val botSessions = mutableMapOf<TelegramUserId, BotSession>()
    private var stopRequested = false

    fun start() {
        transaction {
            TelegramBotUserEntity.all().forEach {
                botSessions[it.telegramUserId] = BotSession(it.telegramUserId, client)
            }
        }

        client.startPolling(::handleInput)
        pendingSessionsRefresherThread.start()
    }

    fun stop() {
        stopRequested = true
        client.stopPolling()
        pendingSessionsRefresherThread.join(1000)
        if (pendingSessionsRefresherThread.isAlive) {
            pendingSessionsRefresherThread.interrupt()
            pendingSessionsRefresherThread.join()
        }
    }

    private fun handleInput(input: BotInput) {
        // bot session creation happens in a separate transaction for now
        // because otherwise we are creating wallet record and in the same db transaction
        // we are trying to get its balances over the API which in turn also
        // attempts to create the same wallet record and in the end one of the transactions fail
        // with duplicate key violation
        // TODO: change back to one transaction here after bot no longer uses the API (CHAIN-173)
        val session = transaction { getBotSession(input.from) }
        transaction {
            session.handleInput(input)
        }
    }

    private val pendingSessionsRefresherThread = thread(start = false, isDaemon = false) {
        while (!stopRequested) {
            try {
                transaction {
                    val botUser = TelegramBotUserEntity.leastRecentlyUpdatedWithPendingSession()
                    if (botUser == null) {
                        Thread.sleep(50)
                    } else {
                        getBotSession(botUser.telegramUserId).also(BotSession::refresh)
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Processing thread exception" }
            }
        }
    }

    private fun getBotSession(telegramUserId: TelegramUserId): BotSession =
        botSessions.getOrPut(telegramUserId) {
            BotSession(telegramUserId, client)
        }
}
