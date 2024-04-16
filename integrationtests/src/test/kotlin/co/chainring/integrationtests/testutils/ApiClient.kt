package co.chainring.integrationtests.testutils

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import co.chainring.apps.api.TestRoutes
import co.chainring.apps.api.middleware.SignInMessage
import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.ApiErrors
import co.chainring.apps.api.model.BalancesApiResponse
import co.chainring.apps.api.model.BatchOrdersApiRequest
import co.chainring.apps.api.model.ConfigurationApiResponse
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.CreateWithdrawalApiRequest
import co.chainring.apps.api.model.Order
import co.chainring.apps.api.model.OrdersApiResponse
import co.chainring.apps.api.model.UpdateOrderApiRequest
import co.chainring.apps.api.model.WithdrawalApiResponse
import co.chainring.core.evm.ECHelper
import co.chainring.core.evm.EIP712Helper
import co.chainring.core.model.Address
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.WithdrawalId
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.web3j.crypto.Credentials
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import java.net.HttpURLConnection
import java.util.Base64
import kotlin.test.assertNotNull
import kotlin.test.fail

val apiServerRootUrl = System.getenv("API_URL") ?: "http://localhost:9999"
val httpClient = OkHttpClient.Builder().build()
val applicationJson = "application/json".toMediaType()

class AbnormalApiResponseException(val response: Response) : Exception()

class ApiClient(val ecKeyPair: ECKeyPair = Keys.createEcKeyPair()) {
    val authToken: String = issueAuthToken(ecKeyPair = ecKeyPair)

    companion object {
        fun listOrders(authHeadersProvider: (Request) -> Headers): OrdersApiResponse {
            val httpResponse = execute(
                Request.Builder()
                    .url("$apiServerRootUrl/v1/orders")
                    .get()
                    .build().let {
                        it.addHeaders(authHeadersProvider(it))
                    },
            )
            return if (httpResponse.code == HttpURLConnection.HTTP_OK) {
                json.decodeFromString<OrdersApiResponse>(httpResponse.body?.string()!!)
            } else {
                throw AbnormalApiResponseException(httpResponse)
            }
        }

        internal fun authHeaders(ecKeyPair: ECKeyPair): Headers {
            val didToken = issueAuthToken(ecKeyPair)

            return Headers.Builder()
                .add(
                    "Authorization",
                    "Bearer $didToken",
                ).build()
        }

        fun issueAuthToken(
            ecKeyPair: ECKeyPair = Keys.createEcKeyPair(),
            address: Address = Address("0x${Keys.getAddress(ecKeyPair)}"),
            chainId: ChainId = ChainId(1337U),
            timestamp: Instant = Clock.System.now(),
        ): String {
            val message = SignInMessage(
                message = "[ChainRing Labs] Please sign this message to verify your ownership of this wallet address. This action will not cost any gas fees.",
                address = address,
                chainId = chainId,
                timestamp = timestamp.toString(),
            )

            val body: String = Json.encodeToString(message)
            val signature: EvmSignature = ECHelper.signData(Credentials.create(ecKeyPair), EIP712Helper.computeHash(message))

            return "${Base64.getUrlEncoder().encodeToString(body.toByteArray())}.${signature.value}"
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

        private fun execute(request: Request): Response =
            httpClient.newCall(request).execute()
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

    fun createOrder(apiRequest: CreateOrderApiRequest): Either<ApiError, Order> {
        return execute(
            Request.Builder()
                .url("$apiServerRootUrl/v1/orders")
                .post(Json.encodeToString(apiRequest).toRequestBody(applicationJson))
                .build()
                .withAuthHeaders(ecKeyPair),
        ).toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_CREATED)
    }

    fun batchOrders(apiRequest: BatchOrdersApiRequest): Either<ApiError, OrdersApiResponse> {
        return execute(
            Request.Builder()
                .url("$apiServerRootUrl/v1/batch/orders")
                .post(Json.encodeToString(apiRequest).toRequestBody(applicationJson))
                .build()
                .withAuthHeaders(ecKeyPair),
        ).toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_OK)
    }

    fun updateOrder(apiRequest: UpdateOrderApiRequest): Either<ApiError, Order> {
        return execute(
            Request.Builder()
                .url("$apiServerRootUrl/v1/orders/${apiRequest.orderId}")
                .patch(Json.encodeToString(apiRequest).toRequestBody(applicationJson))
                .build()
                .withAuthHeaders(ecKeyPair),
        ).toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_OK)
    }

    fun cancelOrder(id: OrderId): Either<ApiError, Unit> {
        return execute(
            Request.Builder()
                .url("$apiServerRootUrl/v1/orders/$id")
                .delete()
                .build()
                .withAuthHeaders(ecKeyPair),
        ).toErrorOrUnit(expectedStatusCode = HttpURLConnection.HTTP_NO_CONTENT)
    }

    fun getOrder(id: OrderId): Order {
        val httpResponse = execute(
            Request.Builder()
                .url("$apiServerRootUrl/v1/orders/$id")
                .get()
                .build()
                .withAuthHeaders(ecKeyPair),
        )

        return when (httpResponse.code) {
            HttpURLConnection.HTTP_OK -> json.decodeFromString<Order>(httpResponse.body?.string()!!)
            else -> throw AbnormalApiResponseException(httpResponse)
        }
    }

    fun listOrders(): OrdersApiResponse {
        val httpResponse = execute(
            Request.Builder()
                .url("$apiServerRootUrl/v1/orders")
                .get()
                .build()
                .withAuthHeaders(ecKeyPair),
        )

        return when (httpResponse.code) {
            HttpURLConnection.HTTP_OK -> json.decodeFromString<OrdersApiResponse>(httpResponse.body?.string()!!)
            else -> throw AbnormalApiResponseException(httpResponse)
        }
    }

    fun cancelOpenOrders(): Either<ApiError, Unit> {
        return execute(
            Request.Builder()
                .url("$apiServerRootUrl/v1/orders")
                .delete()
                .build()
                .withAuthHeaders(ecKeyPair),
        ).toErrorOrUnit(expectedStatusCode = HttpURLConnection.HTTP_NO_CONTENT)
    }

    fun createWithdrawal(apiRequest: CreateWithdrawalApiRequest): Either<ApiError, WithdrawalApiResponse> {
        return execute(
            Request.Builder()
                .url("$apiServerRootUrl/v1/withdrawals")
                .post(Json.encodeToString(apiRequest).toRequestBody(applicationJson))
                .build()
                .withAuthHeaders(ecKeyPair),
        ).toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_CREATED)
    }

    fun getWithdrawal(id: WithdrawalId): WithdrawalApiResponse {
        val httpResponse = execute(
            Request.Builder()
                .url("$apiServerRootUrl/v1/withdrawals/$id")
                .get()
                .build()
                .withAuthHeaders(ecKeyPair),
        )

        return when (httpResponse.code) {
            HttpURLConnection.HTTP_OK -> json.decodeFromString<WithdrawalApiResponse>(httpResponse.body?.string()!!)
            else -> throw AbnormalApiResponseException(httpResponse)
        }
    }

    fun createSequencerDeposit(apiRequest: TestRoutes.Companion.CreateSequencerDeposit) {
        execute(
            Request.Builder()
                .url("$apiServerRootUrl/v1/sequencer-deposits")
                .post(Json.encodeToString(apiRequest).toRequestBody(applicationJson))
                .build()
                .withAuthHeaders(ecKeyPair),
        ).also { httpResponse ->
            if (httpResponse.code != HttpURLConnection.HTTP_CREATED) {
                throw AbnormalApiResponseException(httpResponse)
            }
        }
    }

    fun getBalances(): BalancesApiResponse {
        val httpResponse = execute(
            Request.Builder()
                .url("$apiServerRootUrl/v1/balances")
                .get()
                .build()
                .withAuthHeaders(ecKeyPair),
        )

        return when (httpResponse.code) {
            HttpURLConnection.HTTP_OK -> json.decodeFromString<BalancesApiResponse>(httpResponse.body?.string()!!)
            else -> throw AbnormalApiResponseException(httpResponse)
        }
    }

    private fun execute(request: Request): Response =
        httpClient.newCall(request).execute()
}

val json = Json {
    this.ignoreUnknownKeys = true
}

val prettyJsonFormatter = Json {
    this.prettyPrint = true
}

fun Request.withAuthHeaders(ecKeyPair: ECKeyPair?): Request =
    if (ecKeyPair == null) {
        this
    } else {
        addHeaders(ApiClient.authHeaders(ecKeyPair))
    }

fun Response.apiError(): ApiError? {
    return this.body?.string()?.let {
        try {
            json.decodeFromString<ApiErrors>(it).errors.single()
        } catch (e: Exception) {
            null
        }
    }
}

inline fun <reified T> Response.toErrorOrPayload(expectedStatusCode: Int): Either<ApiError, T> {
    return either {
        val bodyString = body?.string()

        ensure(code == expectedStatusCode) {
            val apiError = bodyString?.let {
                try {
                    json.decodeFromString<ApiErrors>(bodyString).errors.single()
                } catch (e: Exception) {
                    null
                }
            }

            assertNotNull(apiError, "API call failed with code: $code, body: $bodyString")

            apiError
        }

        json.decodeFromString<T>(bodyString!!)
    }
}

fun Response.toErrorOrUnit(expectedStatusCode: Int): Either<ApiError, Unit> {
    return either {
        val bodyString = body?.string()

        ensure(code == expectedStatusCode) {
            val apiError = bodyString?.let {
                try {
                    json.decodeFromString<ApiErrors>(bodyString).errors.single()
                } catch (e: Exception) {
                    null
                }
            }

            assertNotNull(apiError, "API call failed with code: $code, body: $bodyString")

            apiError
        }
    }
}

fun <T> Either<ApiError, T>.assertSuccess(): T {
    if (this.isLeft()) {
        fail(
            "Unexpected API error: ${this.leftOrNull()}. ${
                runCatching { ApiClient.getSequencerStateDump() }.map { " Sequencer state: ${prettyJsonFormatter.encodeToString(it) }" }
            }",
        )
    }
    return this.getOrNull()!!
}

fun Either<ApiError, Any>.assertError(expectedError: ApiError) {
    if (this.isRight()) {
        fail("Unexpected API error, but got a success response")
    }
    assertEquals(expectedError, this.leftOrNull()!!)
}

private fun Request.addHeaders(headers: Headers): Request =
    this
        .newBuilder()
        .headers(
            this
                .headers
                .newBuilder()
                .addAll(headers)
                .build(),
        ).build()

fun base64UrlEncode(input: String): String {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(input.toByteArray())
}

val Headers.Companion.empty: Headers
    get() = emptyMap<String, String>().toHeaders()
