package xyz.funkybit.apps.api.services

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.with
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.apps.api.model.ApiError
import xyz.funkybit.apps.api.model.BatchOrdersApiRequest
import xyz.funkybit.apps.api.model.BatchOrdersApiResponse
import xyz.funkybit.apps.api.model.CancelOrderApiRequest
import xyz.funkybit.apps.api.model.CancelOrderApiResponse
import xyz.funkybit.apps.api.model.CreateDepositApiRequest
import xyz.funkybit.apps.api.model.CreateOrderApiRequest
import xyz.funkybit.apps.api.model.CreateOrderApiResponse
import xyz.funkybit.apps.api.model.CreateWithdrawalApiRequest
import xyz.funkybit.apps.api.model.Deposit
import xyz.funkybit.apps.api.model.DepositApiResponse
import xyz.funkybit.apps.api.model.Market
import xyz.funkybit.apps.api.model.OrderAmount
import xyz.funkybit.apps.api.model.ReasonCode
import xyz.funkybit.apps.api.model.RequestProcessingError
import xyz.funkybit.apps.api.model.RequestStatus
import xyz.funkybit.apps.api.model.Withdrawal
import xyz.funkybit.apps.api.model.WithdrawalApiResponse
import xyz.funkybit.core.evm.ECHelper
import xyz.funkybit.core.evm.EIP712Helper
import xyz.funkybit.core.evm.EIP712Transaction
import xyz.funkybit.core.evm.TokenAddressAndChain
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.EvmSignature
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.DeployedSmartContractEntity
import xyz.funkybit.core.model.db.DepositEntity
import xyz.funkybit.core.model.db.DepositException
import xyz.funkybit.core.model.db.MarketEntity
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.OrderEntity
import xyz.funkybit.core.model.db.OrderId
import xyz.funkybit.core.model.db.OrderSide
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.TestnetChallengeStatus
import xyz.funkybit.core.model.db.UserId
import xyz.funkybit.core.model.db.WalletEntity
import xyz.funkybit.core.model.db.WithdrawalEntity
import xyz.funkybit.core.sequencer.SequencerClient
import xyz.funkybit.core.sequencer.toSequencerId
import xyz.funkybit.core.services.LinkedSignerService
import xyz.funkybit.core.utils.TestnetChallengeUtils
import xyz.funkybit.core.utils.bitcoin.BitcoinSignatureVerification
import xyz.funkybit.core.utils.bitcoin.fromSatoshi
import xyz.funkybit.core.utils.fromFundamentalUnits
import xyz.funkybit.core.utils.safeToInt
import xyz.funkybit.core.utils.toFundamentalUnits
import xyz.funkybit.core.utils.toHexBytes
import xyz.funkybit.sequencer.core.Asset
import xyz.funkybit.sequencer.proto.OrderChangeRejected.Reason
import xyz.funkybit.sequencer.proto.SequencerError
import java.math.BigDecimal
import java.math.BigInteger

class ExchangeApiService(
    private val sequencerClient: SequencerClient,
) {
    private val symbolMap = mutableMapOf<Symbol, SymbolEntity>()
    private val marketMap = mutableMapOf<MarketId, Market>()
    private val logger = KotlinLogging.logger {}

    fun addOrder(
        wallet: WalletEntity,
        apiRequest: CreateOrderApiRequest,
    ): CreateOrderApiResponse {
        return when (apiRequest) {
            is CreateOrderApiRequest.BackToBackMarket -> addBackToBackMarketOrder(
                wallet,
                apiRequest,
            )

            else -> orderBatch(
                wallet,
                BatchOrdersApiRequest(
                    marketId = apiRequest.marketId,
                    createOrders = listOf(apiRequest),
                    cancelOrders = emptyList(),
                ),
            ).createdOrders.first()
        }
    }

    private fun addBackToBackMarketOrder(
        wallet: WalletEntity,
        orderRequest: CreateOrderApiRequest.BackToBackMarket,
    ): CreateOrderApiResponse {
        val orderId = OrderId.generate()

        val userId = wallet.userGuid.value
        val walletAddress = wallet.address
        val market1 = getMarket(orderRequest.marketId)
        val market2 = getMarket(orderRequest.secondMarketId)
        val firstLegDirection = if (listOf(market2.baseSymbol.value, market2.quoteSymbol.value).contains(market1.baseSymbol.value)) OrderSide.Buy else OrderSide.Sell
        val secondLegDirection = if (listOf(market1.baseSymbol.value, market1.quoteSymbol.value).contains(market2.quoteSymbol.value)) OrderSide.Buy else OrderSide.Sell
        val baseSymbol = getSymbolEntity(if (firstLegDirection == OrderSide.Buy) market1.quoteSymbol else market1.baseSymbol)
        val quoteSymbol = getSymbolEntity(if (secondLegDirection == OrderSide.Buy) market2.baseSymbol else market2.quoteSymbol)

        when (walletAddress) {
            is EvmAddress ->
                verifyEIP712Signature(
                    walletAddress,
                    EIP712Transaction.Order(
                        walletAddress,
                        baseChainId = baseSymbol.chainId.value,
                        baseToken = baseSymbol.contractAddress ?: EvmAddress.zero,
                        quoteChainId = quoteSymbol.chainId.value,
                        quoteToken = quoteSymbol.contractAddress ?: EvmAddress.zero,
                        amount = orderRequest.amount.negate(),
                        price = BigInteger.ZERO,
                        nonce = BigInteger(1, orderRequest.nonce.toHexBytes()),
                        signature = orderRequest.signature as EvmSignature,
                    ),
                    verifyingChainId = orderRequest.verifyingChainId,
                )
            is BitcoinAddress -> {
                verifyBitcoinSignature(
                    walletAddress,
                    orderRequest,
                    baseSymbol,
                    quoteSymbol,
                )
            }
        }

        val response = runBlocking {
            sequencerClient.backToBackOrder(
                listOf(market1.id, market2.id),
                userId.toSequencerId(),
                walletAddress.toSequencerId(),
                SequencerClient.Order(
                    sequencerOrderId = orderId.toSequencerId().value,
                    amount = orderRequest.amount.fixedAmount(),
                    levelIx = null,
                    orderType = toSequencerOrderType(true, orderRequest.side),
                    nonce = BigInteger(1, orderRequest.nonce.toHexBytes()),
                    signature = orderRequest.signature,
                    orderId = orderId,
                    chainId = orderRequest.verifyingChainId,
                    clientOrderId = orderRequest.clientOrderId,
                    percentage = orderRequest.amount.percentage(),
                ),
            )
        }

        when (response.error) {
            SequencerError.None -> {}
            SequencerError.ExceedsLimit -> throw RequestProcessingError("Order exceeds limit")
            else -> throw RequestProcessingError("Unable to process request - ${response.error}")
        }

        return CreateOrderApiResponse(orderId, clientOrderId = null, RequestStatus.Accepted, error = null, orderRequest)
    }

    fun orderBatch(
        wallet: WalletEntity,
        batchOrdersRequest: BatchOrdersApiRequest,
    ): BatchOrdersApiResponse {
        if (batchOrdersRequest.cancelOrders.isEmpty() && batchOrdersRequest.createOrders.isEmpty()) {
            return BatchOrdersApiResponse(emptyList(), emptyList())
        }

        val createOrderRequestsByOrderId = batchOrdersRequest.createOrders.associateBy { OrderId.generate() }

        val userId = wallet.userGuid.value
        val walletAddress = wallet.address
        val market = getMarket(batchOrdersRequest.marketId)
        val baseSymbol = getSymbolEntity(market.baseSymbol)
        val quoteSymbol = getSymbolEntity(market.quoteSymbol)
        val symbolNetworks = listOf(baseSymbol, quoteSymbol).map { it.chainId.value.networkType() }.toSet()
        symbolNetworks.subtract(setOf(wallet.networkType)).forEach { networkType ->
            transaction {
                wallet.authorizedWallet(networkType)
                    ?: throw RequestProcessingError("No authorized wallet found for network type for this symbol")
            }
        }

        val ordersToAdd = createOrderRequestsByOrderId.map { (orderId, orderRequest) ->
            val levelIx = when (orderRequest) {
                is CreateOrderApiRequest.Limit -> {
                    ensurePriceIsMultipleOfTickSize(market, orderRequest.price)
                    orderRequest.price.divideToIntegralValue(market.tickSize).toBigInteger().safeToInt()
                        ?: throw RequestProcessingError("Order price is too large")
                }
                else -> null
            }

            val percentage = when (orderRequest) {
                is CreateOrderApiRequest.Market -> orderRequest.amount.percentage()
                else -> null
            }

            ensureOrderMarketIdMatchesBatchMarketId(orderRequest.marketId, batchOrdersRequest)

            when (walletAddress) {
                is EvmAddress ->
                    verifyEIP712Signature(
                        walletAddress,
                        EIP712Transaction.Order(
                            walletAddress,
                            baseChainId = baseSymbol.chainId.value,
                            baseToken = baseSymbol.contractAddress ?: EvmAddress.zero,
                            quoteChainId = quoteSymbol.chainId.value,
                            quoteToken = quoteSymbol.contractAddress ?: EvmAddress.zero,
                            amount = if (orderRequest.side == OrderSide.Buy) orderRequest.amount else orderRequest.amount.negate(),
                            price = when (orderRequest) {
                                is CreateOrderApiRequest.Limit -> orderRequest.price.toFundamentalUnits(quoteSymbol.decimals)
                                else -> BigInteger.ZERO
                            },
                            nonce = BigInteger(1, orderRequest.nonce.toHexBytes()),
                            signature = orderRequest.signature as EvmSignature,
                        ),
                        verifyingChainId = orderRequest.verifyingChainId,
                    )
                is BitcoinAddress -> {
                    verifyBitcoinSignature(walletAddress, orderRequest, baseSymbol, quoteSymbol)
                }
            }

            SequencerClient.Order(
                sequencerOrderId = orderId.toSequencerId().value,
                amount = orderRequest.amount.fixedAmount(),
                levelIx = levelIx,
                orderType = toSequencerOrderType(orderRequest is CreateOrderApiRequest.Market, orderRequest.side),
                nonce = BigInteger(1, orderRequest.nonce.toHexBytes()),
                signature = orderRequest.signature,
                orderId = orderId,
                chainId = orderRequest.verifyingChainId,
                clientOrderId = orderRequest.clientOrderId,
                percentage = percentage,
            )
        }

        val ordersToCancel = batchOrdersRequest.cancelOrders.map { orderRequest ->
            ensureOrderMarketIdMatchesBatchMarketId(orderRequest.marketId, batchOrdersRequest)

            when (walletAddress) {
                is EvmAddress ->
                    verifyEIP712Signature(
                        walletAddress,
                        EIP712Transaction.CancelOrder(
                            walletAddress,
                            batchOrdersRequest.marketId,
                            if (orderRequest.side == OrderSide.Buy) orderRequest.amount else orderRequest.amount.negate(),
                            BigInteger(1, orderRequest.nonce.toHexBytes()),
                            orderRequest.signature as EvmSignature,
                        ),
                        verifyingChainId = orderRequest.verifyingChainId,
                    )
                is BitcoinAddress -> {
                    val amount = orderRequest.amount.fromFundamentalUnits(baseSymbol.decimals).toPlainString()
                    val message = "[funkybit] Please sign this message to authorize order cancellation. This action will not cost any gas fees." +
                        if (orderRequest.side == OrderSide.Buy) {
                            "\nSwap $amount ${quoteSymbol.displayName()} for ${baseSymbol.displayName()}"
                        } else {
                            "\nSwap $amount ${baseSymbol.displayName()} for ${quoteSymbol.displayName()}"
                        } + "\nAddress: ${walletAddress.value}, Nonce: ${orderRequest.nonce}"
                    if (!BitcoinSignatureVerification.verifyMessage(walletAddress, orderRequest.signature.value.replace(" ", "+"), message)) {
                        throw RequestProcessingError(ReasonCode.SignatureNotValid, "Invalid signature")
                    }
                }
            }

            orderRequest.orderId
        }

        val response = runBlocking {
            sequencerClient.orderBatch(market.id, userId.toSequencerId(), walletAddress.toSequencerId(), ordersToAdd, ordersToCancel)
        }

        when (response.error) {
            SequencerError.None -> {}
            SequencerError.ExceedsLimit -> throw RequestProcessingError("Order exceeds limit")
            else -> throw RequestProcessingError("Unable to process request - ${response.error}")
        }

        val failedUpdatesOrCancels = response.ordersChangeRejectedList.associateBy { it.guid }

        return BatchOrdersApiResponse(
            createOrderRequestsByOrderId.map { (orderId, request) ->
                CreateOrderApiResponse(
                    orderId = orderId,
                    clientOrderId = request.clientOrderId,
                    requestStatus = RequestStatus.Accepted,
                    error = null,
                    order = request,
                )
            },
            batchOrdersRequest.cancelOrders.map {
                val rejected = failedUpdatesOrCancels[it.orderId.toSequencerId().value]
                CancelOrderApiResponse(
                    orderId = it.orderId,
                    requestStatus = if (rejected == null) RequestStatus.Accepted else RequestStatus.Rejected,
                    error = rejected?.reason?.let { reason -> ApiError(ReasonCode.RejectedBySequencer, reasonToMessage(reason)) },
                )
            },
        )
    }

    private fun verifyBitcoinSignature(bitcoinAddress: BitcoinAddress, orderRequest: CreateOrderApiRequest, baseSymbol: SymbolEntity, quoteSymbol: SymbolEntity) {
        val amount = when (orderRequest.amount) {
            is OrderAmount.Fixed -> orderRequest.amount.fixedAmount().fromFundamentalUnits(baseSymbol.decimals).toPlainString()
            is OrderAmount.Percent -> "${orderRequest.amount.percentage()}% of your "
        }
        val bitcoinOrderMessage = "[funkybit] Please sign this message to authorize a swap. This action will not cost any gas fees." +
            if (orderRequest.side == OrderSide.Buy) {
                "\nSwap $amount ${quoteSymbol.displayName()} for ${baseSymbol.displayName()}"
            } else {
                "\nSwap $amount ${baseSymbol.displayName()} for ${quoteSymbol.displayName()}"
            } + when (orderRequest) {
                is CreateOrderApiRequest.Limit -> "\nPrice: ${orderRequest.price.toPlainString()}"
                else -> "\nPrice: Market"
            } + "\nAddress: ${bitcoinAddress.value}, Nonce: ${orderRequest.nonce}"
        if (!BitcoinSignatureVerification.verifyMessage(bitcoinAddress, orderRequest.signature.value.replace(" ", "+"), bitcoinOrderMessage)) {
            throw RequestProcessingError(ReasonCode.SignatureNotValid, "Invalid signature")
        }
    }

    private fun reasonToMessage(reason: Reason): String {
        return when (reason) {
            Reason.DoesNotExist -> "Order does not exist or is already finalized"
            Reason.NotForAccount -> "Order not created by this account"
            else -> ""
        }
    }

    fun withdraw(wallet: WalletEntity, apiRequest: CreateWithdrawalApiRequest): WithdrawalApiResponse {
        val userId = wallet.userGuid.value
        val symbol = getSymbolEntity(apiRequest.symbol)
        val walletAddress = getWalletAddressForChain(wallet, symbol.chainId.value)

        when (walletAddress) {
            is EvmAddress -> verifyEIP712Signature(
                walletAddress,
                EIP712Transaction.WithdrawTx(
                    walletAddress,
                    TokenAddressAndChain(symbol.contractAddress ?: EvmAddress.zero, symbol.chainId.value),
                    apiRequest.amount,
                    apiRequest.nonce,
                    apiRequest.amount == BigInteger.ZERO,
                    apiRequest.signature as EvmSignature,
                ),
                symbol.chainId.value,
            )
            is BitcoinAddress -> {
                val bitcoinAddress = BitcoinAddress.canonicalize(walletAddress.value)
                val bitcoinWithdrawalMessage = String.format(
                    "[funkybit] Please sign this message to authorize withdrawal of %s %s from the exchange to your wallet.",
                    apiRequest.amount.fromSatoshi().toPlainString(),
                    symbol.displayName(),
                ) + "\nAddress: ${bitcoinAddress.value}, Timestamp: ${Instant.fromEpochMilliseconds(apiRequest.nonce)}"
                if (!BitcoinSignatureVerification.verifyMessage(bitcoinAddress, apiRequest.signature.value.replace(" ", "+"), bitcoinWithdrawalMessage)) {
                    throw RequestProcessingError(ReasonCode.SignatureNotValid, "Invalid signature")
                }
            }
        }

        val withdrawal = transaction {
            WithdrawalEntity.createPending(
                WalletEntity.getByAddress(walletAddress),
                symbol,
                apiRequest.amount,
                apiRequest.nonce,
                apiRequest.signature,
            ).let {
                it.refresh(flush = true)
                Withdrawal.fromEntity(it)
            }
        }

        runBlocking {
            sequencerClient.withdraw(
                userId.toSequencerId(),
                Asset(symbol.name),
                apiRequest.amount,
                apiRequest.nonce.toBigInteger(),
                apiRequest.signature,
                withdrawal.id,
            )
        }

        return WithdrawalApiResponse(withdrawal)
    }

    fun deposit(wallet: WalletEntity, apiRequest: CreateDepositApiRequest): DepositApiResponse {
        return transaction {
            val symbol = getSymbolEntity(apiRequest.symbol)
            val walletAddress = getWalletAddressForChain(wallet, symbol.chainId.value)

            val deposit = DepositEntity.createOrUpdate(
                wallet = WalletEntity.getByAddress(walletAddress),
                symbol = symbol,
                amount = apiRequest.amount,
                blockNumber = null,
                transactionHash = apiRequest.txHash,
            ) ?: throw DepositException("Unable to create deposit")

            if (TestnetChallengeUtils.enabled) {
                if (deposit.wallet.user.testnetChallengeStatus == TestnetChallengeStatus.PendingDeposit &&
                    deposit.symbol.name == TestnetChallengeUtils.depositSymbolName &&
                    deposit.amount == TestnetChallengeUtils.depositAmount.toFundamentalUnits(TestnetChallengeUtils.depositSymbol().decimals)
                ) {
                    deposit.wallet.user.testnetChallengeStatus = TestnetChallengeStatus.PendingDepositConfirmation
                }
            }

            DepositApiResponse(Deposit.fromEntity(deposit))
        }
    }

    fun cancelOrder(wallet: WalletEntity, cancelOrderApiRequest: CancelOrderApiRequest): CancelOrderApiResponse {
        return orderBatch(
            wallet,
            BatchOrdersApiRequest(
                marketId = cancelOrderApiRequest.marketId,
                createOrders = emptyList(),
                cancelOrders = listOf(cancelOrderApiRequest),
            ),
        ).canceledOrders.first()
    }

    fun cancelOpenOrders(userId: EntityID<UserId>) {
        val openOrders = transaction {
            OrderEntity
                .listOpenForUser(userId)
                .with(OrderEntity::wallet)
                .map { Pair(it, it.wallet) }
        }
        if (openOrders.isNotEmpty()) {
            runBlocking {
                openOrders
                    .groupBy(
                        keySelector = { (order, wallet) -> Pair(order.marketGuid, wallet.address) },
                        valueTransform = { (order, _) -> order },
                    )
                    .forEach { entry ->
                        val orderIds = entry.value.map { it.guid.value }
                        val (marketGuid, walletAddress) = entry.key

                        sequencerClient.cancelOrders(
                            marketGuid.value,
                            userId.value.toSequencerId(),
                            walletAddress.toSequencerId(),
                            orderIds,
                            cancelAll = true,
                        )
                    }
            }
        }
    }

    private fun getSymbolEntity(asset: Symbol): SymbolEntity {
        return symbolMap.getOrPut(asset) {
            transaction { SymbolEntity.forName(asset.value) }
        }
    }

    private fun getMarket(marketId: MarketId): Market {
        return marketMap.getOrPut(marketId) {
            transaction {
                MarketEntity[marketId].let {
                    Market(
                        it.id.value,
                        baseSymbol = Symbol(it.baseSymbol.name),
                        baseDecimals = it.baseSymbol.decimals.toInt(),
                        quoteSymbol = Symbol(it.quoteSymbol.name),
                        quoteDecimals = it.quoteSymbol.decimals.toInt(),
                        tickSize = it.tickSize,
                        lastPrice = it.lastPrice,
                        minFee = it.minFee,
                    )
                }
            }
        }
    }

    private fun toSequencerOrderType(isMarketOrder: Boolean, side: OrderSide): xyz.funkybit.sequencer.proto.Order.Type {
        return when (isMarketOrder) {
            true ->
                when (side) {
                    OrderSide.Buy -> xyz.funkybit.sequencer.proto.Order.Type.MarketBuy
                    OrderSide.Sell -> xyz.funkybit.sequencer.proto.Order.Type.MarketSell
                }

            false ->
                when (side) {
                    OrderSide.Buy -> xyz.funkybit.sequencer.proto.Order.Type.LimitBuy
                    OrderSide.Sell -> xyz.funkybit.sequencer.proto.Order.Type.LimitSell
                }
        }
    }

    private fun ensurePriceIsMultipleOfTickSize(market: Market, price: BigDecimal) {
        if (BigDecimal.ZERO.compareTo(price.remainder(market.tickSize)) != 0) {
            throw RequestProcessingError("Order price is not a multiple of tick size")
        }
    }

    private fun ensureOrderMarketIdMatchesBatchMarketId(orderMarketId: MarketId, apiRequest: BatchOrdersApiRequest) {
        if (orderMarketId != apiRequest.marketId) {
            throw RequestProcessingError("Orders in a batch request have to be in the same market")
        }
    }

    private val exchangeContractsByChain = mutableMapOf<ChainId, Address>()

    private fun verifyEIP712Signature(walletAddress: EvmAddress, tx: EIP712Transaction, verifyingChainId: ChainId) {
        val verifyingContract = exchangeContractsByChain[verifyingChainId] ?: transaction {
            DeployedSmartContractEntity.latestExchangeContractAddress(verifyingChainId)?.also {
                exchangeContractsByChain[verifyingChainId] = it
            } ?: throw RequestProcessingError("Exchange contract not found for $verifyingChainId")
        }

        runCatching {
            val linkedSigner = LinkedSignerService.getLinkedSigner(walletAddress, verifyingChainId) as? EvmAddress
            ECHelper.isValidSignature(
                EIP712Helper.computeHash(tx, verifyingChainId, verifyingContract),
                tx.signature,
                walletAddress,
                linkedSigner,
            )
        }.onFailure {
            logger.warn(it) { "Exception verifying EIP712 signature" }
            throw RequestProcessingError(ReasonCode.SignatureNotValid, "Invalid signature")
        }.getOrDefault(false).also { isValidSignature ->
            if (!isValidSignature) {
                throw RequestProcessingError(ReasonCode.SignatureNotValid, "Invalid signature")
            }
        }
    }

    private fun getWalletAddressForChain(wallet: WalletEntity, chainId: ChainId): Address {
        return transaction {
            if (wallet.networkType == chainId.networkType()) {
                wallet.address
            } else {
                wallet.authorizedWallet(chainId.networkType())?.address
            } ?: throw RequestProcessingError("No authorized wallet found for network type for this symbol")
        }
    }
}
