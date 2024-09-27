package xyz.funkybit.core.blockchain.bitcoin

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class BasicAuthInterceptor(user: String, password: String) : Interceptor {
    private val credentials: String

    init {
        credentials = Credentials.basic(user, password)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request: Request = chain.request()
        val authenticatedRequest = request.newBuilder()
            .header("Authorization", credentials).build()
        return chain.proceed(authenticatedRequest)
    }
}

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
)

class JsonRpcException(val error: JsonRpcError) : Exception(error.message)

open class JsonRpcClientBase(val url: String, interceptor: Interceptor? = null) {
    open val logger = KotlinLogging.logger {}

    @OptIn(ExperimentalSerializationApi::class)
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        explicitNulls = false
    }

    val httpClient = OkHttpClient.Builder()
        .let { builder ->
            interceptor?.let { builder.addInterceptor(it) } ?: builder
        }
        .build()

    open val mediaType = "text/plain".toMediaTypeOrNull()

    fun call(requestString: String, logResponseBody: Boolean = true, noLog: Boolean = false): JsonElement {
        val body = requestString.toRequestBody(mediaType)

        if (!noLog) {
            logger.debug { "Sending -> ${abbreviateString(requestString)}" }
        }
        val httpRequest = Request.Builder()
            .url(url)
            .post(body)
            .build()
        val httpResponse = try {
            httpClient.newCall(httpRequest).execute()
        } catch (e: Exception) {
            throw Exception("send error: ${e.message}")
        }
        val httpResponseBody = httpResponse.body?.string() ?: ""
        if (!noLog) {
            if (logResponseBody || httpResponse.code >= 400) {
                logger.debug { "Received(${httpResponse.code}) <- ${abbreviateString(httpResponseBody)}" }
            } else {
                logger.debug { "Received(${httpResponse.code}) <- (...elided...)" }
            }
        }
        return when (httpResponse.code) {
            in 200..204 -> {
                when (val jsonBody = json.parseToJsonElement(httpResponseBody)) {
                    is JsonObject -> if (jsonBody.containsKey("error") && jsonBody["error"] != JsonNull) {
                        throw JsonRpcException(json.decodeFromJsonElement<JsonRpcError>(jsonBody["error"]!!))
                    } else {
                        jsonBody["result"]
                    }
                    else -> null
                } ?: throw Exception("Failed to extract response")
            }
            else -> {
                // it seems to also give 500s with RPC errors or RPC errors embedded within a string
                try {
                    (json.parseToJsonElement(httpResponseBody) as JsonObject)["error"]?.let {
                        json.decodeFromJsonElement<JsonRpcError>(it)
                    }
                } catch (e: Exception) {
                    null
                }?.let {
                    throw JsonRpcException(it)
                }
                throw Exception("Got an error (${httpResponse.code}) - $httpResponseBody")
            }
        }
    }

    inline fun <reified T> getValue(requestString: String, logResponseBody: Boolean = true): T {
        val jsonElement = call(requestString, logResponseBody)
        return json.decodeFromJsonElement(jsonElement)
    }

    private fun abbreviateString(value: String): String {
        return if (value.length < 4096) {
            value
        } else {
            "${value.substring(0, 2048)} ... ${value.substring(value.length - 2048, value.length)}"
        }
    }
}
