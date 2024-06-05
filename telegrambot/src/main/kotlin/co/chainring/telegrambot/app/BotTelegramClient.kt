package co.chainring.telegrambot.app

import co.chainring.core.model.tgbot.TelegramMessageId
import io.github.oshai.kotlinlogging.KotlinLogging
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow

class BotTelegramClient(private val token: String) : Bot.TelegramClient {
    private val app = TelegramBotsLongPollingApplication()
    private val telegramClient = OkHttpTelegramClient(token)
    private val logger = KotlinLogging.logger { }

    override fun startPolling(updateHandler: (BotInput) -> Unit) {
        app.registerBot(
            token,
            object : LongPollingSingleThreadUpdateConsumer {
                override fun consume(update: Update) {
                    val message = update.message
                    val callbackQuery = update.callbackQuery

                    logger.debug { "Received an update $update" }

                    try {
                        val input = BotInput.fromUpdate(update)
                        if (input != null) {
                            updateHandler(input)
                        }
                    } catch (e: Throwable) {
                        logger.error(e) { "Error while processing telegram update ${update.updateId} " }

                        val chatId = message?.from?.id?.toString() ?: callbackQuery?.from?.id?.toString()
                        if (chatId != null) {
                            sendMessage(
                                BotOutput.SendMessage(
                                    chatId = chatId,
                                    text = "Something went wrong while processing your request, please try again later",
                                ),
                            )
                        }
                    }
                }
            },
        )
    }

    override fun stopPolling() {
        app.stop()
    }

    override fun sendMessage(cmd: BotOutput.SendMessage): TelegramMessageId {
        logger.debug { "Sending message $cmd" }

        val sentMessageId = telegramClient.execute(
            SendMessage.builder()
                .chatId(cmd.chatId)
                .text(cmd.text)
                .parseMode(cmd.parseMode)
                .replyMarkup(
                    when (cmd.keyboard) {
                        is BotOutput.SendMessage.Keyboard.Inline -> {
                            InlineKeyboardMarkup(
                                cmd.keyboard.items.map { row ->
                                    InlineKeyboardRow(
                                        row.map { item ->
                                            InlineKeyboardButton.builder()
                                                .text(item.text)
                                                .callbackData(item.data.serialize())
                                                .build()
                                        },
                                    )
                                },
                            )
                        }
                        else -> null
                    },
                )
                .build(),
        ).messageId.let { TelegramMessageId(it) }

        logger.debug { "Sent message id: $sentMessageId" }

        return sentMessageId
    }

    override fun deleteMessage(cmd: BotOutput.DeleteMessage) {
        logger.debug { "Deleting message $cmd" }

        telegramClient.execute(
            DeleteMessage.builder()
                .messageId(cmd.messageId.value)
                .chatId(cmd.chatId)
                .build(),
        )
    }
}
