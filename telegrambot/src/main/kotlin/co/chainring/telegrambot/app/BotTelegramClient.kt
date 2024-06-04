package co.chainring.telegrambot.app

import co.chainring.core.model.db.TelegramMessageId
import co.chainring.core.model.db.TelegramUserId
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ForceReplyKeyboard
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow

class BotTelegramClient(private val token: String) : Bot.TelegramClient {
    private val app = TelegramBotsLongPollingApplication()
    private val telegramClient = OkHttpTelegramClient(token)

    override fun startPolling(inputHandler: (Bot.Input) -> Unit) {
        app.registerBot(
            token,
            object : LongPollingSingleThreadUpdateConsumer {
                override fun consume(update: Update) {
                    val message = update.message
                    val callbackQuery = update.callbackQuery

                    if (message != null && message.hasText()) {
                        if (message.text == "/start") {
                            inputHandler(
                                Bot.Input.StartCommand(
                                    TelegramUserId(
                                        message.from.id,
                                    ),
                                ),
                            )
                        } else {
                            inputHandler(
                                Bot.Input.TextMessage(
                                    TelegramUserId(message.from.id),
                                    TelegramMessageId(message.messageId),
                                    message.text,
                                    message.replyToMessage?.messageId?.let {
                                        TelegramMessageId(it)
                                    },
                                ),
                            )
                        }
                    } else if (callbackQuery != null) {
                        CallbackData.deserialize(callbackQuery.data)
                            ?.also { callbackData ->
                                inputHandler(
                                    Bot.Input.CallbackQuery(
                                        from = TelegramUserId(callbackQuery.from.id),
                                        id = callbackQuery.id,
                                        data = callbackData,
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

    override fun sendMessage(cmd: Bot.Output.SendMessage): TelegramMessageId =
        telegramClient.execute(
            SendMessage.builder()
                .chatId(cmd.chatId)
                .text(cmd.text)
                .parseMode(cmd.parseMode)
                .replyMarkup(
                    when (cmd.keyboard) {
                        is Bot.Output.SendMessage.Keyboard.Inline -> {
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
                        is Bot.Output.SendMessage.Keyboard.ForceReply -> {
                            ForceReplyKeyboard(
                                true,
                                null,
                                cmd.keyboard.inputFieldPlaceholder,
                            )
                        }
                        else -> null
                    },
                )
                .build(),
        ).messageId.let { TelegramMessageId(it) }

    override fun deleteMessage(cmd: Bot.Output.DeleteMessage) {
        telegramClient.execute(
            DeleteMessage.builder()
                .messageId(cmd.messageId.value)
                .chatId(cmd.chatId)
                .build(),
        )
    }
}
