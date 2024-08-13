package xyz.funkybit.apps.telegrambot

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.transactions.transaction
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import xyz.funkybit.apps.api.BaseApp
import xyz.funkybit.apps.api.services.ExchangeApiService
import xyz.funkybit.apps.telegrambot.model.Input
import xyz.funkybit.apps.telegrambot.model.Output
import xyz.funkybit.core.blockchain.ChainManager
import xyz.funkybit.core.db.DbConfig
import xyz.funkybit.core.model.db.DepositEntity
import xyz.funkybit.core.model.db.DepositStatus
import xyz.funkybit.core.model.db.OrderEntity
import xyz.funkybit.core.model.db.WithdrawalEntity
import xyz.funkybit.core.model.db.WithdrawalStatus
import xyz.funkybit.core.model.telegram.TelegramUserId
import xyz.funkybit.core.model.telegram.bot.SessionState
import xyz.funkybit.core.model.telegram.bot.TelegramBotUserEntity
import xyz.funkybit.core.model.telegram.bot.TelegramMessageId
import xyz.funkybit.core.sequencer.SequencerClient
import xyz.funkybit.core.utils.PgListener
import kotlin.concurrent.thread

private val botToken = System.getenv("TELEGRAM_BOT_TOKEN") ?: ""
val faucetSupported = System.getenv("FAUCET_SUPPORTED")?.toBoolean() ?: true

enum class TelegramUserMessage {
    FirstTouch,
    WelcomeBack
}

class BotApp : BaseApp(dbConfig = DbConfig()) {
    override val logger = KotlinLogging.logger { }
    private val client = TelegramClient(botToken)
    private val exchangeApiService = ExchangeApiService(SequencerClient())

    interface OutputChannel {
        fun sendMessage(cmd: Output.SendMessage): TelegramMessageId
        fun deleteMessage(cmd: Output.DeleteMessage)
    }

    interface TelegramClient : OutputChannel {
        fun startPolling(inputHandler: (Input) -> Unit)
        fun stopPolling()
    }

    private val inputHandler = InputHandler(client, exchangeApiService)

    private var stopRequested = false

    private val pgListener = PgListener(db, "telegram_bot_app-listener", "telegram_bot_app_ctl", {}) { notification ->
        val controlMessage = notification.parameter
        if (controlMessage.contains(':')) {
            val parts = controlMessage.split(':')
            if (parts.size >= 2) {
                val telegramUserId = TelegramUserId(parts[0].toLong())
                val message = TelegramUserMessage.valueOf(parts[1])
                client.sendMessage(Output.SendMessage(telegramUserId, when (message) {
                    TelegramUserMessage.FirstTouch -> "Hello, welcome to funkybit!"
                    TelegramUserMessage.WelcomeBack -> "Welcome back, great to see you again."
                }))
            }
        }
    }

    override fun start() {
        if (botToken.isEmpty()) {
            logger.error { "No bot token provided" }
        } else {
            logger.info { "Starting" }
            super.start()
            client.startPolling(inputHandler = { input ->
                transaction {
                    inputHandler.handle(input)
                }
            })
            pgListener.start()
            pendingSessionsRefresherThread.start()
            logger.info { "Started" }
        }
    }

    override fun stop() {
        logger.info { "Stopping" }
        stopRequested = true
        pgListener.stop()
        client.stopPolling()
        pendingSessionsRefresherThread.join(1000)
        if (pendingSessionsRefresherThread.isAlive) {
            pendingSessionsRefresherThread.interrupt()
            pendingSessionsRefresherThread.join()
        }
        logger.info { "Stopped" }
    }

    private val pendingSessionsRefresherThread = thread(start = false, isDaemon = false) {
        fun refresh(user: TelegramBotUserEntity) {
            when (val sessionState = user.sessionState) {
                is SessionState.AirdropPending -> {
                    val txReceipt = ChainManager
                        .getBlockchainClient(sessionState.symbol.chainId.value)
                        .getTransactionReceipt(sessionState.txHash)

                    if (txReceipt != null) {
                        inputHandler.handle(
                            Input.AirdropTxReceipt(
                                user.telegramUserId,
                                txReceipt,
                            ),
                        )
                    }
                }
                is SessionState.DepositPending -> {
                    val deposit = DepositEntity[sessionState.depositId]
                    if (deposit.status == DepositStatus.Complete || deposit.status == DepositStatus.Failed) {
                        inputHandler.handle(
                            Input.DepositCompleted(
                                user.telegramUserId,
                                deposit,
                            ),
                        )
                    }
                }
                is SessionState.WithdrawalPending -> {
                    val withdrawal = WithdrawalEntity[sessionState.withdrawalId]
                    if (withdrawal.status == WithdrawalStatus.Complete || withdrawal.status == WithdrawalStatus.Failed) {
                        inputHandler.handle(
                            Input.WithdrawalCompleted(
                                user.telegramUserId,
                                withdrawal,
                            ),
                        )
                    }
                }
                is SessionState.SwapPending -> {
                    val order = OrderEntity[sessionState.orderId]
                    if (order.status.isFinal()) {
                        inputHandler.handle(
                            Input.SwapCompleted(
                                user.telegramUserId,
                                order,
                            ),
                        )
                    }
                }
                else -> {}
            }
            user.updatedAt = Clock.System.now()
        }

        while (!stopRequested) {
            try {
                transaction {
                    val user = TelegramBotUserEntity.leastRecentlyUpdatedWithPendingSession()
                    if (user == null) {
                        Thread.sleep(50)
                    } else {
                        refresh(user)
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Processing thread exception" }
            }
        }
    }
}
