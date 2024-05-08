package co.chainring.telegrambot.app

import co.chainring.apps.BaseApp
import co.chainring.core.db.DbConfig
import co.chainring.core.model.db.TelegramBotUserEntity
import com.github.ehsannarmani.bot.Bot
import com.github.ehsannarmani.bot.model.message.TextMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction

val dbConfig: DbConfig = DbConfig()

class BotApp : BaseApp(dbConfig = dbConfig) {
    override val logger = KotlinLogging.logger {}

    private val botSessions = mutableMapOf<Long, BotSession>()
    private val botToken = System.getenv("TELEGRAM_BOT_TOKEN") ?: "6650191369:AAEwYfYkRjd3pBf7OEHmCIQ6MZZ1Wgygv6Q"

    private lateinit var bot: Bot
    private fun run() {
        bot = Bot(
            token = botToken,
            onUpdate = {
                onMessage { message ->
                    val session = getOrCreateBotSession(message.from.id)
                    onText {
                        onCommand("start") {
                            this.mainMenu(session)
                        }

                        onText { text ->
                            // these are replies to messages we sent requesting input
                            message.replyToMessage?.messageId?.let { replyMessageId ->
                                session.handleReplyMessage(replyMessageId, text, message.messageId)?.let {
                                    sendMessage(
                                        TextMessage(
                                            text = it,
                                            chatId = message.chat.id.toString(),
                                        ),
                                    )
                                }
                                if (session.showMenuAfterReply()) {
                                    this.mainMenu(session)
                                }
                            }
                        }
                    }
                }
            },
        )
        bot.startPolling()
    }

    private fun getOrCreateBotSession(userId: Long): BotSession {
        return botSessions.getOrPut(userId) {
            transaction {
                BotSession(TelegramBotUserEntity.getOrCreate(telegramUserId = userId), bot = bot)
            }.also {
                it.loadUserWallet()
            }
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
