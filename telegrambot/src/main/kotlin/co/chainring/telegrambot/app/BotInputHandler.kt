package co.chainring.telegrambot.app

import arrow.core.left
import arrow.core.right
import co.chainring.apps.api.model.CreateDepositApiRequest
import co.chainring.apps.api.model.CreateWithdrawalApiRequest
import co.chainring.apps.api.model.RequestProcessingError
import co.chainring.apps.api.services.ExchangeApiService
import co.chainring.core.blockchain.ChainManager
import co.chainring.core.model.abbreviated
import co.chainring.core.model.db.DepositStatus
import co.chainring.core.model.db.OrderStatus
import co.chainring.core.model.db.SymbolEntity
import co.chainring.core.model.db.TelegramBotUserEntity
import co.chainring.core.model.db.TelegramBotUserWalletEntity
import co.chainring.core.model.db.WithdrawalStatus
import co.chainring.core.model.tgbot.BotSessionState
import co.chainring.core.model.tgbot.TelegramMessageId
import co.chainring.core.utils.fromFundamentalUnits
import co.chainring.core.utils.setScale
import co.chainring.core.utils.toFundamentalUnits
import io.github.oshai.kotlinlogging.KotlinLogging
import java.math.BigDecimal
import java.math.BigInteger

class BotInputHandler(
    private val outputChannel: Bot.OutputChannel,
    private val exchangeApiService: ExchangeApiService,
) {
    private val logger = KotlinLogging.logger { }

    fun handle(input: BotInput) {
        val user = TelegramBotUserEntity.getOrCreate(input.from)
        val currentWallet = user.currentWallet()
        val symbols = SymbolEntity.all().sortedBy { it.name }

        when (val currentState = user.sessionState) {
            is BotSessionState.Initial -> {
                when (input) {
                    is BotInput.Start -> {
                        deleteTransientMessages(user)
                        sendMainMenu(currentWallet, symbols)
                        user.updateSessionState(BotSessionState.MainMenu)
                    }
                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }

            is BotSessionState.MainMenu -> {
                when (input) {
                    is BotInput.Start -> {
                        deleteTransientMessages(user)
                        sendMainMenu(currentWallet, symbols)
                    }
                    is BotInput.Airdrop -> {
                        sendTransient(BotMessage.selectSymbolToAirdrop(user, symbols.filter { it.contractAddress == null }))
                        user.updateSessionState(BotSessionState.AirdropSymbolSelection)
                    }
                    is BotInput.Deposit -> {
                        sendTransient(BotMessage.selectSymbolToDeposit(user, symbols))
                        user.updateSessionState(BotSessionState.DepositSymbolSelection)
                    }
                    is BotInput.Withdraw -> {
                        sendTransient(BotMessage.selectSymbolToWithdraw(user, symbols))
                        user.updateSessionState(BotSessionState.WithdrawalSymbolSelection)
                    }
                    is BotInput.Swap -> {
                        sendTransient(BotMessage.selectSymbolToSwapFrom(user, symbols))
                        user.updateSessionState(BotSessionState.SwapFromSymbolSelection)
                    }
                    is BotInput.Settings -> {
                        sendTransient(BotMessage.settings(user, currentWallet.address))
                        user.updateSessionState(BotSessionState.Settings)
                    }
                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }

            is BotSessionState.AirdropSymbolSelection -> {
                when (input) {
                    is BotInput.SymbolSelected -> {
                        val symbol = SymbolEntity.forName(input.symbol)
                        deleteTransientMessages(user)
                        val amount = BigDecimal("0.1").setScale(symbol.decimals)

                        val txHash = ChainManager
                            .getBlockchainClient(symbol.chainId.value)
                            .asyncDepositNative(currentWallet.address, amount.toFundamentalUnits(symbol.decimals))

                        send(BotMessage.airdropRequested(user, symbol, amount))
                        user.updateSessionState(BotSessionState.AirdropPending(symbol, amount, txHash))
                    }

                    is BotInput.Cancel -> {
                        deleteTransientMessages(user)
                        user.updateSessionState(BotSessionState.MainMenu)
                    }

                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }

            is BotSessionState.AirdropPending -> {
                when (input) {
                    is BotInput.AirdropTxReceipt -> {
                        if (input.receipt.isStatusOK) {
                            send(BotMessage.airdropSucceeded(user, currentState.symbol, currentState.amount))
                        } else {
                            send(BotMessage.airdropTxFailed(user, input.receipt))
                        }
                        sendMainMenu(currentWallet, symbols)
                        user.updateSessionState(BotSessionState.MainMenu)
                    }
                    else -> { }
                }
            }

            is BotSessionState.DepositSymbolSelection -> {
                when (input) {
                    is BotInput.SymbolSelected -> {
                        deleteTransientMessages(user)
                        val symbol = SymbolEntity.forName(input.symbol)
                        val available = currentWallet.onChainBalance(symbol)
                        if (available > BigDecimal.ZERO) {
                            send(BotMessage.enterDepositAmount(user, symbol, available))
                            user.updateSessionState(BotSessionState.DepositAmountEntry(symbol))
                        } else {
                            sendTransient(BotMessage.noBalanceInWallet(user, input.symbol))
                            sendTransient(BotMessage.selectSymbolToDeposit(user, symbols))
                        }
                    }

                    is BotInput.Cancel -> {
                        deleteTransientMessages(user)
                        user.updateSessionState(BotSessionState.MainMenu)
                    }

                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }

            is BotSessionState.DepositAmountEntry -> {
                when (input) {
                    is BotInput.Text -> {
                        if (input.text.contains("cancel", ignoreCase = true)) {
                            deleteTransientMessages(user)
                            sendMainMenu(currentWallet, symbols)
                            user.updateSessionState(BotSessionState.MainMenu)
                        } else {
                            val available = currentWallet.onChainBalance(currentState.symbol)
                            val amount = runCatching { BigDecimal(input.text) }.getOrNull()
                            if (amount == null) {
                                send(BotMessage.invalidNumber(user, input.text))
                            } else if (amount.signum() <= 0) {
                                send(BotMessage.amountHasToBePositive(user))
                            } else if (amount > available) {
                                send(BotMessage.depositAmountTooLarge(user))
                                send(BotMessage.enterDepositAmount(user, currentState.symbol, available))
                            } else {
                                sendTransient(BotMessage.depositConfirmation(user, currentState.symbol, amount))
                                user.updateSessionState(BotSessionState.DepositConfirmation(currentState.symbol, amount))
                            }
                        }
                    }
                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }

            is BotSessionState.DepositConfirmation -> {
                when (input) {
                    is BotInput.Confirm -> {
                        try {
                            deleteTransientMessages(user)
                            val depositId = currentWallet
                                .deposit(currentState.amount, currentState.symbol)
                                ?.let { txHash ->
                                    exchangeApiService
                                        .deposit(currentWallet.address, CreateDepositApiRequest(currentState.symbol, currentState.amount, txHash))
                                        .deposit.id
                                }

                            if (depositId == null) {
                                send(BotMessage.depositFailed(user, currentState.symbol))
                                user.updateSessionState(BotSessionState.MainMenu)
                            } else {
                                send(BotMessage.depositInProgress(user))
                                user.updateSessionState(BotSessionState.DepositPending(depositId))
                            }
                        } catch (e: Exception) {
                            logger.error(e) { "Deposit failed" }
                            send(BotMessage.depositFailed(user, currentState.symbol))
                            sendMainMenu(currentWallet, symbols)
                            user.updateSessionState(BotSessionState.MainMenu)
                        }
                    }

                    is BotInput.Cancel -> {
                        deleteTransientMessages(user)
                        sendMainMenu(currentWallet, symbols)
                        user.updateSessionState(BotSessionState.MainMenu)
                    }

                    is BotInput.ChangeAmount -> {
                        deleteTransientMessages(user)
                        sendTransient(BotMessage.enterDepositAmount(user, currentState.symbol, currentWallet.onChainBalance(currentState.symbol)))
                        user.updateSessionState(BotSessionState.DepositAmountEntry(currentState.symbol))
                    }

                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }

            is BotSessionState.DepositPending -> {
                when (input) {
                    is BotInput.DepositCompleted -> {
                        val deposit = input.deposit
                        if (deposit.status == DepositStatus.Complete) {
                            send(
                                BotMessage.depositSucceeded(
                                    user,
                                    deposit.symbol,
                                    amount = deposit.amount.fromFundamentalUnits(deposit.symbol.decimals),
                                    availableBalance = currentWallet.exchangeAvailableBalance(deposit.symbol),
                                ),
                            )
                        } else {
                            send(BotMessage.depositFailed(user, deposit.symbol))
                        }
                        sendMainMenu(currentWallet, symbols)
                        user.updateSessionState(BotSessionState.MainMenu)
                    }
                    else -> {}
                }
            }

            is BotSessionState.WithdrawalSymbolSelection -> {
                when (input) {
                    is BotInput.SymbolSelected -> {
                        deleteTransientMessages(user)
                        val symbol = SymbolEntity.forName(input.symbol)
                        val available = currentWallet.exchangeAvailableBalance(symbol)
                        if (available == BigInteger.ZERO) {
                            sendTransient(BotMessage.noBalanceAvailable(user, symbol))
                            sendTransient(BotMessage.selectSymbolToWithdraw(user, symbols))
                        } else {
                            send(BotMessage.enterWithdrawalAmount(user, symbol, available))
                            user.updateSessionState(BotSessionState.WithdrawalAmountEntry(symbol))
                        }
                    }

                    is BotInput.Cancel -> {
                        deleteTransientMessages(user)
                        user.updateSessionState(BotSessionState.MainMenu)
                    }

                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }

            is BotSessionState.WithdrawalAmountEntry -> {
                when (input) {
                    is BotInput.Text -> {
                        if (input.text.contains("cancel", ignoreCase = true)) {
                            deleteTransientMessages(user)
                            sendMainMenu(currentWallet, symbols)
                            user.updateSessionState(BotSessionState.MainMenu)
                        } else {
                            val available = currentWallet.exchangeAvailableBalance(currentState.symbol)
                            val amount = runCatching { BigDecimal(input.text) }.getOrNull()
                            if (amount == null) {
                                send(BotMessage.invalidNumber(user, input.text))
                            } else if (amount.signum() <= 0) {
                                send(BotMessage.amountHasToBePositive(user))
                            } else if (amount > available) {
                                send(BotMessage.withdrawalAmountTooLarge(user))
                                send(BotMessage.enterWithdrawalAmount(user, currentState.symbol, available))
                            } else {
                                sendTransient(BotMessage.withdrawalConfirmation(user, currentState.symbol, amount))
                                user.updateSessionState(BotSessionState.WithdrawalConfirmation(currentState.symbol, amount))
                            }
                        }
                    }
                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }

            is BotSessionState.WithdrawalConfirmation -> {
                when (input) {
                    is BotInput.Confirm -> {
                        deleteTransientMessages(user)

                        val errorOrWithdrawalId = try {
                            val nonce = System.currentTimeMillis()
                            val signature = currentWallet.signWithdrawal(currentState.amount, currentState.symbol, nonce)
                            exchangeApiService.withdraw(
                                currentWallet.address,
                                CreateWithdrawalApiRequest(currentState.symbol, currentState.amount, nonce, signature),
                            ).withdrawal.id.right()
                        } catch (e: RequestProcessingError) {
                            e.error.left()
                        }

                        errorOrWithdrawalId
                            .onLeft { error ->
                                send(BotMessage.withdrawalFailed(user, error))
                                sendMainMenu(currentWallet, symbols)
                                user.updateSessionState(BotSessionState.MainMenu)
                            }
                            .onRight { withdrawalId ->
                                send(BotMessage.withdrawalInProgress(user))
                                user.updateSessionState(BotSessionState.WithdrawalPending(withdrawalId))
                            }
                    }

                    is BotInput.Cancel -> {
                        deleteTransientMessages(user)
                        sendMainMenu(currentWallet, symbols)
                        user.updateSessionState(BotSessionState.MainMenu)
                    }

                    is BotInput.ChangeAmount -> {
                        deleteTransientMessages(user)
                        sendTransient(BotMessage.enterWithdrawalAmount(user, currentState.symbol, currentWallet.exchangeAvailableBalance(currentState.symbol)))
                        user.updateSessionState(BotSessionState.WithdrawalAmountEntry(currentState.symbol))
                    }

                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }

            is BotSessionState.WithdrawalPending -> {
                when (input) {
                    is BotInput.WithdrawalCompleted -> {
                        val withdrawal = input.withdrawal
                        if (withdrawal.status == WithdrawalStatus.Complete) {
                            send(BotMessage.withdrawalSucceeded(user))
                        } else {
                            send(BotMessage.withdrawalFailed(user, null))
                        }
                        sendMainMenu(currentWallet, symbols)
                        user.updateSessionState(BotSessionState.MainMenu)
                    }
                    else -> {}
                }
            }

            is BotSessionState.SwapFromSymbolSelection -> {
                when (input) {
                    is BotInput.SymbolSelected -> {
                        deleteTransientMessages(user)
                        val symbol = SymbolEntity.forName(input.symbol)
                        val available = currentWallet.exchangeAvailableBalance(symbol)
                        if (available > BigDecimal.ZERO) {
                            sendTransient(BotMessage.selectSymbolToSwapTo(user, symbol.swapOptions(), from = symbol))
                            user.updateSessionState(BotSessionState.SwapToSymbolSelection(fromSymbol = symbol))
                        } else {
                            sendTransient(BotMessage.noBalanceAvailable(user, symbol))
                            sendTransient(BotMessage.selectSymbolToSwapFrom(user, symbols))
                        }
                    }

                    is BotInput.Cancel -> {
                        deleteTransientMessages(user)
                        sendMainMenu(currentWallet, symbols)
                        user.updateSessionState(BotSessionState.MainMenu)
                    }

                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }

            is BotSessionState.SwapToSymbolSelection -> {
                when (input) {
                    is BotInput.SymbolSelected -> {
                        deleteTransientMessages(user)
                        val symbol = SymbolEntity.forName(input.symbol)
                        send(
                            BotMessage.enterSwapAmount(
                                user,
                                from = currentState.fromSymbol,
                                available = currentWallet.exchangeAvailableBalance(currentState.fromSymbol),
                                to = symbol,
                            ),
                        )
                        user.updateSessionState(BotSessionState.SwapAmountEntry(fromSymbol = currentState.fromSymbol, toSymbol = symbol))
                    }

                    is BotInput.Cancel -> {
                        deleteTransientMessages(user)
                        sendMainMenu(currentWallet, symbols)
                        user.updateSessionState(BotSessionState.MainMenu)
                    }

                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }

            is BotSessionState.SwapAmountEntry -> {
                when (input) {
                    is BotInput.Text -> {
                        if (input.text.contains("cancel", ignoreCase = true)) {
                            deleteTransientMessages(user)
                            sendMainMenu(currentWallet, symbols)
                            user.updateSessionState(BotSessionState.MainMenu)
                        } else {
                            val available = currentWallet.exchangeAvailableBalance(currentState.fromSymbol)
                            val amount = runCatching { BigDecimal(input.text) }.getOrNull()
                            if (amount == null) {
                                send(BotMessage.invalidNumber(user, input.text))
                            } else if (amount.signum() <= 0) {
                                send(BotMessage.amountHasToBePositive(user))
                            } else if (amount > available) {
                                send(BotMessage.swapAmountTooLarge(user))
                                send(BotMessage.enterSwapAmount(user, from = currentState.fromSymbol, available = available, to = currentState.fromSymbol))
                            } else {
                                try {
                                    val swapEstimation = estimateSwap(currentState.fromSymbol, currentState.toSymbol, amount)
                                    sendTransient(BotMessage.swapConfirmation(user, swapEstimation))
                                    user.updateSessionState(BotSessionState.SwapConfirmation(currentState.fromSymbol, currentState.toSymbol, amount))
                                } catch (e: SwapEstimateError) {
                                    send(BotMessage.swapEstimateError(user, e))
                                    send(BotMessage.enterSwapAmount(user, from = currentState.fromSymbol, available = available, to = currentState.fromSymbol))
                                }
                            }
                        }
                    }
                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }

            is BotSessionState.SwapConfirmation -> {
                when (input) {
                    is BotInput.Confirm -> {
                        deleteTransientMessages(user)

                        try {
                            val swapEstimation = estimateSwap(currentState.fromSymbol, currentState.toSymbol, currentState.amount)
                            send(BotMessage.submittingSwap(user, swapEstimation))

                            val signedOrder = swapEstimation.toOrderRequest().let { order ->
                                val (signature, verifyingChainId) = currentWallet.signOrder(swapEstimation.market, order.side, order.amount, order.nonce)
                                order.copy(signature = signature, verifyingChainId = verifyingChainId)
                            }

                            val errorOrOrderId = try {
                                val response = exchangeApiService.addOrder(currentWallet.address, signedOrder)
                                response.error?.left() ?: response.orderId.right()
                            } catch (e: RequestProcessingError) {
                                e.error.left()
                            }

                            errorOrOrderId
                                .onLeft { error ->
                                    send(BotMessage.swapFailed(user, error))
                                    sendMainMenu(currentWallet, symbols)
                                    user.updateSessionState(BotSessionState.MainMenu)
                                }
                                .onRight { orderId ->
                                    user.updateSessionState(BotSessionState.SwapPending(orderId))
                                }
                        } catch (e: SwapEstimateError) {
                            send(BotMessage.swapEstimateError(user, e))
                            sendMainMenu(currentWallet, symbols)
                            user.updateSessionState(BotSessionState.MainMenu)
                        }
                    }

                    is BotInput.Cancel -> {
                        deleteTransientMessages(user)
                        sendMainMenu(currentWallet, symbols)
                        user.updateSessionState(BotSessionState.MainMenu)
                    }

                    is BotInput.ChangeAmount -> {
                        deleteTransientMessages(user)
                        sendTransient(
                            BotMessage.enterSwapAmount(
                                user,
                                from = currentState.fromSymbol,
                                available = currentWallet.exchangeAvailableBalance(currentState.fromSymbol),
                                to = currentState.toSymbol,
                            ),
                        )
                        user.updateSessionState(BotSessionState.SwapAmountEntry(fromSymbolId = currentState.fromSymbolId, toSymbolId = currentState.toSymbolId))
                    }

                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }

            is BotSessionState.SwapPending -> {
                when (input) {
                    is BotInput.SwapCompleted -> {
                        val order = input.order
                        send(
                            when (order.status) {
                                OrderStatus.Filled -> BotMessage.swapSucceeded(user, full = true)
                                OrderStatus.Partial -> BotMessage.swapSucceeded(user, full = false)
                                OrderStatus.Rejected -> BotMessage.swapRejected(user)
                                else -> BotMessage.swapFailed(user, null)
                            },
                        )
                        sendMainMenu(currentWallet, symbols)
                        user.updateSessionState(BotSessionState.MainMenu)
                    }
                    else -> { }
                }
            }

            is BotSessionState.Settings -> {
                when (input) {
                    is BotInput.SwitchWallet -> {
                        deleteTransientMessages(user)
                        sendTransient(
                            BotMessage.selectWalletToSwitchTo(
                                user,
                                wallets = user.wallets.map { it.address },
                                currentWallet = currentWallet.address,
                            ),
                        )
                        user.updateSessionState(BotSessionState.WalletToSwitchToSelection)
                    }
                    is BotInput.ImportWallet -> {
                        deleteTransientMessages(user)
                        send(BotMessage.importWalletPrivateKeyEntryPrompt(user))
                        user.updateSessionState(BotSessionState.ImportWalletPrivateKeyEntry)
                    }
                    is BotInput.ExportPrivateKey -> {
                        deleteTransientMessages(user)
                        sendTransient(BotMessage.showPrivateKey(user, currentWallet.encryptedPrivateKey))
                        user.updateSessionState(BotSessionState.ShowingPrivateKey)
                    }
                    is BotInput.Cancel -> {
                        deleteTransientMessages(user)
                        sendMainMenu(currentWallet, symbols)
                        user.updateSessionState(BotSessionState.MainMenu)
                    }
                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }

            is BotSessionState.ShowingPrivateKey -> {
                deleteTransientMessages(user)
                sendTransient(BotMessage.settings(user, currentWallet.address))
                user.updateSessionState(BotSessionState.Settings)
            }

            is BotSessionState.WalletToSwitchToSelection -> {
                when (input) {
                    is BotInput.WalletSelected -> {
                        deleteTransientMessages(user)
                        val targetWallet = user.wallets.find { it.address.abbreviated() == input.abbreviatedAddress }!!
                        if (currentWallet != targetWallet) {
                            currentWallet.isCurrent = false
                            targetWallet.isCurrent = true
                        }
                        send(BotMessage.switchedToWallet(user, targetWallet.address))
                        sendTransient(BotMessage.settings(user, targetWallet.address))
                        user.updateSessionState(BotSessionState.Settings)
                    }
                    is BotInput.Cancel -> {
                        deleteTransientMessages(user)
                        sendTransient(BotMessage.settings(user, currentWallet.address))
                        user.updateSessionState(BotSessionState.Settings)
                    }
                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }

            is BotSessionState.ImportWalletPrivateKeyEntry -> {
                when (input) {
                    is BotInput.Text -> {
                        if (input.text.contains("cancel", ignoreCase = true)) {
                            deleteTransientMessages(user)
                            sendTransient(BotMessage.settings(user, currentWallet.address))
                            user.updateSessionState(BotSessionState.Settings)
                        } else {
                            try {
                                val newCurrentWallet = user.addWallet(input.text)
                                currentWallet.isCurrent = false
                                newCurrentWallet.isCurrent = true
                                send(BotMessage.importWalletSuccess(user, newCurrentWallet.address))
                            } catch (e: Exception) {
                                logger.warn(e) { "Failed to import key" }
                                send(BotMessage.importWalletFailure(user))
                            }
                            user.messageIdsForDeletion += input.messageId
                            deleteTransientMessages(user)
                            sendTransient(BotMessage.settings(user, user.currentWallet().address))
                            user.updateSessionState(BotSessionState.Settings)
                        }
                    }
                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }
        }
    }

    private fun sendInvalidCommand(user: TelegramBotUserEntity) {
        sendTransient(BotMessage.invalidCommand(user))
    }

    private fun sendMainMenu(wallet: TelegramBotUserWalletEntity, symbols: List<SymbolEntity>) =
        send(
            BotMessage.mainMenu(
                wallet.user,
                wallet.address,
                walletOnChainBalances = wallet.onChainBalances(symbols),
                exchangeBalances = wallet.exchangeBalances(),
                airdropSupported = faucetSupported,
            ),
        )

    private fun deleteTransientMessages(user: TelegramBotUserEntity) {
        user.messageIdsForDeletion.forEach { messageId ->
            outputChannel.deleteMessage(BotOutput.DeleteMessage(user.telegramUserId.value.toString(), messageId))
        }
        user.messageIdsForDeletion = emptyList()
    }

    private fun send(message: BotOutput.SendMessage): TelegramMessageId =
        outputChannel.sendMessage(message)

    private fun sendTransient(message: BotOutput.SendMessage) {
        val sentMessageId = send(message)
        TelegramBotUserEntity.getOrCreate(message.recipient).messageIdsForDeletion += sentMessageId
    }
}
