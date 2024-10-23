package xyz.funkybit.core.utils

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Request
import org.http4k.base64Encode
import org.http4k.core.Uri
import org.http4k.core.query
import java.util.UUID

object XClient {
    private val clientId = System.getenv("X_CLIENT_ID") ?: ""
    private val clientSecret = System.getenv("X_CLIENT_SECRET") ?: "-Hpxck"
    private val redirectUri = System.getenv("X_REDIRECT_URI") ?: "http://localhost:3000/x-callback"
    private val urlBase = System.getenv("X_API_URL") ?: "https://api.x.com"
    private val logging = KotlinLogging.logger {}

    private val httpClient = HttpClient
        .newBuilder()
        .withLogging(logging)
        .build()

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        explicitNulls = false
    }

    fun getOAuth2AuthorizeUrl(codeChallenge: OAuth2.CodeChallenge): String =
        Uri
            .of("https://x.com/i/oauth2/authorize")
            .query("client_id", clientId)
            .query("redirect_uri", redirectUri)
            .query("scope", listOf("offline.access", "users.read", "tweet.read").joinToString(" "))
            .query("response_type", "code")
            .query("code_challenge", codeChallenge.value)
            .query("code_challenge_method", "S256")
            .query("state", UUID.randomUUID().toString().replace("-", ""))
            .toString()

    fun getAuthTokens(authorizationCode: OAuth2.AuthorizationCode, codeVerifier: OAuth2.CodeVerifier): OAuth2.Tokens {
        val credentials = "$clientId:$clientSecret".base64Encode()
        val httpRequest = Request.Builder()
            .url("$urlBase/2/oauth2/token")
            .post(
                FormBody.Builder()
                    .add("client_id", clientId)
                    .add("grant_type", "authorization_code")
                    .add("code", authorizationCode.value)
                    .add("code_verifier", codeVerifier.value)
                    .add("redirect_uri", redirectUri)
                    .build(),
            )
            .addHeader("Authorization", "Basic $credentials")
            .build()

        val httpResponse = httpClient.newCall(httpRequest).execute()
        val httpResponseBody = httpResponse.body?.string() ?: ""
        return when (httpResponse.code) {
            in 200..204 -> json.decodeFromString<OAuth2.Tokens>(httpResponseBody)
            else -> throw Exception("Got an error (${httpResponse.code}) - $httpResponseBody")
        }
    }

    @Serializable
    private data class UsersMeResponse(val data: Data) {
        @Serializable
        data class Data(val id: String)
    }

    fun getUserId(authTokens: OAuth2.Tokens): String {
        val httpRequest = Request.Builder()
            .url("$urlBase/2/users/me")
            .header("Authorization", "Bearer ${authTokens.access}")
            .build()

        val httpResponse = httpClient.newCall(httpRequest).execute()
        val httpResponseBody = httpResponse.body?.string() ?: ""
        return when (httpResponse.code) {
            in 200..204 -> json.decodeFromString<UsersMeResponse>(httpResponseBody).data.id
            else -> throw Exception("Got an error (${httpResponse.code}) - $httpResponseBody")
        }
    }
}
