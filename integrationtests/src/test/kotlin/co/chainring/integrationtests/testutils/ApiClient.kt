package co.chainring.integrationtests.testutils

import co.chainring.apps.api.model.ConfigurationApiResponse
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
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

    private fun execute(request: Request): Response =
        httpClient.newCall(request).execute()
}

private val json = Json {
    this.ignoreUnknownKeys = true
}
