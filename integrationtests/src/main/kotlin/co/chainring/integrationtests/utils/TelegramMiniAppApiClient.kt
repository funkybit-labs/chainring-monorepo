package co.chainring.integrationtests.utils

import arrow.core.Either
import co.chainring.apps.api.middleware.TelegramMiniAppUserData
import co.chainring.apps.api.model.tma.ClaimRewardApiRequest
import co.chainring.apps.api.model.tma.GetUserApiResponse
import co.chainring.core.model.telegram.TelegramUserId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.EMPTY_REQUEST
import org.http4k.core.toUrlFormEncoded
import org.http4k.security.HmacSha256.hmacSHA256
import java.net.HttpURLConnection

class TelegramMiniAppApiClient(val telegramUserId: TelegramUserId) {
    fun signUp(): GetUserApiResponse =
        trySignUp().assertSuccess()

    fun trySignUp(): Either<ApiCallFailure, GetUserApiResponse> =
        httpClient.newCall(
            Request.Builder()
                .url("$apiServerRootUrl/tma/v1/user")
                .post(EMPTY_REQUEST)
                .build()
                .withAuthHeaders(telegramUserId),
        ).execute().toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_CREATED)

    fun getUser(): GetUserApiResponse =
        tryGetUser().assertSuccess()

    fun tryGetUser(): Either<ApiCallFailure, GetUserApiResponse> =
        httpClient.newCall(
            Request.Builder()
                .url("$apiServerRootUrl/tma/v1/user")
                .get()
                .build()
                .withAuthHeaders(telegramUserId),
        ).execute().toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_OK)

    fun claimReward(apiRequest: ClaimRewardApiRequest): GetUserApiResponse =
        tryClaimReward(apiRequest).assertSuccess()

    fun tryClaimReward(apiRequest: ClaimRewardApiRequest): Either<ApiCallFailure, GetUserApiResponse> =
        httpClient.newCall(
            Request.Builder()
                .url("$apiServerRootUrl/tma/v1/rewards")
                .post(Json.encodeToString(apiRequest).toRequestBody(applicationJson))
                .build()
                .withAuthHeaders(telegramUserId),
        ).execute().toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_OK)

    companion object {
        fun tryGetUser(authHeadersProvider: (Request) -> Headers): Either<ApiCallFailure, GetUserApiResponse> =
            httpClient.newCall(
                Request.Builder()
                    .url("$apiServerRootUrl/tma/v1/user")
                    .get()
                    .build().let {
                        it.addHeaders(authHeadersProvider(it))
                    },
            ).execute().toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_OK)

        @OptIn(ExperimentalStdlibApi::class)
        fun authHeaders(telegramUserId: TelegramUserId, botToken: String = "123:456"): Headers {
            val user = TelegramMiniAppUserData(
                telegramUserId,
                firstName = "Name$telegramUserId",
                lastName = "LastName$telegramUserId",
                languageCode = "en",
                allowsWriteToPm = true,
            )

            val params = listOf(
                Pair("user", json.encodeToString(user)),
            )

            val dataCheckString = params
                .filter { (key, _) -> key != "hash" }
                .sortedBy { (key, _) -> key }
                .joinToString("\n") { (key, value) ->
                    "$key=$value"
                }

            val secretKey = hmacSHA256("WebAppData".toByteArray(), botToken)
            val hash = hmacSHA256(secretKey, dataCheckString).toHexString()

            val token = (params + listOf(Pair("hash", hash))).toUrlFormEncoded()

            return Headers
                .Builder()
                .add(
                    "Authorization",
                    "Bearer $token",
                )
                .build()
        }
    }
}

fun Request.withAuthHeaders(telegramUserId: TelegramUserId?): Request =
    if (telegramUserId == null) {
        this
    } else {
        addHeaders(TelegramMiniAppApiClient.authHeaders(telegramUserId))
    }
