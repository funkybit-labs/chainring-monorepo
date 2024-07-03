package co.chainring.apps.api.middleware

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.ReasonCode
import co.chainring.apps.api.model.RequestProcessingError
import co.chainring.apps.api.model.errorResponse
import co.chainring.apps.api.requestContexts
import co.chainring.core.evm.ECHelper
import co.chainring.core.evm.EIP712Helper
import co.chainring.core.model.Address
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.telegram.TelegramUserId
import co.chainring.core.model.telegram.miniapp.TelegramMiniAppUserEntity
import co.chainring.core.model.toChecksumAddress
import co.chainring.core.model.toEvmSignature
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.http4k.contract.security.Security
import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.toParameters
import org.http4k.core.toParametersMap
import org.http4k.core.with
import org.http4k.format.KotlinxSerialization
import org.http4k.lens.RequestContextKey
import org.http4k.security.HmacSha256.hmacSHA256
import org.jetbrains.exposed.sql.transactions.transaction
import org.web3j.crypto.Keys
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

val signedTokenSecurity = object : Security {
    override val filter = Filter { next -> wrapWithAuthentication(next) }

    private fun wrapWithAuthentication(httpHandler: HttpHandler): HttpHandler = { request ->
        when (val authResult = authenticate(request)) {
            is AuthResult.Success -> {
                val requestWithPrincipal =
                    request.with(
                        principalRequestContextKey of authResult.address.toChecksumAddress(),
                    )
                httpHandler(requestWithPrincipal)
            }
            is AuthResult.Failure -> {
                logger.info { "Authentication failed with status ${authResult.response.status.code} and error '${authResult.response.bodyString()}'" }
                authResult.response
            }
        }
    }

    private fun authenticate(request: Request): AuthResult {
        val authHeader = request.header("Authorization")?.trim() ?: return missingAuthorizationHeader()

        if (!authHeader.startsWith(AUTHORIZATION_SCHEME_PREFIX, ignoreCase = true)) {
            return authFailure("Invalid authentication scheme")
        }

        return validateAuthToken(authHeader.removePrefix(AUTHORIZATION_SCHEME_PREFIX))
    }
}

fun validateAuthToken(token: String): AuthResult {
    val message = token.substringBefore('.')
    val signature = token.substringAfter('.')

    val signInMessage = decodeSignInMessage(message) ?: return authFailure("Invalid token format")

    if (!validateExpiry(signInMessage)) {
        return authFailure("Token is expired or not valid yet")
    }

    if (validateSignature(signInMessage, signature)) {
        return AuthResult.Success(Address(Keys.toChecksumAddress(signInMessage.address)), endOfValidityInterval(signInMessage))
    }

    return authFailure("Invalid signature")
}

fun decodeSignInMessage(message: String): SignInMessage? {
    return runCatching {
        Json.decodeFromString<SignInMessage>(String(Base64.getUrlDecoder().decode(message)))
    }.getOrNull()
}

private fun validateExpiry(signInMessage: SignInMessage): Boolean {
    val currentTime = Clock.System.now()
    val messageTimestamp = Instant.parse(signInMessage.timestamp)

    val timestampIsInThePast = (messageTimestamp - currentTime) < 10.seconds
    val acceptableValidityInterval = messageTimestamp + AUTH_TOKEN_VALIDITY_INTERVAL > currentTime

    return timestampIsInThePast && acceptableValidityInterval
}

private fun endOfValidityInterval(signInMessage: SignInMessage): Instant =
    Instant.parse(signInMessage.timestamp) + AUTH_TOKEN_VALIDITY_INTERVAL

private fun validateSignature(signInMessage: SignInMessage, signature: String): Boolean {
    return runCatching {
        ECHelper.isValidSignature(
            messageHash = EIP712Helper.computeHash(signInMessage),
            signature = signature.toEvmSignature(),
            signerAddress = Address(Keys.toChecksumAddress(signInMessage.address)),
        )
    }.getOrElse { false }
}

private fun missingAuthorizationHeader(): AuthResult.Failure = authFailure("Authorization header is missing")
private fun authFailure(error: String): AuthResult.Failure = AuthResult.Failure(unauthorizedResponse(error))

@Serializable
data class SignInMessage(
    val message: String,
    val address: String,
    val chainId: ChainId,
    val timestamp: String,
)

private val logger = KotlinLogging.logger {}
private const val AUTHORIZATION_SCHEME_PREFIX = "Bearer "
private val AUTH_TOKEN_VALIDITY_INTERVAL = System.getenv("AUTH_TOKEN_VALIDITY_INTERVAL")?.let { Duration.parse(it) } ?: 30.days

sealed class AuthResult {
    data class Success(val address: Address, val expiresAt: Instant) : AuthResult()
    data class Failure(val response: Response) : AuthResult()
}

private val principalRequestContextKey =
    RequestContextKey.optional<Address>(requestContexts)

val Request.principal: Address
    get() = principalRequestContextKey(this)
        ?: throw RequestProcessingError(Status.UNAUTHORIZED, ApiError(ReasonCode.AuthenticationError, "Unauthorized"))

private fun unauthorizedResponse(message: String) = errorResponse(
    Status.UNAUTHORIZED,
    ApiError(ReasonCode.AuthenticationError, message),
)

@Serializable
data class TelegramMiniAppUserData(
    @SerialName("id")
    val userId: TelegramUserId,
    @SerialName("first_name")
    val firstName: String,
    @SerialName("last_name")
    val lastName: String,
    @SerialName("language_code")
    val languageCode: String,
    @SerialName("allows_write_to_pm")
    val allowsWriteToPm: Boolean = false,
)

data class TelegramMiniAppPrincipal(
    val userData: TelegramMiniAppUserData,
    val maybeUser: TelegramMiniAppUserEntity?,
)

private val telegramMiniAppPrincipalRequestContextKey =
    RequestContextKey.optional<TelegramMiniAppPrincipal>(requestContexts)

val Request.telegramMiniAppPrincipal: TelegramMiniAppPrincipal
    get() = telegramMiniAppPrincipalRequestContextKey(this)
        ?: throw RequestProcessingError(Status.UNAUTHORIZED, ApiError(ReasonCode.AuthenticationError, "Unauthorized"))

val Request.telegramMiniAppUser: TelegramMiniAppUserEntity
    get() =
        telegramMiniAppPrincipal.maybeUser
            ?: throw RequestProcessingError(Status.UNAUTHORIZED, ApiError(ReasonCode.AuthenticationError, "Unauthorized"))

@OptIn(ExperimentalStdlibApi::class)
val telegramMiniAppSecurity = object : Security {
    override val filter = Filter { next -> wrapWithAuthentication(next) }

    private val botToken = System.getenv("TELEGRAM_BOT_TOKEN")
        ?: "123:456" // dummy value, used by integration tests

    private fun wrapWithAuthentication(httpHandler: HttpHandler): HttpHandler = { request ->
        authenticate(request).fold(
            ifLeft = { error ->
                logger.info { "Authentication failed with error $error" }
                errorResponse(Status.UNAUTHORIZED, error)
            },
            ifRight = { userData ->
                val requestWithPrincipal = request.with(
                    telegramMiniAppPrincipalRequestContextKey of TelegramMiniAppPrincipal(
                        userData,
                        transaction { TelegramMiniAppUserEntity.findByTelegramUserId(userData.userId) },
                    ),
                )
                httpHandler(requestWithPrincipal)
            },
        )
    }

    private fun authenticate(request: Request): Either<ApiError, TelegramMiniAppUserData> {
        val authHeader = request.header("Authorization")?.trim()
            ?: return ApiError(ReasonCode.AuthenticationError, "Authorization header is missing").left()

        if (!authHeader.startsWith(AUTHORIZATION_SCHEME_PREFIX, ignoreCase = true)) {
            return ApiError(ReasonCode.AuthenticationError, "Invalid authentication scheme").left()
        }

        val telegramWebAppInitDataString = authHeader.removePrefix(AUTHORIZATION_SCHEME_PREFIX)
        val params = telegramWebAppInitDataString.toParameters()
        val paramsMap = params.toParametersMap()
        val hash = paramsMap["hash"]?.first()
            ?: return ApiError(ReasonCode.AuthenticationError, "Hash is missing").left()

        val dataCheckString = params
            .filter { (key, _) -> key != "hash" }
            .sortedBy { (key, _) -> key }
            .joinToString("\n") { (key, value) ->
                "$key=${value ?: ""}"
            }

        val secretKey = hmacSHA256("WebAppData".toByteArray(), botToken)

        if (hmacSHA256(secretKey, dataCheckString).toHexString() != hash) {
            return ApiError(ReasonCode.AuthenticationError, "Invalid signature").left()
        }

        return paramsMap["user"]?.first()
            ?.let { KotlinxSerialization.json.decodeFromString<TelegramMiniAppUserData>(it).right() }
            ?: ApiError(ReasonCode.AuthenticationError, "User data is missing").left()
    }
}
