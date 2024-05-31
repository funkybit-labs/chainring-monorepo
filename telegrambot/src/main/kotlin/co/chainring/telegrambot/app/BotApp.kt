package co.chainring.telegrambot.app

import co.chainring.apps.BaseApp
import co.chainring.core.db.DbConfig
import co.chainring.core.model.db.TelegramBotUserEntity
import com.github.ehsannarmani.bot.Bot
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction

class BotApp : BaseApp(dbConfig = DbConfig()) {
    override val logger = KotlinLogging.logger {}

    private val botSessions = mutableMapOf<Long, BotSession>()
    private val botToken = System.getenv("TELEGRAM_BOT_TOKEN") ?: "6650191369:AAEwYfYkRjd3pBf7OEHmCIQ6MZZ1Wgygv6Q"

    private lateinit var bot: Bot
    private fun run() {
        bot = Bot(
            token = botToken,
            onUpdate = {
                logger.debug { "Got update: $it" }

                onCallbackQuery {
                    CallbackData.deserialize(it.data)?.also { callbackData ->
                        getOrCreateBotSession(it.from.id)
                            .handleCallbackButtonClick(callbackData)
                    }
                }
                onMessage { message ->
                    val session = getOrCreateBotSession(message.from.id)
                    onText {
                        onCommand("start") {
                            session.sendMainMenu()
                        }
                        onText { text ->
                            // these are replies to messages we sent requesting input
                            val originalMessage = message.replyToMessage
                            if (originalMessage != null) {
                                session.handleReplyMessage(originalMessage.messageId, message.messageId, text)
                            }
                        }
                    }
                }
            },
            onErrorThrown = { error ->
                logger.error(error) { "Got error" }
            },
        )

        transaction {
            TelegramBotUserEntity.all().forEach {
                botSessions[it.telegramUserId] = BotSession(it.telegramUserId, bot)
            }
        }

        bot.startPolling()
    }

    private fun getOrCreateBotSession(userId: Long): BotSession {
        return botSessions.getOrPut(userId) {
            BotSession(userId, bot)
        }
    }

    override fun start() {
        super.start()
        run()
    }

    override fun stop() {
        logger.info { "Stopping" }
    }
}
