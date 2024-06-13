package co.chainring.telegrambot.app

import co.chainring.core.model.tgbot.TelegramMessageId
import co.chainring.core.model.tgbot.TelegramUserId
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

    override fun startPolling(inputHandler: (BotInput) -> Unit) {
        app.registerBot(
            token,
            object : LongPollingSingleThreadUpdateConsumer {
                override fun consume(update: Update) {
                    val message = update.message
                    val callbackQuery = update.callbackQuery
                    val chatId = message?.from?.id ?: callbackQuery?.from?.id

                    logger.debug { "Received an update $update" }

                    try {
                        val input = updateToBotInput(update)
                        if (input == null) {
                            if (chatId != null) {
                                sendMessage(BotOutput.SendMessage(TelegramUserId(chatId), text = "Unsupported command"))
                            }
                        } else {
                            inputHandler(input)
                        }
                    } catch (e: Throwable) {
                        logger.error(e) { "Error while processing telegram update ${update.updateId} " }

                        if (chatId != null) {
                            sendMessage(BotOutput.SendMessage(TelegramUserId(chatId), text = "Something went wrong while processing your request, please try again later"))
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
                .chatId(cmd.recipient.value)
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

    private fun updateToBotInput(update: Update): BotInput? {
        val message = update.message
        val callbackQuery = update.callbackQuery

        return if (message != null && message.hasText()) {
            if (message.text == "/start") {
                BotInput.Start(TelegramUserId(message.from.id))
            } else {
                BotInput.Text(
                    TelegramUserId(message.from.id),
                    message.text,
                    TelegramMessageId(message.messageId),
                )
            }
        } else if (callbackQuery != null) {
            CallbackData
                .deserialize(callbackQuery.data)
                ?.let { callbackData ->
                    val from = TelegramUserId(callbackQuery.from.id)
                    when (callbackData) {
                        is CallbackData.Airdrop -> BotInput.Airdrop(from)
                        is CallbackData.SymbolSelected -> BotInput.SymbolSelected(from, callbackData.symbol)
                        is CallbackData.Confirm -> BotInput.Confirm(from)
                        is CallbackData.Cancel -> BotInput.Cancel(from)
                        is CallbackData.Deposit -> BotInput.Deposit(from)
                        is CallbackData.ChangeAmount -> BotInput.ChangeAmount(from)
                        is CallbackData.Withdraw -> BotInput.Withdraw(from)
                        is CallbackData.Swap -> BotInput.Swap(from)
                        is CallbackData.Settings -> BotInput.Settings(from)
                        is CallbackData.ImportWallet -> BotInput.ImportWallet(from)
                        is CallbackData.SwitchWallet -> BotInput.SwitchWallet(from)
                        is CallbackData.WalletSelected -> BotInput.WalletSelected(from, callbackData.abbreviatedAddress)
                        is CallbackData.ExportPrivateKey -> BotInput.ExportPrivateKey(from)
                    }
                }
        } else {
            null
        }
    }
}
