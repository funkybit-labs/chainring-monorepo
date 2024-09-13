package xyz.funkybit.sequencer.apps

import io.github.oshai.kotlinlogging.KotlinLogging
import net.openhft.chronicle.queue.ExcerptTailer
import net.openhft.chronicle.queue.TailerDirection
import net.openhft.chronicle.queue.TailerState
import net.openhft.chronicle.queue.impl.RollingChronicleQueue
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.sequencer.core.AccountGuid
import xyz.funkybit.sequencer.core.Asset
import xyz.funkybit.sequencer.core.Clock
import xyz.funkybit.sequencer.core.FeeRate
import xyz.funkybit.sequencer.core.FeeRates
import xyz.funkybit.sequencer.core.Market
import xyz.funkybit.sequencer.core.MarketId
import xyz.funkybit.sequencer.core.SequencerState
import xyz.funkybit.sequencer.core.asBalanceChangesList
import xyz.funkybit.sequencer.core.notional
import xyz.funkybit.sequencer.core.notionalPlusFee
import xyz.funkybit.sequencer.core.sumBigIntegers
import xyz.funkybit.sequencer.core.toAccountGuid
import xyz.funkybit.sequencer.core.toAsset
import xyz.funkybit.sequencer.core.toBigDecimal
import xyz.funkybit.sequencer.core.toBigInteger
import xyz.funkybit.sequencer.core.toDecimalValue
import xyz.funkybit.sequencer.core.toIntegerValue
import xyz.funkybit.sequencer.core.toOrderGuid
import xyz.funkybit.sequencer.proto.BackToBackOrder
import xyz.funkybit.sequencer.proto.LimitsUpdate
import xyz.funkybit.sequencer.proto.Order
import xyz.funkybit.sequencer.proto.OrderBatch
import xyz.funkybit.sequencer.proto.OrderChangeRejected
import xyz.funkybit.sequencer.proto.OrderChanged
import xyz.funkybit.sequencer.proto.OrderDisposition
import xyz.funkybit.sequencer.proto.SequencerError
import xyz.funkybit.sequencer.proto.SequencerRequest
import xyz.funkybit.sequencer.proto.SequencerResponse
import xyz.funkybit.sequencer.proto.TradeCreated
import xyz.funkybit.sequencer.proto.copy
import xyz.funkybit.sequencer.proto.limitsUpdate
import xyz.funkybit.sequencer.proto.marketCreated
import xyz.funkybit.sequencer.proto.order
import xyz.funkybit.sequencer.proto.orderBatch
import xyz.funkybit.sequencer.proto.orderChanged
import xyz.funkybit.sequencer.proto.sequencerRequest
import xyz.funkybit.sequencer.proto.sequencerResponse
import xyz.funkybit.sequencer.proto.withdrawalCreated
import java.lang.Thread.UncaughtExceptionHandler
import java.math.BigInteger
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import xyz.funkybit.sequencer.core.checkpointsQueue as defaultCheckpointsQueue
import xyz.funkybit.sequencer.core.inputQueue as defaultInputQueue
import xyz.funkybit.sequencer.core.outputQueue as defaultOutputQueue

class SequencerApp(
    val clock: Clock = Clock(),
    val inputQueue: RollingChronicleQueue = defaultInputQueue,
    val outputQueue: RollingChronicleQueue = defaultOutputQueue,
    var checkpointsQueue: RollingChronicleQueue? = defaultCheckpointsQueue,
    val inSandboxMode: Boolean = System.getenv("SANDBOX_MODE").toBoolean(),
    private val strictReplayValidation: Boolean = System.getenv("STRICT_REPLAY_VALIDATION").toBoolean(),
    private val ecoMode: Boolean = System.getenv("ECO_MODE").toBoolean(),
) : BaseApp() {
    override val logger = KotlinLogging.logger {}
    private var stop = false
    private lateinit var sequencerThread: Thread
    private val state = SequencerState()

    fun processRequest(request: SequencerRequest, sequence: Long = 0L, startTime: Long = 0L): SequencerResponse {
        return when (request.type) {
            SequencerRequest.Type.AddMarket -> {
                val market = request.addMarket!!
                val marketId = MarketId(market.marketId)
                var error: SequencerError? = null
                if (state.markets.containsKey(marketId)) {
                    val currentMarket = state.markets.getValue(marketId)
                    if (market.tickSize.toBigDecimal() != currentMarket.tickSize ||
                        market.baseDecimals != currentMarket.baseDecimals ||
                        market.quoteDecimals != currentMarket.quoteDecimals
                    ) {
                        error = SequencerError.MarketExists
                    }
                } else {
                    state.addMarket(
                        Market(
                            id = marketId,
                            tickSize = market.tickSize.toBigDecimal(),
                            maxOrdersPerLevel = market.maxOrdersPerLevel,
                            baseDecimals = market.baseDecimals,
                            quoteDecimals = market.quoteDecimals,
                            minFee = if (market.hasMinFee()) market.minFee.toBigInteger() else BigInteger.ZERO,
                        ),
                    )
                }
                sequencerResponse {
                    this.guid = market.guid
                    this.sequence = sequence
                    error?.let { this.error = it } ?: run {
                        this.marketsCreated.add(
                            marketCreated {
                                this.marketId = marketId.value
                                this.tickSize = market.tickSize
                                this.baseDecimals = market.baseDecimals
                                this.quoteDecimals = market.quoteDecimals
                                this.minFee = if (market.hasMinFee()) market.minFee else BigInteger.ZERO.toIntegerValue()
                            },
                        )
                    }
                    this.createdAt = clock.currentTimeMillis()
                    this.processingTime = clock.nanoTime() - startTime
                }
            }

            SequencerRequest.Type.SetFeeRates -> {
                var error: SequencerError? = null

                val feeRates = request.feeRates
                if (feeRates == null || !FeeRate.isValid(feeRates.maker) || !FeeRate.isValid(feeRates.taker)) {
                    error = SequencerError.InvalidFeeRate
                } else {
                    state.feeRates = FeeRates(
                        maker = FeeRate(feeRates.maker),
                        taker = FeeRate(feeRates.taker),
                    )
                }

                sequencerResponse {
                    this.guid = request.guid
                    this.sequence = sequence
                    error?.let { this.error = it } ?: run {
                        this.feeRatesSet = feeRates
                    }
                    this.createdAt = clock.currentTimeMillis()
                    this.processingTime = clock.nanoTime() - startTime
                }
            }

            SequencerRequest.Type.SetWithdrawalFees -> {
                var error: SequencerError? = null

                val withdrawalFees = request.withdrawalFeesList
                if (withdrawalFees.isEmpty()) {
                    error = SequencerError.InvalidWithdrawalFee
                } else {
                    withdrawalFees.forEach {
                        state.withdrawalFees[Symbol(it.asset)] = it.value.toBigInteger()
                    }
                }

                sequencerResponse {
                    this.guid = request.guid
                    this.sequence = sequence
                    error?.let { this.error = it } ?: run {
                        this.withdrawalFeesSet.addAll(withdrawalFees)
                    }
                    this.createdAt = clock.currentTimeMillis()
                    this.processingTime = clock.nanoTime() - startTime
                }
            }

            SequencerRequest.Type.SetMarketMinFees -> {
                var error: SequencerError? = null

                val marketMinFees = request.marketMinFeesList
                if (marketMinFees.isEmpty()) {
                    error = SequencerError.InvalidMarketMinFee
                } else {
                    marketMinFees.forEach {
                        state.markets[MarketId(it.marketId)]?.minFee = it.minFee.toBigInteger()
                    }
                }

                sequencerResponse {
                    this.guid = request.guid
                    this.sequence = sequence
                    error?.let { this.error = it } ?: run {
                        this.marketMinFeesSet.addAll(marketMinFees)
                    }
                    this.createdAt = clock.currentTimeMillis()
                    this.processingTime = clock.nanoTime() - startTime
                }
            }

            SequencerRequest.Type.ApplyBackToBackOrder -> {
                val ordersChanged: MutableList<OrderChanged> = mutableListOf()
                val trades: MutableList<TradeCreated> = mutableListOf()
                val balanceChanges = mutableMapOf<Pair<AccountGuid, Asset>, BigInteger>()
                val accountsAndAssetsWithBalanceChanges: MutableSet<Pair<AccountGuid, Asset>> = mutableSetOf()
                val accountsWithLimitChanges: MutableSet<Pair<AccountGuid, MarketId>> = mutableSetOf()
                val order = request.backToBackOrder!!.order

                val error = if (request.backToBackOrder.marketIdsList.size != 2) {
                    SequencerError.InvalidBackToBackOrder
                } else {
                    val firstMarketId = MarketId(request.backToBackOrder.marketIdsList[0])
                    val firstMarket = state.markets[firstMarketId]
                    val secondMarketId = MarketId(request.backToBackOrder.marketIdsList[1])
                    val secondMarket = state.markets[secondMarketId]
                    when {
                        firstMarket == null -> SequencerError.UnknownMarket
                        secondMarket == null -> SequencerError.UnknownMarket
                        firstMarketId.quoteAsset() != secondMarketId.baseAsset() -> SequencerError.InvalidBackToBackOrder
                        firstMarketId.baseAsset() == secondMarketId.quoteAsset() -> SequencerError.InvalidBackToBackOrder
                        else -> {
                            when (order.type) {
                                Order.Type.MarketSell -> handleBackToBackSellOrder(
                                    request.backToBackOrder,
                                    firstMarket,
                                    secondMarket,
                                    ordersChanged,
                                    trades,
                                    balanceChanges,
                                    accountsAndAssetsWithBalanceChanges,
                                    accountsWithLimitChanges,
                                )

                                Order.Type.MarketBuy -> handleBackToBackBuyOrder(
                                    request.backToBackOrder,
                                    firstMarket,
                                    secondMarket,
                                    ordersChanged,
                                    trades,
                                    balanceChanges,
                                    accountsAndAssetsWithBalanceChanges,
                                    accountsWithLimitChanges,
                                )

                                else -> SequencerError.InvalidBackToBackOrder
                            }
                        }
                    }
                }
                ordersChanged.addAll(autoReduce(accountsAndAssetsWithBalanceChanges, accountsWithLimitChanges))

                sequencerResponse {
                    this.sequence = sequence
                    this.guid = request.guid
                    this.ordersChanged.addAll(ordersChanged)
                    this.tradesCreated.addAll(trades)
                    this.balancesChanged.addAll(balanceChanges.asBalanceChangesList())
                    this.limitsUpdated.addAll(calculateLimits(accountsWithLimitChanges))
                    this.error = error
                    this.createdAt = clock.currentTimeMillis()
                    this.processingTime = clock.nanoTime() - startTime
                }
            }

            SequencerRequest.Type.ApplyOrderBatch -> {
                val ordersChanged: MutableList<OrderChanged> = mutableListOf()
                var ordersChangeRejected: List<OrderChangeRejected> = emptyList()
                var trades: List<TradeCreated> = emptyList()
                val balanceChanges = mutableMapOf<Pair<AccountGuid, Asset>, BigInteger>()
                val accountsAndAssetsWithBalanceChanges: MutableSet<Pair<AccountGuid, Asset>> = mutableSetOf()
                val accountsWithLimitChanges: MutableSet<Pair<AccountGuid, MarketId>> = mutableSetOf()
                val orderBatch = request.orderBatch!!
                val error: SequencerError?
                val marketId = MarketId(orderBatch.marketId)
                val market = state.markets[marketId]
                if (market == null) {
                    error = SequencerError.UnknownMarket
                } else {
                    val adjustedOrderBatch = adjustBatchForPercentageMarketOrders(market, orderBatch)
                    error = checkLimits(market, adjustedOrderBatch)
                    if (error == null) {
                        val result = market.applyOrderBatch(adjustedOrderBatch, state.feeRates)
                        ordersChanged.addAll(result.ordersChanged)
                        ordersChangeRejected = result.ordersChangeRejected
                        trades = result.createdTrades
                        applyBalanceAndConsumptionChanges(
                            market.id,
                            result,
                            accountsAndAssetsWithBalanceChanges,
                            balanceChanges,
                            accountsWithLimitChanges,
                        )
                        ordersChanged.addAll(autoReduce(accountsAndAssetsWithBalanceChanges, accountsWithLimitChanges))
                    }
                }
                sequencerResponse {
                    this.sequence = sequence
                    this.guid = orderBatch.guid
                    this.ordersChanged.addAll(ordersChanged)
                    this.tradesCreated.addAll(trades)
                    this.balancesChanged.addAll(balanceChanges.asBalanceChangesList())
                    this.limitsUpdated.addAll(calculateLimits(accountsWithLimitChanges))
                    error?.let {
                        this.error = it
                    }
                    this.ordersChangeRejected.addAll(ordersChangeRejected)
                    market?.let {
                        this.bidOfferState = market.getBidOfferState()
                    }
                    this.createdAt = clock.currentTimeMillis()
                    this.processingTime = clock.nanoTime() - startTime
                }
            }

            SequencerRequest.Type.ApplyBalanceBatch -> {
                val balanceBatch = request.balanceBatch!!
                val balancesChanged = mutableMapOf<Pair<AccountGuid, Asset>, BigInteger>()
                val accountsWithLimitChanges: MutableSet<Pair<AccountGuid, MarketId>> = mutableSetOf()
                balanceBatch.depositsList.forEach { deposit ->
                    val account = deposit.account.toAccountGuid()
                    val asset = deposit.asset.toAsset()
                    val amount = deposit.amount.toBigInteger()
                    state.balances.getOrPut(account) { mutableMapOf() }.merge(asset, amount, ::sumBigIntegers)
                    balancesChanged.merge(Pair(account, asset), amount, ::sumBigIntegers)
                }

                val withdrawalsCreated = mutableMapOf<String, BigInteger>()
                balanceBatch.withdrawalsList.forEach { withdrawal ->
                    val withdrawalFee = state.withdrawalFees[Symbol(withdrawal.asset)] ?: BigInteger.ZERO
                    state.balances[withdrawal.account.toAccountGuid()]?.let { balanceByAsset ->
                        val asset = withdrawal.asset.toAsset()
                        val requestedAmount = withdrawal.amount.toBigInteger()
                        val balance = balanceByAsset[withdrawal.asset.toAsset()] ?: BigInteger.ZERO
                        val withdrawalAmount = if (requestedAmount == BigInteger.ZERO) balance else requestedAmount
                        if (withdrawalAmount > withdrawalFee && withdrawalAmount <= balance) {
                            val account = withdrawal.account.toAccountGuid()
                            balanceByAsset.merge(asset, -withdrawalAmount, ::sumBigIntegers)
                            balancesChanged.merge(Pair(account, asset), -withdrawalAmount, ::sumBigIntegers)
                            withdrawalsCreated[withdrawal.externalGuid] = withdrawalFee
                        }
                    }
                }

                balanceBatch.failedWithdrawalsList.forEach { failedWithdrawal ->
                    val account = failedWithdrawal.account.toAccountGuid()
                    val asset = failedWithdrawal.asset.toAsset()
                    val amount = failedWithdrawal.amount.toBigInteger()
                    state.balances.getOrPut(account) { mutableMapOf() }.merge(asset, amount, ::sumBigIntegers)
                    balancesChanged.merge(Pair(account, asset), amount, ::sumBigIntegers)
                }

                balanceBatch.failedSettlementsList.forEach { failedSettlement ->
                    val marketId = MarketId(failedSettlement.marketId)
                    state.markets[marketId]?.let { market ->
                        val baseAsset = market.id.baseAsset()
                        val quoteAsset = market.id.quoteAsset()
                        val baseAmount = failedSettlement.trade.amount.toBigInteger()
                        val price = market.price(failedSettlement.trade.levelIx)
                        val notional = notional(baseAmount, price, market.baseDecimals, market.quoteDecimals)

                        val sellAccount = failedSettlement.sellAccount.toAccountGuid()
                        val sellerBaseRefund = baseAmount
                        val sellerQuoteRefund = (notional - failedSettlement.trade.sellerFee.toBigInteger()).negate()

                        val buyAccount = failedSettlement.buyAccount.toAccountGuid()
                        val buyerBaseRefund = baseAmount.negate()
                        val buyerQuoteRefund = notional + failedSettlement.trade.buyerFee.toBigInteger()

                        state.balances.getOrPut(sellAccount) { mutableMapOf() }.merge(baseAsset, sellerBaseRefund, ::sumBigIntegers)
                        balancesChanged.merge(Pair(sellAccount, baseAsset), sellerBaseRefund, ::sumBigIntegers)

                        state.balances.getOrPut(sellAccount) { mutableMapOf() }.merge(quoteAsset, sellerQuoteRefund, ::sumBigIntegers)
                        balancesChanged.merge(Pair(sellAccount, quoteAsset), sellerQuoteRefund, ::sumBigIntegers)

                        state.balances.getOrPut(buyAccount) { mutableMapOf() }.merge(baseAsset, buyerBaseRefund, ::sumBigIntegers)
                        balancesChanged.merge(Pair(buyAccount, baseAsset), buyerBaseRefund, ::sumBigIntegers)

                        state.balances.getOrPut(buyAccount) { mutableMapOf() }.merge(quoteAsset, buyerQuoteRefund, ::sumBigIntegers)
                        balancesChanged.merge(Pair(buyAccount, quoteAsset), buyerQuoteRefund, ::sumBigIntegers)
                    }
                }

                balancesChanged.keys.forEach { (account, asset) ->
                    state
                        .getMarketIdsByAsset(asset)
                        .forEach { marketId ->
                            accountsWithLimitChanges.add(Pair(account, marketId))
                        }
                }

                sequencerResponse {
                    this.guid = balanceBatch.guid
                    this.sequence = sequence
                    this.balancesChanged.addAll(balancesChanged.asBalanceChangesList())
                    this.ordersChanged.addAll(autoReduce(balancesChanged.keys, accountsWithLimitChanges))
                    this.withdrawalsCreated.addAll(
                        withdrawalsCreated.map {
                            withdrawalCreated {
                                this.externalGuid = it.key
                                this.fee = it.value.toIntegerValue()
                            }
                        },
                    )
                    this.limitsUpdated.addAll(calculateLimits(accountsWithLimitChanges))
                    this.createdAt = clock.currentTimeMillis()
                    this.processingTime = clock.nanoTime() - startTime
                }
            }

            SequencerRequest.Type.Reset -> {
                if (inSandboxMode) {
                    state.clear()
                    sequencerResponse {
                        this.sequence = sequence
                        this.guid = request.guid
                        this.createdAt = clock.currentTimeMillis()
                        this.processingTime = clock.nanoTime() - startTime
                    }
                } else {
                    sequencerResponse {
                        this.sequence = sequence
                        this.guid = request.guid
                        this.error = SequencerError.UnknownRequest
                        this.createdAt = clock.currentTimeMillis()
                        this.processingTime = clock.nanoTime() - startTime
                    }
                }
            }

            SequencerRequest.Type.GetState -> {
                if (inSandboxMode) {
                    sequencerResponse {
                        this.sequence = sequence
                        this.guid = request.guid
                        this.stateDump = state.getDump()
                        this.createdAt = clock.currentTimeMillis()
                        this.processingTime = clock.nanoTime() - startTime
                    }
                } else {
                    sequencerResponse {
                        this.sequence = sequence
                        this.guid = request.guid
                        this.error = SequencerError.UnknownRequest
                        this.createdAt = clock.currentTimeMillis()
                        this.processingTime = clock.nanoTime() - startTime
                    }
                }
            }

            SequencerRequest.Type.AuthorizeWallet -> {
                sequencerResponse {
                    this.sequence = sequence
                    this.guid = request.guid
                    this.createdAt = clock.currentTimeMillis()
                    this.processingTime = clock.nanoTime() - startTime
                }
            }

            null, SequencerRequest.Type.Unparseable, SequencerRequest.Type.UNRECOGNIZED -> {
                sequencerResponse {
                    this.sequence = sequence
                    this.guid = request.guid
                    this.error = SequencerError.UnknownRequest
                    this.createdAt = clock.currentTimeMillis()
                    this.processingTime = clock.nanoTime() - startTime
                }
            }
        }
    }

    private fun applyBalanceAndConsumptionChanges(
        marketId: MarketId,
        result: Market.AddOrdersResult,
        accountsAndAssetsWithBalanceChanges: MutableSet<Pair<AccountGuid, Asset>>,
        balanceChanges: MutableMap<Pair<AccountGuid, Asset>, BigInteger>,
        accountsWithLimitChanges: MutableSet<Pair<AccountGuid, MarketId>>,
    ) {
        // apply balance changes
        result.balanceChanges.forEach {
            val asset = Asset(it.asset)
            val account = AccountGuid(it.account)
            val accountAndAsset = Pair(account, asset)
            val delta = it.delta.toBigInteger()

            balanceChanges.merge(accountAndAsset, delta, ::sumBigIntegers)

            state
                .balances
                .getOrPut(account) { mutableMapOf() }
                .merge(asset, delta) { a, b -> BigInteger.ZERO.max(a + b) }

            accountsAndAssetsWithBalanceChanges.add(accountAndAsset)

            state
                .getMarketIdsByAsset(asset)
                .forEach { marketId ->
                    accountsWithLimitChanges.add(Pair(account, marketId))
                }
        }

        // apply consumption changes
        result.consumptionChanges.forEach {
            if (it.delta != BigInteger.ZERO) {
                state.consumed.getOrPut(it.account) {
                    mutableMapOf()
                }.getOrPut(it.asset) {
                    mutableMapOf()
                }.merge(marketId, it.delta, ::sumBigIntegers)
                accountsWithLimitChanges.add(Pair(it.account, marketId))
            }
        }
    }

    private fun calculateLimits(accountsWithLimitChanges: Set<Pair<AccountGuid, MarketId>>): List<LimitsUpdate> =
        accountsWithLimitChanges
            .map { (account, marketId) ->
                limitsUpdate {
                    this.account = account.value
                    this.marketId = marketId.value
                    val (baseAsset, quoteAsset) = marketId.assets()
                    this.base = ((state.balances[account]?.get(baseAsset) ?: BigInteger.ZERO) - (state.consumed[account]?.get(baseAsset)?.get(marketId) ?: BigInteger.ZERO)).toIntegerValue()
                    this.quote = ((state.balances[account]?.get(quoteAsset) ?: BigInteger.ZERO) - (state.consumed[account]?.get(quoteAsset)?.get(marketId) ?: BigInteger.ZERO)).toIntegerValue()
                }
            }
            .sortedWith(compareBy(LimitsUpdate::getAccount, LimitsUpdate::getMarketId))

    private fun handleBackToBackSellOrder(
        request: BackToBackOrder,
        firstMarket: Market,
        secondMarket: Market,
        ordersChanged: MutableList<OrderChanged>,
        trades: MutableList<TradeCreated>,
        balanceChanges: MutableMap<Pair<AccountGuid, Asset>, BigInteger>,
        accountsAndAssetsWithBalanceChanges: MutableSet<Pair<AccountGuid, Asset>>,
        accountsWithLimitChanges: MutableSet<Pair<AccountGuid, MarketId>>,
    ): SequencerError {
        val account = request.account.toAccountGuid()
        val order = request.order
        val startingAmount = if (order.hasPercentage() && order.percentage > 0) {
            calculateAmountForPercentageSell(
                firstMarket,
                account,
                order.percentage,
            )
        } else {
            order.amount.toBigInteger()
        }

        val (quantity, bridgeAssetReceived) = firstMarket.quantityAndNotionalForMarketSell(startingAmount)
        val bridgeAssetAvailable = secondMarket.clearingQuantityForMarketSell(bridgeAssetReceived)
        if (quantity == BigInteger.ZERO || bridgeAssetAvailable == BigInteger.ZERO) {
            return SequencerError.InvalidBackToBackOrder
        }
        val (quantityForFirstOrder, quantityForSecondOrder) = if (bridgeAssetReceived != bridgeAssetAvailable) {
            val newQuantity = firstMarket.quantityForMarketSell(bridgeAssetAvailable)
            firstMarket.quantityAndNotionalForMarketSell(newQuantity)
        } else {
            Pair(quantity, bridgeAssetReceived)
        }

        val firstOrderBatch = orderBatch {
            this.guid = request.guid
            this.account = account.value
            this.wallet = request.wallet
            this.marketId = firstMarket.id.value
            this.ordersToAdd.add(
                order {
                    this.guid = order.guid
                    this.type = order.type
                    this.amount = quantityForFirstOrder.toIntegerValue()
                    if (order.hasPercentage()) {
                        this.percentage = percentage
                    }
                },
            )
        }

        checkLimits(firstMarket, firstOrderBatch)?.let {
            return it
        }

        // make sure 2nd leg meets min fee requirement.
        val secondOrder = order {
            this.guid = order.guid
            this.type = order.type
            this.amount = quantityForSecondOrder.toIntegerValue()
        }
        if (secondMarket.isBelowMinFee(secondOrder, state.feeRates)) {
            ordersChanged.add(
                orderChanged {
                    this.guid = order.guid
                    this.disposition = OrderDisposition.Rejected
                },
            )
            return SequencerError.None
        }

        val firstOrderResult = firstMarket.applyOrderBatch(
            firstOrderBatch,
            FeeRates(state.feeRates.maker, FeeRate(0)),
        )
        val disposition = firstOrderResult.ordersChanged.firstOrNull()?.disposition
        if (disposition == OrderDisposition.Filled || disposition == OrderDisposition.PartiallyFilled) {
            applyBalanceAndConsumptionChanges(
                firstMarket.id,
                firstOrderResult,
                accountsAndAssetsWithBalanceChanges,
                balanceChanges,
                accountsWithLimitChanges,
            )
            ordersChanged.addAll(firstOrderResult.ordersChanged.filterNot { it.guid == order.guid })
            trades.addAll(firstOrderResult.createdTrades)

            val secondOrderResult = secondMarket.applyOrderBatch(
                orderBatch {
                    this.guid = request.guid
                    this.account = account.value
                    this.wallet = request.wallet
                    this.marketId = secondMarket.id.value
                    this.ordersToAdd.add(secondOrder)
                },
                state.feeRates,
            )
            applyBalanceAndConsumptionChanges(
                secondMarket.id,
                secondOrderResult,
                accountsAndAssetsWithBalanceChanges,
                balanceChanges,
                accountsWithLimitChanges,
            )
            ordersChanged.addAll(secondOrderResult.ordersChanged.filterNot { it.guid == order.guid })
            ordersChanged.add(
                orderChanged {
                    this.guid = order.guid
                    this.disposition = if (quantityForFirstOrder != startingAmount) OrderDisposition.PartiallyFilled else OrderDisposition.Filled
                    if (order.hasPercentage()) {
                        this.newQuantity = startingAmount.toIntegerValue()
                    }
                },
            )
            trades.addAll(secondOrderResult.createdTrades)
        } else {
            ordersChanged.addAll(firstOrderResult.ordersChanged)
        }
        return SequencerError.None
    }

    private fun handleBackToBackBuyOrder(
        request: BackToBackOrder,
        firstMarket: Market,
        secondMarket: Market,
        ordersChanged: MutableList<OrderChanged>,
        trades: MutableList<TradeCreated>,
        balanceChanges: MutableMap<Pair<AccountGuid, Asset>, BigInteger>,
        accountsAndAssetsWithBalanceChanges: MutableSet<Pair<AccountGuid, Asset>>,
        accountsWithLimitChanges: MutableSet<Pair<AccountGuid, MarketId>>,
    ): SequencerError {
        val account = request.account.toAccountGuid()
        val order = request.order

        val (startingAmount, maxAvailable) = if (order.hasPercentage() && order.percentage > 0) {
            val (amount, maxAvailable) = calculateAmountForPercentageBuy(
                secondMarket,
                account,
                order.percentage,
            )
            Pair(firstMarket.quantityForMarketBuy(amount), maxAvailable)
        } else {
            Pair(order.amount.toBigInteger(), null)
        }

        // determine how much of the bridge asset we need to buy the base asset
        val (quantity, bridgeAssetNeeded) = firstMarket.quantityAndNotionalForMarketBuy(startingAmount)
        // now see how much of the bridge asset is available in the 2nd market
        val (_, bridgeAssetAvailable) = secondMarket.clearingPriceAndQuantityForMarketBuy(
            bridgeAssetNeeded,
        )
        if (quantity == BigInteger.ZERO || bridgeAssetAvailable == BigInteger.ZERO) {
            return SequencerError.InvalidBackToBackOrder
        }
        // if there is less available then we need, then adjust the quantity of 2nd order to fit
        val quantityForSecondOrder = if (bridgeAssetAvailable < bridgeAssetNeeded) {
            firstMarket.quantityForMarketBuy(bridgeAssetAvailable)
        } else {
            quantity
        }

        val firstOrderBatch = orderBatch {
            this.guid = request.guid
            this.account = account.value
            this.wallet = request.wallet
            this.marketId = secondMarket.id.value
            this.ordersToAdd.add(
                order {
                    this.guid = order.guid
                    this.type = order.type
                    this.amount = bridgeAssetAvailable.toIntegerValue()
                    if (order.hasPercentage()) {
                        this.percentage = order.percentage
                    }
                    maxAvailable?.let { this.maxAvailable = it.toIntegerValue() }
                },
            )
        }

        checkLimits(secondMarket, firstOrderBatch)?.let {
            return it
        }

        val firstOrderResult =
            secondMarket.applyOrderBatch(firstOrderBatch, state.feeRates)
        val disposition = firstOrderResult.ordersChanged.firstOrNull()?.disposition
        if (disposition == OrderDisposition.Filled || disposition == OrderDisposition.PartiallyFilled) {
            applyBalanceAndConsumptionChanges(
                secondMarket.id,
                firstOrderResult,
                accountsAndAssetsWithBalanceChanges,
                balanceChanges,
                accountsWithLimitChanges,
            )
            ordersChanged.addAll(firstOrderResult.ordersChanged.filterNot { it.guid == order.guid })
            trades.addAll(firstOrderResult.createdTrades)

            val secondOrderResult = firstMarket.applyOrderBatch(
                orderBatch {
                    this.guid = request.guid
                    this.account = account.value
                    this.wallet = request.wallet
                    this.marketId = firstMarket.id.value
                    this.ordersToAdd.add(
                        order {
                            this.guid = order.guid
                            this.type = order.type
                            this.amount = quantityForSecondOrder.toIntegerValue()
                        },
                    )
                },
                FeeRates(state.feeRates.maker, FeeRate(0)),
            )
            applyBalanceAndConsumptionChanges(
                firstMarket.id,
                secondOrderResult,
                accountsAndAssetsWithBalanceChanges,
                balanceChanges,
                accountsWithLimitChanges,
            )
            ordersChanged.addAll(secondOrderResult.ordersChanged.filterNot { it.guid == order.guid })
            ordersChanged.add(
                orderChanged {
                    this.guid = order.guid
                    this.disposition =
                        if (quantityForSecondOrder != startingAmount) OrderDisposition.PartiallyFilled else OrderDisposition.Filled
                    if (order.hasPercentage()) {
                        this.newQuantity = startingAmount.toIntegerValue()
                    }
                },
            )
            trades.addAll(secondOrderResult.createdTrades)
        } else {
            ordersChanged.addAll(firstOrderResult.ordersChanged)
        }

        return SequencerError.None
    }

    private fun adjustBatchForPercentageMarketOrders(market: Market, orderBatch: OrderBatch): OrderBatch {
        return if (orderBatch.ordersToAddList.any { isOrderWithPercentage(it) }) {
            orderBatch.copy {
                this.ordersToAdd.clear()
                this.ordersToAdd.addAll(
                    orderBatch.ordersToAddList.map { order ->
                        if (isOrderWithPercentage(order)) {
                            val account = orderBatch.account.toAccountGuid()
                            order.copy {
                                this.amount = if (order.type == Order.Type.MarketSell) {
                                    calculateAmountForPercentageSell(
                                        market,
                                        account,
                                        order.percentage,
                                    ).toIntegerValue()
                                } else {
                                    val (amount, maxAvailable) = calculateAmountForPercentageBuy(
                                        market,
                                        account,
                                        order.percentage,
                                    )
                                    maxAvailable?.let { this.maxAvailable = it.toIntegerValue() }
                                    amount.toIntegerValue()
                                }
                            }
                        } else {
                            order
                        }
                    },
                )
            }
        } else {
            orderBatch
        }
    }

    private fun autoReduce(
        accountsAndAssets: Collection<Pair<AccountGuid, Asset>>,
        accountsWithLimitChanges: MutableSet<Pair<AccountGuid, MarketId>>,
    ): List<OrderChanged> {
        return accountsAndAssets.flatMap { (account, asset) ->
            state.consumed[account]?.get(asset)?.flatMap { (marketId, amount) ->
                val balance = state.balances[account]?.get(asset) ?: BigInteger.ZERO
                if (amount > balance) {
                    val changedOrders = state.markets[marketId]?.autoReduce(account, asset, balance) ?: emptyList()
                    state.consumed.getValue(account).getValue(asset)[marketId] = balance
                    accountsWithLimitChanges.add(Pair(account, marketId))
                    changedOrders
                } else {
                    emptyList()
                }
            }?.sortedBy { it.guid } ?: emptyList()
        }
    }

    private fun isOrderWithPercentage(order: Order) = order.hasPercentage() && order.percentage != 0 && (order.type == Order.Type.MarketSell || order.type == Order.Type.MarketBuy)

    private fun checkLimits(market: Market, orderBatch: OrderBatch): SequencerError? {
        // compute cumulative assets required change from applying all orders in order batch
        val baseAssetsRequired = mutableMapOf<AccountGuid, BigInteger>()
        val quoteAssetsRequired = mutableMapOf<AccountGuid, BigInteger>()
        orderBatch.ordersToAddList.forEach { order ->
            when (order.type) {
                Order.Type.LimitSell, Order.Type.MarketSell -> {
                    baseAssetsRequired.merge(orderBatch.account.toAccountGuid(), order.amount.toBigInteger(), ::sumBigIntegers)
                }
                Order.Type.LimitBuy -> {
                    val notionalAndFee = calculateLimitBuyOrderNotionalPlusFee(order, market)
                    quoteAssetsRequired.merge(orderBatch.account.toAccountGuid(), notionalAndFee, ::sumBigIntegers)
                }
                Order.Type.MarketBuy -> {
                    // the quote assets required for a market buy depends on what the clearing price would be
                    val (clearingPrice, availableQuantity) = market.clearingPriceAndQuantityForMarketBuy(order.amount.toBigInteger())
                    quoteAssetsRequired.merge(
                        orderBatch.account.toAccountGuid(),
                        notionalPlusFee(availableQuantity, clearingPrice, market.baseDecimals, market.quoteDecimals, state.feeRates.taker),
                        ::sumBigIntegers,
                    )
                }
                else -> {}
            }
        }
        orderBatch.ordersToCancelList.forEach { cancelOrder ->
            market.ordersByGuid[cancelOrder.guid.toOrderGuid()]?.let { order ->
                val (baseAssets, quoteAssets) = market.assetsReservedForOrder(order)
                if (baseAssets > BigInteger.ZERO) {
                    baseAssetsRequired.merge(order.account, -baseAssets, ::sumBigIntegers)
                }
                if (quoteAssets > BigInteger.ZERO) {
                    quoteAssetsRequired.merge(order.account, -quoteAssets, ::sumBigIntegers)
                }
            }
        }

        baseAssetsRequired.forEach { (account, required) ->
            val baseRequired = market.baseAssetsRequired(account)
            val baseBalance = state.balances[account]?.get(market.id.baseAsset()) ?: BigInteger.ZERO
            if (required + baseRequired > baseBalance) {
                logger.debug { "Account $account requires $required + $baseRequired = ${required + baseRequired} but only has $baseBalance" }
                return SequencerError.ExceedsLimit
            }
        }

        quoteAssetsRequired.forEach { (account, required) ->
            val quoteRequired = market.quoteAssetsRequired(account)
            val quoteBalance = state.balances[account]?.get(market.id.quoteAsset()) ?: BigInteger.ZERO
            if (required + quoteRequired > quoteBalance) {
                logger.debug { "Account $account requires $required + $quoteRequired = ${required + quoteRequired} but only has $quoteBalance" }
                return SequencerError.ExceedsLimit
            }
        }

        return null
    }

    private fun calculateAmountForPercentageSell(market: Market, account: AccountGuid, percent: Int): BigInteger {
        return market.calculateAmountForPercentageSell(
            account,
            state.balances[account]?.get(market.id.baseAsset()) ?: BigInteger.ZERO,
            percent,
        )
    }

    private fun calculateAmountForPercentageBuy(market: Market, account: AccountGuid, percent: Int): Pair<BigInteger, BigInteger?> {
        return market.calculateAmountForPercentageBuy(
            account,
            state.balances[account]?.get(market.id.quoteAsset()) ?: BigInteger.ZERO,
            percent,
            state.feeRates.taker.value.toBigInteger(),
        )
    }

    private fun calculateLimitBuyOrderNotionalPlusFee(order: Order, market: Market): BigInteger {
        val orderPrice = market.price(order.levelIx)
        return if (order.levelIx >= market.bestOfferIx) {
            // limit order crosses the market
            val (clearingPrice, availableQuantity) = market.clearingPriceAndQuantityForMarketBuy(order.amount.toBigInteger(), stopAtLevelIx = order.levelIx)
            val remainingQuantity = order.amount.toBigInteger() - availableQuantity

            val marketChunkNotional = notionalPlusFee(availableQuantity, clearingPrice, market.baseDecimals, market.quoteDecimals, state.feeRates.taker)
            val limitChunkNotional = notionalPlusFee(remainingQuantity, orderPrice, market.baseDecimals, market.quoteDecimals, state.feeRates.maker)

            marketChunkNotional + limitChunkNotional
        } else {
            notionalPlusFee(order.amount, orderPrice.toDecimalValue(), market.baseDecimals, market.quoteDecimals, state.feeRates.maker)
        }
    }

    override fun start() {
        logger.info { "Starting${if (inSandboxMode) " in sandbox mode" else ""}" }

        stop = false
        sequencerThread = thread(start = false, name = "sequencer", isDaemon = false) {
            val inputTailer = inputQueue.createTailer("sequencer")

            checkpointsQueue?.let { restoreFromCheckpoint(inputTailer, it) }

            val lastSequenceNumberProcessedBeforeRestart = getLastSequenceNumberInOutputQueue()
            var requestsProcessedSinceStarted: Long = 0

            val outputAppender = outputQueue.acquireAppender()
            var tailerPrevState = inputTailer.state()
            while (!stop) {
                val tailerState = inputTailer.state()
                if (tailerState == TailerState.END_OF_CYCLE && tailerState != tailerPrevState) {
                    checkpointsQueue?.let {
                        saveCheckpoint(it, inputTailer.cycle())
                    }
                }

                inputTailer.readingDocument().use { dc ->
                    if (dc.isPresent) {
                        val startTime = clock.nanoTime()
                        dc.wire()?.read()?.bytes { bytes ->
                            val request = runCatching {
                                SequencerRequest.parseFrom(bytes.toByteArray())
                            }.getOrDefault(
                                sequencerRequest {
                                    type = SequencerRequest.Type.Unparseable
                                    guid = ""
                                },
                            )
                            val response = processRequest(request, dc.index(), startTime)

                            if (strictReplayValidation && response.sequence <= lastSequenceNumberProcessedBeforeRestart) {
                                // validate actual response matches expected while replaying requests
                                loadResponseFromOutputQueue(response.sequence)?.let {
                                    val expectedResponse = it.toBuilder()
                                        .setProcessingTime(response.processingTime)
                                        .setCreatedAt(response.createdAt)
                                        .build()

                                    if (request.type != SequencerRequest.Type.GetState && response != expectedResponse) {
                                        logger.error { "Actual response did not match expected, exiting. Sequence: ${dc.index()}, requests processed since start: $requestsProcessedSinceStarted, request: $request, expected response: $expectedResponse, actual response: $response" }
                                        exitProcess(1)
                                    }
                                }
                                if (requestsProcessedSinceStarted.toInt() % 1000 == 0) {
                                    logger.info { "Replayed and validated $requestsProcessedSinceStarted requests" }
                                }
                            }

                            requestsProcessedSinceStarted += 1
                            if (response.sequence == lastSequenceNumberProcessedBeforeRestart) {
                                logger.info { "Caught up after re-processing $requestsProcessedSinceStarted requests" }
                            }
                            if (response.sequence > lastSequenceNumberProcessedBeforeRestart) {
                                outputAppender.writingDocument().use {
                                    it.wire()?.write()?.bytes(response.toByteArray())
                                }
                            }
                        }
                    }
                }

                tailerPrevState = tailerState

                if (ecoMode) {
                    Thread.sleep(10)
                }
            }
        }
        sequencerThread.uncaughtExceptionHandler = UncaughtExceptionHandler { _, throwable ->
            logger.error(throwable) { "Error in sequencer main thread" }
            stop()
        }
        sequencerThread.start()

        logger.info { "Started" }
    }

    private fun getLastSequenceNumberInOutputQueue(): Long =
        outputQueue.createTailer().let { outputTailer ->
            var result = -1L
            outputTailer.moveToIndex(outputQueue.lastIndex())
            outputTailer.readingDocument().use {
                if (it.isPresent) {
                    it.wire()?.read()?.bytes { bytes ->
                        result = SequencerResponse.parseFrom(bytes.toByteArray()).sequence
                    }
                }
            }
            result
        }

    private lateinit var outputTailer: ExcerptTailer
    private fun loadResponseFromOutputQueue(sequence: Long): SequencerResponse? {
        if (!::outputTailer.isInitialized) {
            outputTailer = outputQueue.createTailer()
        }

        var result: SequencerResponse? = null
        outputTailer.moveToIndex(sequence)
        outputTailer.readingDocument().use {
            if (it.isPresent) {
                it.wire()?.read()?.bytes { bytes ->
                    result = SequencerResponse.parseFrom(bytes.toByteArray())
                }
            }
        }
        return result
    }

    private fun saveCheckpoint(checkpointsQueue: RollingChronicleQueue, currentCycle: Int) {
        logger.debug { "Saving checkpoint for cycle $currentCycle" }
        state.persist(checkpointsQueue, currentCycle)
        logger.debug { "Saved checkpoint" }
    }

    private fun restoreFromCheckpoint(inputTailer: ExcerptTailer, checkpointsQueue: RollingChronicleQueue) {
        val currentCycle = inputTailer.cycle()

        // moveToCycle moves tailer to the start of the cycle
        if (inputTailer.moveToCycle(currentCycle)) {
            // restore from previous cycle's checkpoint unless we are in the first cycle
            if (currentCycle != inputQueue.firstCycle()) {
                var expectedCycle = inputQueue.nextCycle(currentCycle, TailerDirection.BACKWARD)
                while (expectedCycle != inputQueue.firstCycle()) {
                    logger.debug { "Restoring from checkpoint with expected cycle $expectedCycle" }
                    try {
                        state.load(
                            checkpointsQueue,
                            expectedCycle = expectedCycle,
                        )
                        break
                    } catch (e: Exception) {
                        if (e.message?.contains("Invalid cycle in the checkpoint") == true) {
                            inputTailer.moveToCycle(expectedCycle)
                            expectedCycle = inputQueue.nextCycle(expectedCycle, TailerDirection.BACKWARD)
                            logger.debug { "Could not find expected cycle, going backwards" }
                        } else {
                            throw(e)
                        }
                    }
                }
                logger.debug { "Restored from checkpoint" }
            }
        }
    }

    override fun stop() {
        logger.info { "Stopping" }
        stop = true
        sequencerThread.join(100)
        sequencerThread.stop()
        logger.info { "Stopped" }
    }

    fun blockUntilShutdown() {
        while (!stop) {
            Thread.sleep(20)
        }
        sequencerThread.join()
    }
}
