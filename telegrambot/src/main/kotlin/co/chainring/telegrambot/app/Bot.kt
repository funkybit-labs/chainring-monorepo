package co.chainring.telegrambot.app

import co.chainring.core.model.db.TelegramBotUserEntity
import co.chainring.core.model.db.TelegramMessageId
import co.chainring.core.model.db.TelegramUserId
import co.chainring.core.model.db.TelegramUserReplyType
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.ScheduledThreadPoolExecutor

class Bot(private val client: TelegramClient) {
    sealed class Input {
        abstract val from: TelegramUserId

        data class StartCommand(
            override val from: TelegramUserId,
        ) : Input()

        data class TextMessage(
            override val from: TelegramUserId,
            val id: TelegramMessageId,
            val text: String,
            val replyToMessage: TelegramMessageId?,
        ) : Input()

        data class CallbackQuery(
            override val from: TelegramUserId,
            val id: String,
            val data: CallbackData,
        ) : Input()
    }

    sealed class Output {
        data class SendMessage(
            val chatId: String,
            val text: String,
            val parseMode: String = "markdown",
            val keyboard: Keyboard? = null,
        ) : Output() {
            sealed class Keyboard {
                data class Inline(
                    val items: List<List<CallbackButton>>,
                ) : Keyboard() {
                    data class CallbackButton(
                        val text: String,
                        val data: CallbackData,
                    )
                }

                data class ForceReply(
                    val inputFieldPlaceholder: String,
                    val expectedReplyType: TelegramUserReplyType,
                ) : Keyboard()
            }
        }

        data class DeleteMessage(
            val chatId: String,
            val messageId: TelegramMessageId,
        ) : Output()
    }

    interface OutputChannel {
        fun sendMessage(cmd: Output.SendMessage): TelegramMessageId
        fun deleteMessage(cmd: Output.DeleteMessage)
    }

    interface TelegramClient : OutputChannel {
        fun startPolling(inputHandler: (Input) -> Unit)
        fun stopPolling()
    }

    private val botSessions = mutableMapOf<TelegramUserId, BotSession>()
    private val timer = ScheduledThreadPoolExecutor(1)

    fun start() {
        transaction {
            TelegramBotUserEntity.all().forEach {
                botSessions[it.telegramUserId] = BotSession(it.telegramUserId, timer, client)
            }
        }

        client.startPolling(::handleInput)
    }

    fun stop() {
        timer.shutdown()
    }

    private fun handleInput(input: Input) {
        // bot session creation happens in a separate transaction for now
        // because otherwise we are creating wallet record and in the same db transaction
        // we are trying to get its balances over the API which in turn also
        // attempts to create the same wallet record and in the end one of the transactions fail
        // with duplicate key violation
        // TODO: change back to one transaction here after bot no longer uses the API (CHAIN-173)
        val session = transaction {
            botSessions.getOrPut(input.from) {
                BotSession(input.from, timer, client)
            }
        }
        transaction {
            session.handleInput(input)
        }
    }
}
