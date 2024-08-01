package co.chainring.core.blockchain.bitcoin

import io.github.oshai.kotlinlogging.KotlinLogging
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
import java.util.*

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

open class JsonRpcClientBase(val url: String, interceptor: Interceptor? = null) {
    open val logger = KotlinLogging.logger {}

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    val httpClient = OkHttpClient.Builder()
        .let { builder ->
            interceptor?.let { builder.addInterceptor(it) } ?: builder
        }
        .build()

    open val mediaType = "text/plain".toMediaTypeOrNull()

    fun call(requestString: String, logResponseBody: Boolean = true): JsonElement {
        val body = requestString.toRequestBody(mediaType)

        logger.debug { "Sending -> ${abbreviateString(requestString)}" }
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
        if (logResponseBody || httpResponse.code >= 400) {
            logger.debug { "Received(${httpResponse.code}) <- ${abbreviateString(httpResponseBody)}" }
        } else {
            logger.debug { "Received(${httpResponse.code}) <- (...elided...)" }
        }
        return when (httpResponse.code) {
            in 200..204 -> {
                when (val jsonBody = json.parseToJsonElement(httpResponseBody)) {
                    is JsonObject -> if (jsonBody.containsKey("error") && jsonBody["error"] != JsonNull) {
                        throw Exception("got error ${jsonBody["error"]}")
                    } else {
                        jsonBody["result"]
                    }
                    else -> null
                } ?: throw Exception("Failed to extract response")
            }
            else -> {
                // it seems to also give 500s with RPC errors or RPC errors embedded within a string
                try {
                    (json.parseToJsonElement(httpResponseBody) as JsonObject)["error"]
                } catch (e: Exception) {
                    null
                }?.let {
                    throw Exception(it.toString())
                }
                throw Exception("Got an error (${httpResponse.code}) - $httpResponseBody")
            }
        }
    }

    inline fun <reified T> getValue(requestString: String, logResponseBody: Boolean = true): T {
        val jsonElement = call(requestString, logResponseBody)
        return json.decodeFromJsonElement(jsonElement)
    }

    inline fun abbreviateString(value: String): String {
        if (value.length < 4096) {
            return value
        } else {
            return "${value.substring(0, 2048)} ... ${value.substring(value.length - 2048, value.length)}"
        }
    }
}
