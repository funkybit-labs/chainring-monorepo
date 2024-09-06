package xyz.funkybit.apps.api.services

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
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
import xyz.funkybit.core.model.db.UserId
import xyz.funkybit.core.model.db.WalletEntity
import xyz.funkybit.core.model.db.WalletFamily
import xyz.funkybit.core.model.db.WithdrawalEntity
import xyz.funkybit.core.sequencer.SequencerClient
import xyz.funkybit.core.sequencer.toSequencerId
import xyz.funkybit.core.services.LinkedSignerService
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
        userId: UserId,
        walletAddress: Address,
        walletFamily: WalletFamily,
        apiRequest: CreateOrderApiRequest,
    ): CreateOrderApiResponse {
        return when (apiRequest) {
            is CreateOrderApiRequest.BackToBackMarket -> addBackToBackMarketOrder(
                userId,
                walletAddress,
                walletFamily,
                apiRequest,
            )

            else -> orderBatch(
                userId,
                walletAddress,
                walletFamily,
                BatchOrdersApiRequest(
                    marketId = apiRequest.marketId,
                    createOrders = listOf(apiRequest),
                    cancelOrders = emptyList(),
                ),
            ).createdOrders.first()
        }
    }

    private fun addBackToBackMarketOrder(
        userId: UserId,
        walletAddress: Address,
        walletFamily: WalletFamily,
        orderRequest: CreateOrderApiRequest.BackToBackMarket,
    ): CreateOrderApiResponse {
        val orderId = OrderId.generate()

        val market1 = getMarket(orderRequest.marketId)
        val baseSymbol = getSymbolEntity(market1.baseSymbol)
        val market2 = getMarket(orderRequest.secondMarketId)
        val quoteSymbol = getSymbolEntity(market2.quoteSymbol)

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
                        price = BigInteger.ZERO,
                        nonce = BigInteger(1, orderRequest.nonce.toHexBytes()),
                        signature = orderRequest.signature,
                    ),
                    verifyingChainId = orderRequest.verifyingChainId,
                )
            is BitcoinAddress -> {} // TODO verify signature
        }

        val response = runBlocking {
            sequencerClient.backToBackOrder(
                listOf(market1.id, market2.id),
                userId.toSequencerId(),
                walletFamily,
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
        userId: UserId,
        walletAddress: Address,
        walletFamily: WalletFamily,
        batchOrdersRequest: BatchOrdersApiRequest,
    ): BatchOrdersApiResponse {
        if (batchOrdersRequest.cancelOrders.isEmpty() && batchOrdersRequest.createOrders.isEmpty()) {
            return BatchOrdersApiResponse(emptyList(), emptyList())
        }

        val createOrderRequestsByOrderId = batchOrdersRequest.createOrders.associateBy { OrderId.generate() }

        val market = getMarket(batchOrdersRequest.marketId)
        val baseSymbol = getSymbolEntity(market.baseSymbol)
        val quoteSymbol = getSymbolEntity(market.quoteSymbol)

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
                            signature = orderRequest.signature,
                        ),
                        verifyingChainId = orderRequest.verifyingChainId,
                    )
                is BitcoinAddress -> {} // TODO verify signature
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
                            orderRequest.signature,
                        ),
                        verifyingChainId = orderRequest.verifyingChainId,
                    )
                is BitcoinAddress -> {} // TODO verify signature
            }

            orderRequest.orderId
        }

        val response = runBlocking {
            sequencerClient.orderBatch(market.id, userId.toSequencerId(), walletFamily, ordersToAdd, ordersToCancel)
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

    private fun reasonToMessage(reason: Reason): String {
        return when (reason) {
            Reason.DoesNotExist -> "Order does not exist or is already finalized"
            Reason.NotForUser -> "Order not created by this user"
            else -> ""
        }
    }

    fun withdraw(userId: UserId, walletAddress: Address, apiRequest: CreateWithdrawalApiRequest): WithdrawalApiResponse {
        val symbol = getSymbolEntity(apiRequest.symbol)

        when (walletAddress) {
            is EvmAddress -> verifyEIP712Signature(
                walletAddress,
                EIP712Transaction.WithdrawTx(
                    walletAddress,
                    TokenAddressAndChain(symbol.contractAddress ?: EvmAddress.zero, symbol.chainId.value),
                    apiRequest.amount,
                    apiRequest.nonce,
                    apiRequest.amount == BigInteger.ZERO,
                    apiRequest.signature,
                ),
                symbol.chainId.value,
            )
            is BitcoinAddress -> {} // TODO verify signature
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

    fun deposit(walletAddress: Address, apiRequest: CreateDepositApiRequest): DepositApiResponse =
        transaction {
            val deposit = DepositEntity.createOrUpdate(
                wallet = WalletEntity.getOrCreateWithUser(walletAddress),
                symbol = getSymbolEntity(apiRequest.symbol),
                amount = apiRequest.amount,
                blockNumber = null,
                transactionHash = apiRequest.txHash,
            ) ?: throw DepositException("Unable to create deposit")

            DepositApiResponse(Deposit.fromEntity(deposit))
        }

    fun cancelOrder(userId: UserId, walletAddress: Address, walletFamily: WalletFamily, cancelOrderApiRequest: CancelOrderApiRequest): CancelOrderApiResponse {
        return orderBatch(
            userId,
            walletAddress,
            walletFamily,
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
                        keySelector = { (order, wallet) -> Pair(order.marketGuid, wallet.family) },
                        valueTransform = { (order, _) -> order },
                    )
                    .forEach { entry ->
                        val orderIds = entry.value.map { it.guid.value }
                        val (marketGuid, walletFamily) = entry.key

                        sequencerClient.cancelOrders(
                            marketGuid.value,
                            userId.value.toSequencerId(),
                            walletFamily,
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
}
