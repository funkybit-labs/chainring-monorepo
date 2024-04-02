package co.chainring.integrationtests.testutils

import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.ApiErrors
import co.chainring.apps.api.model.ConfigurationApiResponse
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.CreateWithdrawalApiRequest
import co.chainring.apps.api.model.Order
import co.chainring.apps.api.model.OrdersApiResponse
import co.chainring.apps.api.model.UpdateOrderApiRequest
import co.chainring.apps.api.model.WithdrawalApiResponse
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.WithdrawalId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.HttpURLConnection

val apiServerRootUrl = System.getenv("API_URL") ?: "http://localhost:9999"
val httpClient = OkHttpClient.Builder().build()
val applicationJson = "application/json".toMediaType()

class AbnormalApiResponseException(val response: Response) : Exception()

class ApiClient {

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
                .build(),
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
                .build(),
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
                .build(),
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
                .build(),
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
                .build(),
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
                .build(),
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
                .build(),
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
                .build(),
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

fun Response.apiError(): ApiError? {
    return this.body?.string()?.let {
        try {
            json.decodeFromString<ApiErrors>(it).errors.single()
        } catch (e: Exception) {
            null
        }
    }
}
