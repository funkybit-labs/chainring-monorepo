package xyz.funkybit.integrationtests.utils

import arrow.core.Either
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.jupiter.api.Assertions
import xyz.funkybit.apps.api.TestRoutes
import xyz.funkybit.apps.api.model.AccountConfigurationApiResponse
import xyz.funkybit.apps.api.model.ApiError
import xyz.funkybit.apps.api.model.AuthorizeWalletApiRequest
import xyz.funkybit.apps.api.model.BalancesApiResponse
import xyz.funkybit.apps.api.model.BatchOrdersApiRequest
import xyz.funkybit.apps.api.model.BatchOrdersApiResponse
import xyz.funkybit.apps.api.model.CancelOrderApiRequest
import xyz.funkybit.apps.api.model.ConfigurationApiResponse
import xyz.funkybit.apps.api.model.CreateDepositApiRequest
import xyz.funkybit.apps.api.model.CreateOrderApiRequest
import xyz.funkybit.apps.api.model.CreateOrderApiResponse
import xyz.funkybit.apps.api.model.CreateWithdrawalApiRequest
import xyz.funkybit.apps.api.model.DepositApiResponse
import xyz.funkybit.apps.api.model.FaucetApiRequest
import xyz.funkybit.apps.api.model.FaucetApiResponse
import xyz.funkybit.apps.api.model.GetLastPriceResponse
import xyz.funkybit.apps.api.model.GetLimitsApiResponse
import xyz.funkybit.apps.api.model.GetOrderBookApiResponse
import xyz.funkybit.apps.api.model.ListDepositsApiResponse
import xyz.funkybit.apps.api.model.ListWithdrawalsApiResponse
import xyz.funkybit.apps.api.model.Market
import xyz.funkybit.apps.api.model.Order
import xyz.funkybit.apps.api.model.OrderAmount
import xyz.funkybit.apps.api.model.OrdersApiResponse
import xyz.funkybit.apps.api.model.WithdrawalApiResponse
import xyz.funkybit.core.model.EvmSignature
import xyz.funkybit.core.model.MarketMinFee
import xyz.funkybit.core.model.Percentage
import xyz.funkybit.core.model.WithdrawalFee
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.ClientOrderId
import xyz.funkybit.core.model.db.DepositId
import xyz.funkybit.core.model.db.FeeRates
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.OrderId
import xyz.funkybit.core.model.db.OrderSide
import xyz.funkybit.core.model.db.OrderStatus
import xyz.funkybit.core.model.db.WithdrawalId
import xyz.funkybit.core.utils.TraceRecorder
import xyz.funkybit.core.utils.generateOrderNonce
import xyz.funkybit.core.utils.toFundamentalUnits
import java.math.BigDecimal
import java.net.HttpURLConnection
import kotlin.test.DefaultAsserter.fail
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TestApiClient(keyPair: WalletKeyPair = WalletKeyPair.EVM.generate(), traceRecorder: TraceRecorder = TraceRecorder.noOp, chainId: ChainId = ChainId(1337u)) : ApiClient(keyPair, traceRecorder, chainId) {

    companion object {

        fun withEvmWallet(keyPair: WalletKeyPair.EVM = WalletKeyPair.EVM.generate(), chainId: ChainId = ChainId(1337u)): TestApiClient {
            return TestApiClient(keyPair = keyPair, chainId = chainId)
        }

        fun withBitcoinWallet(keyPair: WalletKeyPair.Bitcoin = WalletKeyPair.Bitcoin.generate(), chainId: ChainId = ChainId(0u)): TestApiClient {
            return TestApiClient(keyPair = keyPair, chainId = chainId)
        }

        fun getOpenApiDocumentation(): String {
            val httpResponse = execute(
                Request.Builder()
                    .url("$apiServerRootUrl/v1/openapi.json")
                    .get()
                    .build(),
            )
            return if (httpResponse.code == HttpURLConnection.HTTP_OK) {
                httpResponse.body?.string()!!
            } else {
                throw AbnormalApiResponseException(httpResponse)
            }
        }

        fun resetSequencer() {
            execute(
                Request.Builder()
                    .url("$apiServerRootUrl/v1/sequencer")
                    .delete()
                    .build(),
            ).also { httpResponse ->
                if (httpResponse.code != HttpURLConnection.HTTP_NO_CONTENT) {
                    throw AbnormalApiResponseException(httpResponse)
                }
            }
        }

        fun getSequencerStateDump(): TestRoutes.Companion.StateDump {
            val httpResponse = execute(
                Request.Builder()
                    .url("$apiServerRootUrl/v1/sequencer-state")
                    .get()
                    .build(),
            )

            return when (httpResponse.code) {
                HttpURLConnection.HTTP_OK -> json.decodeFromString<TestRoutes.Companion.StateDump>(httpResponse.body?.string()!!)
                else -> throw AbnormalApiResponseException(httpResponse)
            }
        }

        fun createMarketInSequencer(apiRequest: TestRoutes.Companion.CreateMarketInSequencer) {
            execute(
                Request.Builder()
                    .url("$apiServerRootUrl/v1/sequencer-markets")
                    .post(Json.encodeToString(apiRequest).toRequestBody(applicationJson))
                    .build(),
            ).also { httpResponse ->
                if (httpResponse.code != HttpURLConnection.HTTP_CREATED) {
                    throw AbnormalApiResponseException(httpResponse)
                }
            }
        }

        fun setFeeRatesInSequencer(feeRates: FeeRates) =
            setFeeRatesInSequencer(TestRoutes.Companion.SetFeeRatesInSequencer(maker = feeRates.maker, taker = feeRates.taker))

        fun setFeeRatesInSequencer(apiRequest: TestRoutes.Companion.SetFeeRatesInSequencer) {
            execute(
                Request.Builder()
                    .url("$apiServerRootUrl/v1/sequencer-fee-rates")
                    .put(Json.encodeToString(apiRequest).toRequestBody(applicationJson))
                    .build(),
            ).also { httpResponse ->
                if (httpResponse.code != HttpURLConnection.HTTP_OK) {
                    throw AbnormalApiResponseException(httpResponse)
                }
            }
        }

        fun setWithdrawalFeesInSequencer(withdrawalFees: List<WithdrawalFee>) =
            setWithdrawalFeesInSequencer(TestRoutes.Companion.SetWithdrawalFeesInSequencer(withdrawalFees = withdrawalFees))

        fun setWithdrawalFeesInSequencer(apiRequest: TestRoutes.Companion.SetWithdrawalFeesInSequencer) {
            execute(
                Request.Builder()
                    .url("$apiServerRootUrl/v1/sequencer-withdrawal-fees")
                    .put(Json.encodeToString(apiRequest).toRequestBody(applicationJson))
                    .build(),
            ).also { httpResponse ->
                if (httpResponse.code != HttpURLConnection.HTTP_OK) {
                    throw AbnormalApiResponseException(httpResponse)
                }
            }
        }

        fun setMarketMinFeesInSequencer(marketMinFees: List<MarketMinFee>) =
            setMarketMinFeesInSequencer(TestRoutes.Companion.SetMarketMinFeesInSequencer(marketMinFees = marketMinFees))

        fun setMarketMinFeesInSequencer(apiRequest: TestRoutes.Companion.SetMarketMinFeesInSequencer) {
            execute(
                Request.Builder()
                    .url("$apiServerRootUrl/v1/sequencer-market-min-fees")
                    .put(Json.encodeToString(apiRequest).toRequestBody(applicationJson))
                    .build(),
            ).also { httpResponse ->
                if (httpResponse.code != HttpURLConnection.HTTP_OK) {
                    throw AbnormalApiResponseException(httpResponse)
                }
            }
        }

        fun getConfiguration(): ConfigurationApiResponse {
            val httpResponse = execute(
                Request.Builder()
                    .url("$apiServerRootUrl/v1/config")
                    .get()
                    .build(),
            )

            return when (httpResponse.code) {
                HttpURLConnection.HTTP_OK -> json.decodeFromString<ConfigurationApiResponse>(httpResponse.body?.string()!!)
                else -> throw AbnormalApiResponseException(httpResponse)
            }
        }

        private fun execute(request: Request): Response =
            httpClient.newCall(request).execute()
    }

    override fun getConfiguration(): ConfigurationApiResponse =
        tryGetConfiguration().assertSuccess()

    override fun getAccountConfiguration(): AccountConfigurationApiResponse =
        tryGetAccountConfiguration().assertSuccess()

    override fun markSymbolAsAdded(symbolName: String) =
        tryMarkSymbolAsAdded(symbolName).assertSuccess()

    override fun authorizeWallet(apiRequest: AuthorizeWalletApiRequest) =
        tryAuthorizeWallet(apiRequest).assertSuccess()

    fun authorizeBitcoinWallet(evmKeyPair: WalletKeyPair.EVM) {
        val bitcoinKeyPair = keyPair as WalletKeyPair.Bitcoin

        authorizeWallet(
            signAuthorizeBitcoinWalletRequest(
                ecKeyPair = evmKeyPair.ecKeyPair,
                address = evmKeyPair.address(),
                authorizedAddress = bitcoinKeyPair.address(),
            ),
        )
    }

    override fun createOrder(apiRequest: CreateOrderApiRequest): CreateOrderApiResponse =
        tryCreateOrder(apiRequest).assertSuccess()

    fun createLimitOrder(market: Market, side: OrderSide, amount: BigDecimal, price: BigDecimal, wallet: Wallet, clientOrderId: ClientOrderId? = null, linkedSignerKeyPair: WalletKeyPair? = null): CreateOrderApiResponse {
        val request = CreateOrderApiRequest.Limit(
            nonce = generateOrderNonce(),
            marketId = market.id,
            side = side,
            amount = OrderAmount.Fixed(amount.toFundamentalUnits(market.baseDecimals)),
            price = price,
            signature = EvmSignature.emptySignature(),
            verifyingChainId = ChainId.empty,
            clientOrderId = clientOrderId,
        ).let { wallet.signOrder(it, linkedSignerKeyPair = linkedSignerKeyPair) }

        val response = createOrder(request)

        assertIs<CreateOrderApiRequest.Limit>(response.order)
        assertEquals(request, response.order)

        return response
    }

    fun tryCreateLimitOrder(market: Market, side: OrderSide, amount: BigDecimal, price: BigDecimal, wallet: Wallet, clientOrderId: ClientOrderId? = null, linkedSignerKeyPair: WalletKeyPair? = null): Either<ApiCallFailure, CreateOrderApiResponse> {
        val request = CreateOrderApiRequest.Limit(
            nonce = generateOrderNonce(),
            marketId = market.id,
            side = side,
            amount = OrderAmount.Fixed(amount.toFundamentalUnits(market.baseDecimals)),
            price = price,
            signature = EvmSignature.emptySignature(),
            verifyingChainId = ChainId.empty,
            clientOrderId = clientOrderId,
        ).let { wallet.signOrder(it, linkedSignerKeyPair = linkedSignerKeyPair) }

        return tryCreateOrder(request)
    }

    fun createMarketOrder(market: Market, side: OrderSide, amount: BigDecimal?, wallet: Wallet, clientOrderId: ClientOrderId? = null, percentage: Percentage? = null): CreateOrderApiResponse {
        val request = CreateOrderApiRequest.Market(
            nonce = generateOrderNonce(),
            marketId = market.id,
            side = side,
            amount = amount?.let { OrderAmount.Fixed(it.toFundamentalUnits(market.baseDecimals)) } ?: OrderAmount.Percent(percentage!!),
            signature = EvmSignature.emptySignature(),
            verifyingChainId = ChainId.empty,
            clientOrderId = clientOrderId,
        ).let { wallet.signOrder(it) }

        val response = createOrder(request)

        assertIs<CreateOrderApiRequest.Market>(response.order)
        assertEquals(request, response.order)

        return response
    }

    fun createBackToBackMarketOrder(markets: List<Market>, side: OrderSide, amount: BigDecimal?, wallet: Wallet, clientOrderId: ClientOrderId? = null, percentage: Percentage? = null): CreateOrderApiResponse {
        val request = CreateOrderApiRequest.BackToBackMarket(
            nonce = generateOrderNonce(),
            marketId = markets[0].id,
            secondMarketId = markets[1].id,
            side = side,
            amount = amount?.let { OrderAmount.Fixed(it.toFundamentalUnits(markets[0].baseDecimals)) } ?: OrderAmount.Percent(percentage!!),
            signature = EvmSignature.emptySignature(),
            verifyingChainId = ChainId.empty,
            clientOrderId = clientOrderId,
        ).let { wallet.signOrder(it) }

        val response = createOrder(request)

        assertIs<CreateOrderApiRequest.BackToBackMarket>(response.order)
        assertEquals(request, response.order)

        return response
    }

    override fun batchOrders(apiRequest: BatchOrdersApiRequest): BatchOrdersApiResponse =
        tryBatchOrders(apiRequest).assertSuccess()

    override fun cancelOrder(apiRequest: CancelOrderApiRequest) =
        tryCancelOrder(apiRequest).assertSuccess()

    fun cancelOrder(createOrderApiResponse: CreateOrderApiResponse, wallet: Wallet) =
        cancelOrder(
            createOrderApiResponse.toCancelOrderRequest(wallet),
        )

    fun tryCancelOrder(createOrderApiResponse: CreateOrderApiResponse, wallet: Wallet) =
        tryCancelOrder(
            createOrderApiResponse.toCancelOrderRequest(wallet),
        )

    override fun getOrder(id: OrderId): Order =
        tryGetOrder(id).assertSuccess()

    override fun getOrder(id: ClientOrderId): Order =
        tryGetOrder(id).assertSuccess()

    override fun listOrders(statuses: List<OrderStatus>, marketId: MarketId?): OrdersApiResponse =
        tryListOrders(statuses, marketId).assertSuccess()

    override fun cancelOpenOrders() =
        tryCancelOpenOrders().assertSuccess()

    override fun getOrderBook(marketId: MarketId): GetOrderBookApiResponse =
        tryGetOrderBook(marketId).assertSuccess()

    override fun getLimits(): GetLimitsApiResponse =
        tryGetLimits().assertSuccess()

    override fun createDeposit(apiRequest: CreateDepositApiRequest): DepositApiResponse =
        tryCreateDeposit(apiRequest).assertSuccess()

    override fun getDeposit(id: DepositId): DepositApiResponse =
        tryGetDeposit(id).assertSuccess()

    override fun listDeposits(): ListDepositsApiResponse =
        tryListDeposits().assertSuccess()

    override fun createWithdrawal(apiRequest: CreateWithdrawalApiRequest): WithdrawalApiResponse =
        tryCreateWithdrawal(apiRequest).assertSuccess()

    override fun getWithdrawal(id: WithdrawalId): WithdrawalApiResponse =
        tryGetWithdrawal(id).assertSuccess()

    override fun listWithdrawals(): ListWithdrawalsApiResponse =
        tryListWithdrawals().assertSuccess()

    override fun getBalances(): BalancesApiResponse =
        tryGetBalances().assertSuccess()

    override fun faucet(apiRequest: FaucetApiRequest): FaucetApiResponse =
        tryFaucet(apiRequest).assertSuccess()

    override fun getLastPrice(marketId: MarketId): GetLastPriceResponse =
        tryGetLastPrice(marketId).assertSuccess()
}

fun <T> Either<ApiCallFailure, T>.assertSuccess(): T {
    if (this.isLeft()) {
        fail(
            "Unexpected API error: ${this.leftOrNull()}. ${
                runCatching { TestApiClient.getSequencerStateDump() }.map { " Sequencer state: ${prettyJsonFormatter.encodeToString(it) }" }
            }",
        )
    }
    return this.getOrNull()!!
}

fun Either<ApiCallFailure, Any>.assertError(expectedHttpCode: Int, expectedError: ApiError) {
    if (this.isRight()) {
        org.junit.jupiter.api.fail("Expected API error, but got a success response")
    }
    val failure = this.leftOrNull()!!
    Assertions.assertEquals(expectedHttpCode, failure.httpCode)
    Assertions.assertEquals(expectedError, failure.error)
}

fun Either<ApiCallFailure, Any>.assertError(expectedError: ApiError) {
    if (this.isRight()) {
        org.junit.jupiter.api.fail("Expected API error, but got a success response")
    }
    val failure = this.leftOrNull()!!
    Assertions.assertEquals(expectedError, failure.error)
}

val prettyJsonFormatter = Json {
    this.prettyPrint = true
}

fun CreateOrderApiResponse.toCancelOrderRequest(wallet: Wallet): CancelOrderApiRequest =
    CancelOrderApiRequest(
        orderId = this.orderId,
        marketId = this.order.marketId,
        amount = this.order.amount.fixedAmount(),
        side = this.order.side,
        nonce = generateOrderNonce(),
        signature = EvmSignature.emptySignature(),
        verifyingChainId = ChainId.empty,
    ).let {
        wallet.signCancelOrder(it)
    }
