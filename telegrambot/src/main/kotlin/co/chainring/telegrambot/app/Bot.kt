package co.chainring.telegrambot.app

import co.chainring.apps.api.services.ExchangeApiService
import co.chainring.core.blockchain.ChainManager
import co.chainring.core.model.db.DepositEntity
import co.chainring.core.model.db.DepositStatus
import co.chainring.core.model.db.OrderEntity
import co.chainring.core.model.db.TelegramBotUserEntity
import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.model.db.WithdrawalStatus
import co.chainring.core.model.tgbot.BotSessionState
import co.chainring.core.model.tgbot.TelegramMessageId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.concurrent.thread

class Bot(private val client: TelegramClient, exchangeApiService: ExchangeApiService) {
    private val logger = KotlinLogging.logger { }

    interface OutputChannel {
        fun sendMessage(cmd: BotOutput.SendMessage): TelegramMessageId
        fun deleteMessage(cmd: BotOutput.DeleteMessage)
    }

    interface TelegramClient : OutputChannel {
        fun startPolling(inputHandler: (BotInput) -> Unit)
        fun stopPolling()
    }

    private val inputHandler = BotInputHandler(client, exchangeApiService)

    private var stopRequested = false

    fun start() {
        client.startPolling(inputHandler = { input ->
            transaction {
                inputHandler.handle(input)
            }
        })
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

    private val pendingSessionsRefresherThread = thread(start = false, isDaemon = false) {
        fun refresh(user: TelegramBotUserEntity) {
            when (val sessionState = user.sessionState) {
                is BotSessionState.AirdropPending -> {
                    val txReceipt = ChainManager
                        .getBlockchainClient(sessionState.symbol.chainId.value)
                        .getTransactionReceipt(sessionState.txHash)

                    if (txReceipt != null) {
                        inputHandler.handle(BotInput.AirdropTxReceipt(user.telegramUserId, txReceipt))
                    }
                }
                is BotSessionState.DepositPending -> {
                    val deposit = DepositEntity[sessionState.depositId]
                    if (deposit.status == DepositStatus.Complete || deposit.status == DepositStatus.Failed) {
                        inputHandler.handle(BotInput.DepositCompleted(user.telegramUserId, deposit))
                    }
                }
                is BotSessionState.WithdrawalPending -> {
                    val withdrawal = WithdrawalEntity[sessionState.withdrawalId]
                    if (withdrawal.status == WithdrawalStatus.Complete || withdrawal.status == WithdrawalStatus.Failed) {
                        inputHandler.handle(BotInput.WithdrawalCompleted(user.telegramUserId, withdrawal))
                    }
                }
                is BotSessionState.SwapPending -> {
                    val order = OrderEntity[sessionState.orderId]
                    if (order.status.isFinal()) {
                        inputHandler.handle(BotInput.SwapCompleted(user.telegramUserId, order))
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
