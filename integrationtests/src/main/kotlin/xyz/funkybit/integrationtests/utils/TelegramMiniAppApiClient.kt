package xyz.funkybit.integrationtests.utils

import arrow.core.Either
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.http4k.core.toUrlFormEncoded
import org.http4k.security.HmacSha256.hmacSHA256
import xyz.funkybit.apps.api.middleware.TelegramMiniAppUserData
import xyz.funkybit.apps.api.model.tma.ClaimRewardApiRequest
import xyz.funkybit.apps.api.model.tma.GetUserApiResponse
import xyz.funkybit.apps.api.model.tma.ReactionTimeApiRequest
import xyz.funkybit.apps.api.model.tma.ReactionsTimeApiResponse
import xyz.funkybit.apps.api.model.tma.SingUpApiRequest
import xyz.funkybit.core.model.telegram.TelegramUserId
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppInviteCode
import java.net.HttpURLConnection

class TelegramMiniAppApiClient(val telegramUserId: TelegramUserId) {
    fun signUp(inviteCode: TelegramMiniAppInviteCode? = null): GetUserApiResponse =
        trySignUp(inviteCode).assertSuccess()

    fun trySignUp(inviteCode: TelegramMiniAppInviteCode? = null): Either<ApiCallFailure, GetUserApiResponse> =
        httpClient.newCall(
            Request.Builder()
                .url("$apiServerRootUrl/tma/v1/user")
                .post(Json.encodeToString(SingUpApiRequest(inviteCode)).toRequestBody(applicationJson))
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

    fun recordReactionTime(apiRequest: ReactionTimeApiRequest): ReactionsTimeApiResponse =
        tryRecordReactionTime(apiRequest).assertSuccess()

    fun tryRecordReactionTime(apiRequest: ReactionTimeApiRequest): Either<ApiCallFailure, ReactionsTimeApiResponse> =
        httpClient.newCall(
            Request.Builder()
                .url("$apiServerRootUrl/tma/v1/reaction-time")
                .post(Json.encodeToString(apiRequest).toRequestBody(applicationJson))
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
