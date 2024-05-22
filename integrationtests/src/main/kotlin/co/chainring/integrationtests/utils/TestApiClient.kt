package co.chainring.integrationtests.utils

import arrow.core.Either
import co.chainring.apps.api.TestRoutes
import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.BalancesApiResponse
import co.chainring.apps.api.model.BatchOrdersApiRequest
import co.chainring.apps.api.model.BatchOrdersApiResponse
import co.chainring.apps.api.model.CancelOrderApiRequest
import co.chainring.apps.api.model.ConfigurationApiResponse
import co.chainring.apps.api.model.CreateDepositApiRequest
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.CreateOrderApiResponse
import co.chainring.apps.api.model.CreateWithdrawalApiRequest
import co.chainring.apps.api.model.DepositApiResponse
import co.chainring.apps.api.model.ListDepositsApiResponse
import co.chainring.apps.api.model.ListWithdrawalsApiResponse
import co.chainring.apps.api.model.Market
import co.chainring.apps.api.model.Order
import co.chainring.apps.api.model.OrdersApiResponse
import co.chainring.apps.api.model.RequestStatus
import co.chainring.apps.api.model.UpdateOrderApiRequest
import co.chainring.apps.api.model.UpdateOrderApiResponse
import co.chainring.apps.api.model.WithdrawalApiResponse
import co.chainring.core.client.rest.AbnormalApiResponseException
import co.chainring.core.client.rest.ApiCallFailure
import co.chainring.core.client.rest.ApiClient
import co.chainring.core.client.rest.apiServerRootUrl
import co.chainring.core.client.rest.applicationJson
import co.chainring.core.client.rest.httpClient
import co.chainring.core.client.rest.json
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.DepositId
import co.chainring.core.model.db.FeeRates
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.WithdrawalId
import co.chainring.core.utils.TraceRecorder
import co.chainring.core.utils.generateOrderNonce
import co.chainring.core.utils.toFundamentalUnits
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.jupiter.api.Assertions
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import java.math.BigDecimal
import java.net.HttpURLConnection
import kotlin.test.DefaultAsserter.fail
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TestApiClient(ecKeyPair: ECKeyPair = Keys.createEcKeyPair(), traceRecorder: TraceRecorder = TraceRecorder.noOp) : ApiClient(ecKeyPair, traceRecorder) {

    companion object {

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

    override fun createOrder(apiRequest: CreateOrderApiRequest): CreateOrderApiResponse =
        tryCreateOrder(apiRequest).assertSuccess()

    fun createLimitOrder(market: Market, side: OrderSide, amount: BigDecimal, price: BigDecimal, wallet: Wallet): CreateOrderApiResponse {
        val request = CreateOrderApiRequest.Limit(
            nonce = generateOrderNonce(),
            marketId = market.id,
            side = side,
            amount = amount.toFundamentalUnits(market.baseDecimals),
            price = price,
            signature = EvmSignature.emptySignature(),
            verifyingChainId = ChainId.empty,
        ).let { wallet.signOrder(it) }

        val response = createOrder(request)

        assertIs<CreateOrderApiRequest.Limit>(response.order)
        assertEquals(request, response.order)

        return response
    }

    fun createMarketOrder(market: Market, side: OrderSide, amount: BigDecimal, wallet: Wallet): CreateOrderApiResponse {
        val request = CreateOrderApiRequest.Market(
            nonce = generateOrderNonce(),
            marketId = market.id,
            side = side,
            amount = amount.toFundamentalUnits(market.baseDecimals),
            signature = EvmSignature.emptySignature(),
            verifyingChainId = ChainId.empty,
        ).let { wallet.signOrder(it) }

        val response = createOrder(request)

        assertIs<CreateOrderApiRequest.Market>(response.order)
        assertEquals(request, response.order)

        return response
    }

    override fun batchOrders(apiRequest: BatchOrdersApiRequest): BatchOrdersApiResponse =
        tryBatchOrders(apiRequest).assertSuccess()

    override fun updateOrder(apiRequest: UpdateOrderApiRequest): UpdateOrderApiResponse {
        return tryUpdateOrder(apiRequest).assertSuccess()
    }

    fun updateOrder(market: Market, createOrderApiResponse: CreateOrderApiResponse, amount: BigDecimal, price: BigDecimal, wallet: Wallet): UpdateOrderApiResponse {
        val request = UpdateOrderApiRequest(
            orderId = createOrderApiResponse.orderId,
            marketId = createOrderApiResponse.order.marketId,
            side = createOrderApiResponse.order.side,
            amount = amount.toFundamentalUnits(market.baseDecimals),
            price = price,
            nonce = generateOrderNonce(),
            signature = EvmSignature.emptySignature(),
            verifyingChainId = ChainId.empty,
        ).let {
            wallet.signOrder(it)
        }

        val response = updateOrder(request)

        assertEquals(response.requestStatus, RequestStatus.Accepted)
        assertIs<UpdateOrderApiRequest>(response.order)
        assertEquals(request.amount, response.order.amount)
        assertEquals(request.price, response.order.price)
        return response
    }

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

    override fun listOrders(): OrdersApiResponse =
        tryListOrders().assertSuccess()

    override fun cancelOpenOrders() =
        tryCancelOpenOrders().assertSuccess()

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
        amount = this.order.amount,
        side = this.order.side,
        nonce = generateOrderNonce(),
        signature = EvmSignature.emptySignature(),
        verifyingChainId = ChainId.empty,
    ).let {
        wallet.signCancelOrder(it)
    }
