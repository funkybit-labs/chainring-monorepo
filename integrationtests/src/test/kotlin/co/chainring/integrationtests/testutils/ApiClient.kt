package co.chainring.integrationtests.testutils

import co.chainring.apps.api.middleware.SignInMessage
import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.ApiErrors
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
import org.web3j.crypto.Credentials
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import java.net.HttpURLConnection
import java.util.Base64

val apiServerRootUrl = System.getenv("API_URL") ?: "http://localhost:9999"
val httpClient = OkHttpClient.Builder().build()
val applicationJson = "application/json".toMediaType()

class AbnormalApiResponseException(val response: Response) : Exception()

class ApiClient(val ecKeyPair: ECKeyPair?) {
    val authToken: String? = ecKeyPair?.let { issueAuthToken(ecKeyPair = it) }

    companion object {
        fun initWallet(
            ecKeyPair: ECKeyPair? = Keys.createEcKeyPair(),
        ): ApiClient {
            return ApiClient(ecKeyPair)
        }

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

            return "$body.${signature.value}"
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

    fun createOrder(apiRequest: CreateOrderApiRequest): Order {
        val httpResponse = execute(
            Request.Builder()
                .url("$apiServerRootUrl/v1/orders")
                .post(Json.encodeToString(apiRequest).toRequestBody(applicationJson))
                .build()
                .withAuthHeaders(ecKeyPair),
        )

        return when (httpResponse.code) {
            HttpURLConnection.HTTP_CREATED -> json.decodeFromString<Order>(httpResponse.body?.string()!!)
            else -> throw AbnormalApiResponseException(httpResponse)
        }
    }

    fun updateOrder(apiRequest: UpdateOrderApiRequest): Order {
        val httpResponse = execute(
            Request.Builder()
                .url("$apiServerRootUrl/v1/orders/${apiRequest.id}")
                .patch(Json.encodeToString(apiRequest).toRequestBody(applicationJson))
                .build()
                .withAuthHeaders(ecKeyPair),
        )

        return when (httpResponse.code) {
            HttpURLConnection.HTTP_OK -> json.decodeFromString<Order>(httpResponse.body?.string()!!)
            else -> throw AbnormalApiResponseException(httpResponse)
        }
    }

    fun cancelOrder(id: OrderId) {
        val httpResponse = execute(
            Request.Builder()
                .url("$apiServerRootUrl/v1/orders/$id")
                .delete()
                .build()
                .withAuthHeaders(ecKeyPair),
        )

        return when (httpResponse.code) {
            HttpURLConnection.HTTP_NO_CONTENT -> Unit
            else -> throw AbnormalApiResponseException(httpResponse)
        }
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

    fun cancelOpenOrders() {
        val httpResponse = execute(
            Request.Builder()
                .url("$apiServerRootUrl/v1/orders")
                .delete()
                .build()
                .withAuthHeaders(ecKeyPair),
        )

        return when (httpResponse.code) {
            HttpURLConnection.HTTP_NO_CONTENT -> Unit
            else -> throw AbnormalApiResponseException(httpResponse)
        }
    }

    fun createWithdrawal(apiRequest: CreateWithdrawalApiRequest): WithdrawalApiResponse {
        val httpResponse = execute(
            Request.Builder()
                .url("$apiServerRootUrl/v1/withdrawals")
                .post(Json.encodeToString(apiRequest).toRequestBody(applicationJson))
                .build()
                .withAuthHeaders(ecKeyPair),
        )

        return when (httpResponse.code) {
            HttpURLConnection.HTTP_CREATED -> json.decodeFromString<WithdrawalApiResponse>(httpResponse.body?.string()!!)
            else -> throw AbnormalApiResponseException(httpResponse)
        }
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

    private fun execute(request: Request): Response =
        httpClient.newCall(request).execute()
}

private val json = Json {
    this.ignoreUnknownKeys = true
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
