package co.chainring.telegrambot.app

import co.chainring.core.model.Address
import co.chainring.core.model.Symbol
import co.chainring.core.model.abbreviated
import co.chainring.core.model.db.DepositEntity
import co.chainring.core.model.db.DepositStatus
import co.chainring.core.model.db.OrderEntity
import co.chainring.core.model.db.OrderStatus
import co.chainring.core.model.db.TelegramBotUserEntity
import co.chainring.core.model.db.TelegramBotUserWalletEntity
import co.chainring.core.model.db.WalletEntity
import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.model.db.WithdrawalStatus
import co.chainring.core.model.tgbot.BotSessionState
import co.chainring.core.model.tgbot.TelegramUserId
import co.chainring.core.utils.fromFundamentalUnits
import co.chanring.core.model.encrypt
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import org.web3j.crypto.Keys
import java.math.BigDecimal
import java.math.BigInteger

class BotSession(
    private val telegramUserId: TelegramUserId,
    private val outputChannel: Bot.OutputChannel,
) {
    private val logger = KotlinLogging.logger { }

    private val chatId = telegramUserId.value.toString()
    private lateinit var currentWallet: BotSessionCurrentWallet

    init {
        val botUser = TelegramBotUserEntity.getOrCreate(telegramUserId)
        if (botUser.wallets.empty()) {
            val privateKey = Keys.createEcKeyPair().privateKey.toString(16)
            switchWallet(
                TelegramBotUserWalletEntity.create(
                    WalletEntity.getOrCreate(Address.fromPrivateKey(privateKey)),
                    botUser,
                    privateKey.encrypt(),
                    isCurrent = true,
                ).also {
                    it.flush()
                },
            )
        } else {
            switchWallet(
                botUser.wallets.find { it.isCurrent }
                    ?: botUser.wallets.first(),
            )
        }
    }

    fun handleInput(input: BotInput) {
        val botUser = TelegramBotUserEntity.getOrCreate(telegramUserId)

        when (val currentState = botUser.sessionState) {
            is BotSessionState.Initial -> {
                when (input) {
                    is BotInput.Start -> {
                        deleteMessages()
                        sendMainMenu()
                        botUser.updateSessionState(BotSessionState.MainMenu)
                    }
                    else -> {
                        sendInvalidCommand()
                    }
                }
            }

            is BotSessionState.MainMenu -> {
                when (input) {
                    is BotInput.Start -> {
                        deleteMessages()
                        sendMainMenu()
                    }
                    is BotInput.Airdrop -> {
                        send(BotMessage.SelectSymbol.toAirdrop(currentWallet.airDroppableSymbols()), scheduleDeletion = true)
                        botUser.updateSessionState(BotSessionState.AirdropSymbolSelection)
                    }
                    is BotInput.Deposit -> {
                        send(BotMessage.SelectSymbol.toDeposit(currentWallet.symbols()), scheduleDeletion = true)
                        botUser.updateSessionState(BotSessionState.DepositSymbolSelection)
                    }
                    is BotInput.Withdraw -> {
                        send(BotMessage.SelectSymbol.toWithdraw(currentWallet.symbols()), scheduleDeletion = true)
                        botUser.updateSessionState(BotSessionState.WithdrawalSymbolSelection)
                    }
                    is BotInput.Swap -> {
                        send(BotMessage.SelectSymbol.toSwapFrom(currentWallet.symbols()), scheduleDeletion = true)
                        botUser.updateSessionState(BotSessionState.SwapFromSymbolSelection)
                    }
                    is BotInput.Settings -> {
                        send(BotMessage.Settings(currentWallet.walletAddress), scheduleDeletion = true)
                        botUser.updateSessionState(BotSessionState.Settings)
                    }
                    else -> {
                        sendInvalidCommand()
                    }
                }
            }

            is BotSessionState.AirdropSymbolSelection -> {
                when (input) {
                    is BotInput.SymbolSelected -> {
                        deleteMessages()
                        currentWallet
                            .airDrop(input.symbol)
                            .onLeft {
                                send(BotMessage.AirdropFailed(it.error))
                            }
                            .onRight {
                                val amount = it.amount.fromFundamentalUnits(currentWallet.decimals(input.symbol))
                                send(BotMessage.AirdropRequested(input.symbol, amount))
                                botUser.updateSessionState(BotSessionState.AirdropPending(input.symbol, amount, it.chainId, it.txHash))
                            }
                    }

                    is BotInput.Cancel -> {
                        deleteMessages()
                        botUser.updateSessionState(BotSessionState.MainMenu)
                    }

                    else -> {
                        sendInvalidCommand(scheduleDeletion = true)
                    }
                }
            }

            is BotSessionState.AirdropPending -> {
                when (input) {
                    is BotInput.AirdropTxReceipt -> {
                        if (input.receipt.isStatusOK) {
                            send(BotMessage.AirdropSucceeded(currentState.symbol, currentState.amount))
                        } else {
                            send(BotMessage.AirdropTxFailed(input.receipt))
                        }
                        sendMainMenu()
                        botUser.updateSessionState(BotSessionState.MainMenu)
                    }
                    else -> { }
                }
            }

            is BotSessionState.DepositSymbolSelection -> {
                when (input) {
                    is BotInput.SymbolSelected -> {
                        deleteMessages()
                        val available = currentWallet.getWalletBalance(input.symbol)
                        if (available > BigDecimal.ZERO) {
                            send(BotMessage.EnterDepositAmount(input.symbol, available))
                            botUser.updateSessionState(BotSessionState.DepositAmountEntry(input.symbol))
                        } else {
                            send(BotMessage.NoBalanceInWallet(input.symbol), scheduleDeletion = true)
                            send(BotMessage.SelectSymbol.toDeposit(currentWallet.symbols()), scheduleDeletion = true)
                        }
                    }

                    is BotInput.Cancel -> {
                        deleteMessages()
                        botUser.updateSessionState(BotSessionState.MainMenu)
                    }

                    else -> {
                        sendInvalidCommand(scheduleDeletion = true)
                    }
                }
            }

            is BotSessionState.DepositAmountEntry -> {
                when (input) {
                    is BotInput.Text -> {
                        if (input.text == "/cancel") {
                            deleteMessages()
                            sendMainMenu()
                            botUser.updateSessionState(BotSessionState.MainMenu)
                        } else {
                            val available = currentWallet.getWalletBalance(currentState.symbol)
                            val amount = runCatching { BigDecimal(input.text) }.getOrNull()
                            if (amount == null) {
                                send(BotMessage.InvalidNumber(input.text))
                            } else if (amount > available) {
                                send(BotMessage.DepositAmountTooLarge)
                                send(BotMessage.EnterDepositAmount(currentState.symbol, available))
                            } else {
                                send(BotMessage.DepositConfirmation(currentState.symbol, amount), scheduleDeletion = true)
                                botUser.updateSessionState(BotSessionState.DepositConfirmation(currentState.symbol, amount))
                            }
                        }
                    }
                    else -> {
                        sendInvalidCommand(scheduleDeletion = true)
                    }
                }
            }

            is BotSessionState.DepositConfirmation -> {
                when (input) {
                    is BotInput.Confirm -> {
                        try {
                            deleteMessages()
                            val depositId = currentWallet.deposit(currentState.amount, currentState.symbol)
                            if (depositId == null) {
                                send(BotMessage.DepositFailed(currentState.symbol))
                                botUser.updateSessionState(BotSessionState.MainMenu)
                            } else {
                                send(BotMessage.DepositInProgress)
                                botUser.updateSessionState(BotSessionState.DepositPending(depositId))
                            }
                        } catch (e: Exception) {
                            logger.error(e) { "Deposit failed" }
                            send(BotMessage.DepositFailed(currentState.symbol))
                            sendMainMenu()
                            botUser.updateSessionState(BotSessionState.MainMenu)
                        }
                    }

                    is BotInput.Cancel -> {
                        deleteMessages()
                        sendMainMenu()
                        botUser.updateSessionState(BotSessionState.MainMenu)
                    }

                    is BotInput.ChangeAmount -> {
                        deleteMessages()
                        send(BotMessage.EnterDepositAmount(currentState.symbol, currentWallet.getWalletBalance(currentState.symbol)), scheduleDeletion = true)
                        botUser.updateSessionState(BotSessionState.DepositAmountEntry(currentState.symbol))
                    }

                    else -> {
                        sendInvalidCommand(scheduleDeletion = true)
                    }
                }
            }

            is BotSessionState.DepositPending -> {
                when (input) {
                    is BotInput.DepositCompleted -> {
                        val deposit = input.deposit
                        val symbol = Symbol(deposit.symbol.name)
                        if (deposit.status == DepositStatus.Complete) {
                            send(
                                BotMessage.DepositSucceeded(
                                    symbol,
                                    amount = deposit.amount.fromFundamentalUnits(currentWallet.decimals(symbol)),
                                    availableBalance = currentWallet.getExchangeAvailableBalance(symbol),
                                ),
                            )
                        } else {
                            send(BotMessage.DepositFailed(symbol))
                        }
                        sendMainMenu()
                        botUser.updateSessionState(BotSessionState.MainMenu)
                    }
                    else -> {}
                }
            }

            is BotSessionState.WithdrawalSymbolSelection -> {
                when (input) {
                    is BotInput.SymbolSelected -> {
                        deleteMessages()
                        val available = currentWallet.getExchangeAvailableBalance(input.symbol)
                        if (available == BigInteger.ZERO) {
                            send(BotMessage.NoBalanceAvailable(input.symbol), scheduleDeletion = true)
                            send(BotMessage.SelectSymbol.toWithdraw(currentWallet.symbols()), scheduleDeletion = true)
                        } else {
                            send(BotMessage.EnterWithdrawalAmount(input.symbol, available))
                            botUser.updateSessionState(BotSessionState.WithdrawalAmountEntry(input.symbol))
                        }
                    }

                    is BotInput.Cancel -> {
                        deleteMessages()
                        botUser.updateSessionState(BotSessionState.MainMenu)
                    }

                    else -> {
                        sendInvalidCommand(scheduleDeletion = true)
                    }
                }
            }

            is BotSessionState.WithdrawalAmountEntry -> {
                when (input) {
                    is BotInput.Text -> {
                        if (input.text == "/cancel") {
                            deleteMessages()
                            sendMainMenu()
                            botUser.updateSessionState(BotSessionState.MainMenu)
                        } else {
                            val available = currentWallet.getExchangeAvailableBalance(currentState.symbol)
                            val amount = runCatching { BigDecimal(input.text) }.getOrNull()
                            if (amount == null) {
                                send(BotMessage.InvalidNumber(input.text))
                            } else if (amount > available) {
                                send(BotMessage.WithdrawalAmountTooLarge)
                                send(BotMessage.EnterWithdrawalAmount(currentState.symbol, available))
                            } else {
                                send(BotMessage.WithdrawalConfirmation(currentState.symbol, amount), scheduleDeletion = true)
                                botUser.updateSessionState(BotSessionState.WithdrawalConfirmation(currentState.symbol, amount))
                            }
                        }
                    }
                    else -> {
                        sendInvalidCommand(scheduleDeletion = true)
                    }
                }
            }

            is BotSessionState.WithdrawalConfirmation -> {
                when (input) {
                    is BotInput.Confirm -> {
                        deleteMessages()

                        currentWallet
                            .withdraw(currentState.amount, currentState.symbol)
                            .onLeft {
                                send(BotMessage.WithdrawalFailed(it.error))
                                sendMainMenu()
                                botUser.updateSessionState(BotSessionState.MainMenu)
                            }
                            .onRight {
                                send(BotMessage.WithdrawalInProgress)
                                botUser.updateSessionState(BotSessionState.WithdrawalPending(it.withdrawal.id))
                            }
                    }

                    is BotInput.Cancel -> {
                        deleteMessages()
                        sendMainMenu()
                        botUser.updateSessionState(BotSessionState.MainMenu)
                    }

                    is BotInput.ChangeAmount -> {
                        deleteMessages()
                        send(BotMessage.EnterWithdrawalAmount(currentState.symbol, currentWallet.getExchangeAvailableBalance(currentState.symbol)), scheduleDeletion = true)
                        botUser.updateSessionState(BotSessionState.WithdrawalAmountEntry(currentState.symbol))
                    }

                    else -> {
                        sendInvalidCommand(scheduleDeletion = true)
                    }
                }
            }

            is BotSessionState.WithdrawalPending -> {
                when (input) {
                    is BotInput.WithdrawalCompleted -> {
                        val withdrawal = input.withdrawal
                        if (withdrawal.status == WithdrawalStatus.Complete) {
                            send(BotMessage.WithdrawalSucceeded)
                        } else {
                            send(BotMessage.WithdrawalFailed(null))
                        }
                        sendMainMenu()
                        botUser.updateSessionState(BotSessionState.MainMenu)
                    }
                    else -> {}
                }
            }

            is BotSessionState.SwapFromSymbolSelection -> {
                when (input) {
                    is BotInput.SymbolSelected -> {
                        deleteMessages()
                        val available = currentWallet.getExchangeAvailableBalance(input.symbol)
                        if (available > BigDecimal.ZERO) {
                            val buySymbolOptions = currentWallet.config.markets.mapNotNull {
                                if (it.baseSymbol == input.symbol) {
                                    it.quoteSymbol
                                } else if (it.quoteSymbol == input.symbol) {
                                    it.baseSymbol
                                } else {
                                    null
                                }
                            }

                            send(BotMessage.SelectSymbol.toSwapTo(buySymbolOptions, from = input.symbol), scheduleDeletion = true)
                            botUser.updateSessionState(BotSessionState.SwapToSymbolSelection(from = input.symbol))
                        } else {
                            send(BotMessage.NoBalanceAvailable(input.symbol), scheduleDeletion = true)
                            send(BotMessage.SelectSymbol.toSwapFrom(currentWallet.symbols()), scheduleDeletion = true)
                        }
                    }

                    is BotInput.Cancel -> {
                        deleteMessages()
                        sendMainMenu()
                        botUser.updateSessionState(BotSessionState.MainMenu)
                    }

                    else -> {
                        sendInvalidCommand(scheduleDeletion = true)
                    }
                }
            }

            is BotSessionState.SwapToSymbolSelection -> {
                when (input) {
                    is BotInput.SymbolSelected -> {
                        deleteMessages()
                        send(
                            BotMessage.EnterSwapAmount(
                                from = currentState.from,
                                available = currentWallet.getExchangeAvailableBalance(currentState.from),
                                to = input.symbol,
                            ),
                        )
                        botUser.updateSessionState(BotSessionState.SwapAmountEntry(from = currentState.from, to = input.symbol))
                    }

                    is BotInput.Cancel -> {
                        deleteMessages()
                        sendMainMenu()
                        botUser.updateSessionState(BotSessionState.MainMenu)
                    }

                    else -> {
                        sendInvalidCommand(scheduleDeletion = true)
                    }
                }
            }

            is BotSessionState.SwapAmountEntry -> {
                when (input) {
                    is BotInput.Text -> {
                        if (input.text == "/cancel") {
                            deleteMessages()
                            sendMainMenu()
                            botUser.updateSessionState(BotSessionState.MainMenu)
                        } else {
                            val available = currentWallet.getExchangeAvailableBalance(currentState.from)
                            val amount = runCatching { BigDecimal(input.text) }.getOrNull()
                            if (amount == null) {
                                send(BotMessage.InvalidNumber(input.text))
                            } else if (amount > available) {
                                send(BotMessage.SwapAmountTooLarge)
                                send(BotMessage.EnterSwapAmount(from = currentState.from, available = available, to = currentState.from))
                            } else {
                                val swapEstimation = currentWallet.estimateSwap(currentState.from, currentState.to, amount)
                                send(BotMessage.SwapConfirmation(swapEstimation), scheduleDeletion = true)
                                botUser.updateSessionState(BotSessionState.SwapConfirmation(currentState.from, currentState.to, amount))
                            }
                        }
                    }
                    else -> {
                        sendInvalidCommand(scheduleDeletion = true)
                    }
                }
            }

            is BotSessionState.SwapConfirmation -> {
                when (input) {
                    is BotInput.Confirm -> {
                        deleteMessages()

                        val swapEstimation = currentWallet.estimateSwap(currentState.from, currentState.to, currentState.amount)
                        send(BotMessage.SubmittingSwap(swapEstimation))

                        currentWallet
                            .submitSwap(swapEstimation)
                            .onLeft {
                                send(BotMessage.SwapFailed(it.error))
                                sendMainMenu()
                                botUser.updateSessionState(BotSessionState.MainMenu)
                            }
                            .onRight {
                                botUser.updateSessionState(BotSessionState.SwapPending(it.orderId))
                            }
                    }

                    is BotInput.Cancel -> {
                        deleteMessages()
                        sendMainMenu()
                        botUser.updateSessionState(BotSessionState.MainMenu)
                    }

                    is BotInput.ChangeAmount -> {
                        deleteMessages()
                        send(
                            BotMessage.EnterSwapAmount(
                                from = currentState.from,
                                available = currentWallet.getExchangeAvailableBalance(currentState.from),
                                to = currentState.to,
                            ),
                            scheduleDeletion = true,
                        )
                        botUser.updateSessionState(BotSessionState.SwapAmountEntry(from = currentState.from, to = currentState.to))
                    }

                    else -> {
                        sendInvalidCommand(scheduleDeletion = true)
                    }
                }
            }

            is BotSessionState.SwapPending -> {
                when (input) {
                    is BotInput.SwapCompleted -> {
                        val order = input.order
                        send(
                            when (order.status) {
                                OrderStatus.Filled -> BotMessage.SwapSucceeded(full = true)
                                OrderStatus.Partial -> BotMessage.SwapSucceeded(full = false)
                                OrderStatus.Rejected -> BotMessage.SwapRejected
                                else -> BotMessage.SwapFailed(null)
                            },
                        )
                        sendMainMenu()
                        botUser.updateSessionState(BotSessionState.MainMenu)
                    }
                    else -> { }
                }
            }

            is BotSessionState.Settings -> {
                when (input) {
                    is BotInput.SwitchWallet -> {
                        deleteMessages()
                        send(
                            BotMessage.SelectWalletToSwitchTo(
                                wallets = botUser.wallets.map { it.address },
                                currentWallet = currentWallet.walletAddress,
                            ),
                            scheduleDeletion = true,
                        )
                        botUser.updateSessionState(BotSessionState.WalletToSwitchToSelection)
                    }
                    is BotInput.ImportWallet -> {
                        deleteMessages()
                        send(BotMessage.ImportWalletPrivateKeyEntryPrompt)
                        botUser.updateSessionState(BotSessionState.ImportWalletPrivateKeyEntry)
                    }
                    is BotInput.ExportPrivateKey -> {
                        deleteMessages()
                        send(
                            BotMessage.ShowPrivateKey(currentWallet.wallet.encryptedPrivateKey),
                            scheduleDeletion = true,
                        )
                        botUser.updateSessionState(BotSessionState.ShowingPrivateKey)
                    }
                    is BotInput.Cancel -> {
                        deleteMessages()
                        sendMainMenu()
                        botUser.updateSessionState(BotSessionState.MainMenu)
                    }
                    else -> {
                        sendInvalidCommand(scheduleDeletion = true)
                    }
                }
            }

            is BotSessionState.ShowingPrivateKey -> {
                deleteMessages()
                send(BotMessage.Settings(currentWallet.walletAddress), scheduleDeletion = true)
                botUser.updateSessionState(BotSessionState.Settings)
            }

            is BotSessionState.WalletToSwitchToSelection -> {
                when (input) {
                    is BotInput.WalletSelected -> {
                        deleteMessages()
                        val wallet = botUser.wallets
                            .find { it.address.abbreviated() == input.abbreviatedAddress }!!

                        switchWallet(wallet)
                        send(BotMessage.SwitchedToWallet(currentWallet.walletAddress))
                        send(BotMessage.Settings(currentWallet.walletAddress), scheduleDeletion = true)
                        botUser.updateSessionState(BotSessionState.Settings)
                    }
                    is BotInput.Cancel -> {
                        deleteMessages()
                        send(BotMessage.Settings(currentWallet.walletAddress), scheduleDeletion = true)
                        botUser.updateSessionState(BotSessionState.Settings)
                    }
                    else -> {
                        sendInvalidCommand(scheduleDeletion = true)
                    }
                }
            }

            is BotSessionState.ImportWalletPrivateKeyEntry -> {
                when (input) {
                    is BotInput.Text -> {
                        if (input.text == "/cancel") {
                            deleteMessages()
                            send(BotMessage.Settings(currentWallet.walletAddress), scheduleDeletion = true)
                            botUser.updateSessionState(BotSessionState.Settings)
                        } else {
                            try {
                                val privateKey = input.text
                                switchWallet(
                                    TelegramBotUserWalletEntity.create(
                                        WalletEntity.getOrCreate(Address.fromPrivateKey(privateKey)),
                                        botUser,
                                        privateKey.encrypt(),
                                        isCurrent = true,
                                    ).also {
                                        it.flush()
                                    },
                                )
                                send(BotMessage.ImportWalletSuccess(currentWallet.walletAddress))
                            } catch (e: Exception) {
                                logger.warn(e) { "Failed to import key" }
                                send(BotMessage.ImportWalletFailure)
                            }
                            botUser.messageIdsForDeletion += input.messageId
                            deleteMessages()
                            send(BotMessage.Settings(currentWallet.walletAddress), scheduleDeletion = true)
                            botUser.updateSessionState(BotSessionState.Settings)
                        }
                    }
                    else -> {
                        sendInvalidCommand(scheduleDeletion = true)
                    }
                }
            }
        }
    }

    fun refresh() {
        val botUser = TelegramBotUserEntity.getOrCreate(telegramUserId)
        when (val sessionState = botUser.sessionState) {
            is BotSessionState.AirdropPending -> {
                val txReceipt = currentWallet.getTxReceipt(sessionState.chainId, sessionState.txHash)
                if (txReceipt != null) {
                    handleInput(BotInput.AirdropTxReceipt(telegramUserId, txReceipt))
                }
            }
            is BotSessionState.DepositPending -> {
                val deposit = DepositEntity[sessionState.depositId]
                if (deposit.status == DepositStatus.Complete || deposit.status == DepositStatus.Failed) {
                    handleInput(BotInput.DepositCompleted(telegramUserId, deposit))
                }
            }
            is BotSessionState.WithdrawalPending -> {
                val withdrawal = WithdrawalEntity[sessionState.withdrawalId]
                if (withdrawal.status == WithdrawalStatus.Complete || withdrawal.status == WithdrawalStatus.Failed) {
                    handleInput(BotInput.WithdrawalCompleted(telegramUserId, withdrawal))
                }
            }
            is BotSessionState.SwapPending -> {
                val order = OrderEntity[sessionState.orderId]
                if (order.status.isFinal()) {
                    handleInput(BotInput.SwapCompleted(telegramUserId, order))
                }
            }
            else -> {}
        }
        botUser.updatedAt = Clock.System.now()
    }

    private fun sendInvalidCommand(scheduleDeletion: Boolean = false) {
        send(BotMessage.InvalidCommand, scheduleDeletion)
    }

    private fun sendMainMenu() {
        send(
            BotMessage.MainMenu(
                currentWallet.walletAddress,
                currentWallet.getWalletBalances(),
                currentWallet.getExchangeBalances(),
            ),
        )
    }

    private fun switchWallet(walletEntity: TelegramBotUserWalletEntity) {
        if (!this::currentWallet.isInitialized || currentWallet.wallet.id != walletEntity.id) {
            if (this::currentWallet.isInitialized) {
                currentWallet.stop()
                currentWallet.wallet.isCurrent = false
            }

            walletEntity.isCurrent = true

            currentWallet = BotSessionCurrentWallet(walletEntity)
            currentWallet.start()
        }
    }

    private fun deleteMessages() {
        TelegramBotUserEntity.getOrCreate(telegramUserId).apply {
            messageIdsForDeletion.forEach { messageId ->
                outputChannel.deleteMessage(BotOutput.DeleteMessage(chatId, messageId))
            }
            messageIdsForDeletion = emptyList()
        }
    }

    private fun send(message: BotMessage, scheduleDeletion: Boolean = false) =
        send(message.render(chatId), scheduleDeletion)

    private fun send(message: BotOutput.SendMessage, scheduleDeletion: Boolean = false) {
        val sentMessageId = outputChannel.sendMessage(message)
        if (scheduleDeletion) {
            TelegramBotUserEntity.getOrCreate(telegramUserId).messageIdsForDeletion += sentMessageId
        }
    }
}
