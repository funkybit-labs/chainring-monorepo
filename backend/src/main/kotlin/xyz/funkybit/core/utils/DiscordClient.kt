package xyz.funkybit.core.utils

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request

object DiscordClient {
    private val clientId = System.getenv("DISCORD_CLIENT_ID") ?: ""
    private val clientSecret = System.getenv("DISCORD_CLIENT_SECRET") ?: ""
    private val redirectUri = System.getenv("DISCORD_REDIRECT_URI") ?: "http://localhost:3000/discord-callback"
    private val urlBase = System.getenv("DISCORD_API_URL") ?: "https://discord.com/api"
    private val logging = KotlinLogging.logger {}

    private val httpClient = HttpClient
        .newBuilder()
        .withLogging(logging)
        .build()

    private val mediaType = "application/x-www-form-urlencoded".toMediaTypeOrNull()

    fun exchangeCodeForTokens(code: String): Pair<String, String> {
        val httpRequest = Request.Builder()
            .url("$urlBase/oauth2/token")
            .post(
                FormBody.Builder()
                    .add("client_id", clientId)
                    .add("client_secret", clientSecret)
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("redirect_uri", redirectUri)
                    .build(),
            )
            .build()
        val httpResponse = try {
            httpClient.newCall(httpRequest).execute()
        } catch (e: Exception) {
            throw Exception("send error: ${e.message}")
        }
        val httpResponseBody = httpResponse.body?.string() ?: ""
        return when (httpResponse.code) {
            in 200..204 -> {
                val jsonResponse = json.parseToJsonElement(httpResponseBody)
                val accessToken = jsonResponse.jsonObject["access_token"]?.toString()?.trim('"') ?: ""
                val refreshToken = jsonResponse.jsonObject["refresh_token"]?.toString()?.trim('"') ?: ""
                Pair(accessToken, refreshToken)
            }
            else -> throw Exception("Got an error (${httpResponse.code}) - $httpResponseBody")
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        explicitNulls = false
    }

    fun getDiscordUserId(accessToken: String): String? {
        val httpRequest = Request.Builder()
            .url("$urlBase/users/@me")
            .header("Authorization", "Bearer $accessToken")
            .header("User-Agent", "DiscordBot ($urlBase, 10)")
            .build()

        val httpResponse = try {
            httpClient.newCall(httpRequest).execute()
        } catch (e: Exception) {
            throw Exception("send error: ${e.message}")
        }
        val httpResponseBody = httpResponse.body?.string() ?: ""
        return when (httpResponse.code) {
            in 200..204 -> {
                val jsonResponse = json.parseToJsonElement(httpResponseBody)
                jsonResponse.jsonObject["id"]?.toString()?.trim('"')
            }
            else -> throw Exception("Got an error (${httpResponse.code}) - $httpResponseBody")
        }
    }
}
