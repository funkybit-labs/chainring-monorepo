package co.chainring.apps.telegrambot

import co.chainring.apps.BaseApp
import co.chainring.apps.api.services.ExchangeApiService
import co.chainring.apps.telegrambot.model.Input
import co.chainring.apps.telegrambot.model.Output
import co.chainring.core.blockchain.ChainManager
import co.chainring.core.db.DbConfig
import co.chainring.core.model.db.DepositEntity
import co.chainring.core.model.db.DepositStatus
import co.chainring.core.model.db.OrderEntity
import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.model.db.WithdrawalStatus
import co.chainring.core.model.telegram.bot.SessionState
import co.chainring.core.model.telegram.bot.TelegramBotUserEntity
import co.chainring.core.model.telegram.bot.TelegramMessageId
import co.chainring.core.sequencer.SequencerClient
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.concurrent.thread

private val botToken = System.getenv("TELEGRAM_BOT_TOKEN") ?: ""
val faucetSupported = System.getenv("FAUCET_SUPPORTED")?.toBoolean() ?: true

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
            pendingSessionsRefresherThread.start()
            logger.info { "Started" }
        }
    }

    override fun stop() {
        logger.info { "Stopping" }
        stopRequested = true
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
