package xyz.funkybit.core.utils

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.http4k.core.Uri
import org.http4k.core.query

object DiscordClient {
    private val clientId = System.getenv("DISCORD_CLIENT_ID") ?: ""
    private val clientSecret = System.getenv("DISCORD_CLIENT_SECRET") ?: ""
    private val redirectUri = System.getenv("DISCORD_REDIRECT_URI") ?: "http://localhost:3000/discord-callback"
    private val urlBase = System.getenv("DISCORD_API_URL") ?: "https://discord.com/api"
    private val funkybitGuildId = System.getenv("DISCORD_FUNKYBIT_GUILD_ID") ?: "1206128759485374474"
    private val botToken = System.getenv("DISCORD_BOT_TOKEN") ?: ""
    private val logging = KotlinLogging.logger {}

    private val httpClient = HttpClient
        .newBuilder()
        .withLogging(logging)
        .build()

    private val mediaType = "application/json".toMediaTypeOrNull()

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        explicitNulls = false
    }

    fun getOAuth2AuthorizeUrl(scope: String = "identify"): String =
        Uri
            .of("$urlBase/oauth2/authorize")
            .query("client_id", clientId)
            .query("redirect_uri", redirectUri)
            .query("scope", scope)
            .query("response_type", "code")
            .toString()

    private inline fun <reified T> jsonCall(request: Request): T {
        val httpResponse = httpClient.newCall(request).execute()
        val httpResponseBody = httpResponse.body?.string() ?: ""
        return when (httpResponse.code) {
            in 200..204 -> json.decodeFromString<T>(httpResponseBody)
            else -> throw Exception("Got an error (${httpResponse.code}) - $httpResponseBody")
        }
    }

    fun getAuthTokens(authorizationCode: OAuth2.AuthorizationCode): OAuth2.Tokens {
        val httpRequest = Request.Builder()
            .url("$urlBase/oauth2/token")
            .post(
                FormBody.Builder()
                    .add("client_id", clientId)
                    .add("client_secret", clientSecret)
                    .add("grant_type", "authorization_code")
                    .add("code", authorizationCode.value)
                    .add("redirect_uri", redirectUri)
                    .build(),
            )
            .build()
        return jsonCall(httpRequest)
    }

    fun joinFunkybitDiscord(userId: String, accessToken: String) {
        val headers = mapOf("Authorization" to "Bot $botToken").toHeaders()
        val httpRequest = Request.Builder()
            .url("$urlBase/guilds/$funkybitGuildId/members/$userId")
            .put(json.encodeToString(mapOf("access_token" to accessToken)).toRequestBody(mediaType))
            .headers(headers)
            .build()
        val httpResponse = httpClient.newCall(httpRequest).execute()
        if (!httpResponse.isSuccessful) {
            throw Exception("Got an error (${httpResponse.code}) - ${httpResponse.body?.string()}")
        }
    }

    @Serializable
    private data class UsersMeResponse(val id: String)

    fun getUserId(authTokens: OAuth2.Tokens): String {
        val httpRequest = Request.Builder()
            .url("$urlBase/users/@me")
            .header("Authorization", "Bearer ${authTokens.access}")
            .header("User-Agent", "DiscordBot ($urlBase, 10)")
            .build()

        return jsonCall<UsersMeResponse>(httpRequest).id
    }
}
