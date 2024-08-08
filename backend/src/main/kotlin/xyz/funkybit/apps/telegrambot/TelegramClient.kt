package xyz.funkybit.apps.telegrambot

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
import xyz.funkybit.apps.telegrambot.model.CallbackData
import xyz.funkybit.apps.telegrambot.model.Input
import xyz.funkybit.apps.telegrambot.model.Output
import xyz.funkybit.core.model.telegram.TelegramUserId
import xyz.funkybit.core.model.telegram.bot.TelegramMessageId

class TelegramClient(private val token: String) : BotApp.TelegramClient {
    private val app = TelegramBotsLongPollingApplication()
    private val telegramClient = OkHttpTelegramClient(token)
    private val logger = KotlinLogging.logger { }

    override fun startPolling(inputHandler: (Input) -> Unit) {
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
                                sendMessage(Output.SendMessage(TelegramUserId(chatId), text = "Unsupported command"))
                            }
                        } else {
                            inputHandler(input)
                        }
                    } catch (e: Throwable) {
                        logger.error(e) { "Error while processing telegram update ${update.updateId} " }

                        if (chatId != null) {
                            sendMessage(Output.SendMessage(TelegramUserId(chatId), text = "Something went wrong while processing your request, please try again later"))
                        }
                    }
                }
            },
        )
    }

    override fun stopPolling() {
        app.stop()
    }

    override fun sendMessage(cmd: Output.SendMessage): TelegramMessageId {
        logger.debug { "Sending message $cmd" }

        val sentMessageId = telegramClient.execute(
            SendMessage.builder()
                .chatId(cmd.recipient.value)
                .text(cmd.text)
                .parseMode(cmd.parseMode)
                .replyMarkup(
                    when (cmd.keyboard) {
                        is Output.SendMessage.Keyboard.Inline -> {
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

    override fun deleteMessage(cmd: Output.DeleteMessage) {
        logger.debug { "Deleting message $cmd" }

        telegramClient.execute(
            DeleteMessage.builder()
                .messageId(cmd.messageId.value)
                .chatId(cmd.chatId)
                .build(),
        )
    }

    private fun updateToBotInput(update: Update): Input? {
        val message = update.message
        val callbackQuery = update.callbackQuery

        return if (message != null && message.hasText()) {
            if (message.text == "/start") {
                Input.Start(TelegramUserId(message.from.id))
            } else {
                Input.Text(
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
                        is CallbackData.Airdrop -> Input.Airdrop(from)
                        is CallbackData.SymbolSelected -> Input.SymbolSelected(from, callbackData.symbol)
                        is CallbackData.Confirm -> Input.Confirm(from)
                        is CallbackData.Cancel -> Input.Cancel(from)
                        is CallbackData.Deposit -> Input.Deposit(from)
                        is CallbackData.ChangeAmount -> Input.ChangeAmount(from)
                        is CallbackData.Withdraw -> Input.Withdraw(from)
                        is CallbackData.Swap -> Input.Swap(from)
                        is CallbackData.Settings -> Input.Settings(from)
                        is CallbackData.ImportWallet -> Input.ImportWallet(from)
                        is CallbackData.SwitchWallet -> Input.SwitchWallet(from)
                        is CallbackData.WalletSelected -> Input.WalletSelected(from, callbackData.abbreviatedAddress)
                        is CallbackData.ExportPrivateKey -> Input.ExportPrivateKey(from)
                    }
                }
        } else {
            null
        }
    }
}
