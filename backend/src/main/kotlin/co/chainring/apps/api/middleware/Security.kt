package co.chainring.apps.api.middleware

import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.ReasonCode
import co.chainring.apps.api.model.RequestProcessingError
import co.chainring.apps.api.model.errorResponse
import co.chainring.apps.api.requestContexts
import co.chainring.core.evm.ECHelper
import co.chainring.core.evm.EIP712Helper
import co.chainring.core.model.Address
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.db.ChainId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.http4k.contract.security.Security
import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.lens.RequestContextKey
import java.util.Base64
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

val signedLoginRequestHeader = object : Security {
    override val filter = Filter { next -> wrapWithAuthentication(next) }

    private fun wrapWithAuthentication(httpHandler: HttpHandler): HttpHandler = { request ->
        when (val authResult = authenticate(request)) {
            is AuthResult.Success -> {
                val requestWithPrincipal =
                    request.with(
                        principalRequestContextKey of authResult.address,
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
        return AuthResult.Success(signInMessage.address, endOfValidityInterval(signInMessage))
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
    val acceptableValidityInterval = messageTimestamp + AUTHORIZATION_VALIDITY_INTERVAL > currentTime

    return timestampIsInThePast && acceptableValidityInterval
}

private fun endOfValidityInterval(signInMessage: SignInMessage): Instant =
    Instant.parse(signInMessage.timestamp) + AUTHORIZATION_VALIDITY_INTERVAL

private fun validateSignature(signInMessage: SignInMessage, signature: String): Boolean {
    return ECHelper.isValidSignature(
        messageHash = EIP712Helper.computeHash(signInMessage),
        signature = EvmSignature(signature),
        signerAddress = signInMessage.address,
    )
}

private fun missingAuthorizationHeader(): AuthResult.Failure = authFailure("Authorization header is missing")
private fun authFailure(error: String): AuthResult.Failure = AuthResult.Failure(unauthorizedResponse(error))

@Serializable
data class SignInMessage(
    val message: String,
    val address: Address,
    val chainId: ChainId,
    val timestamp: String,
)

private val logger = KotlinLogging.logger {}
private const val AUTHORIZATION_SCHEME_PREFIX = "Bearer "
private val AUTHORIZATION_VALIDITY_INTERVAL = 30.days

sealed class AuthResult {
    data class Success(val address: Address, val expiresAt: Instant) : AuthResult()
    data class Failure(val response: Response) : AuthResult()
}

private val principalRequestContextKey =
    RequestContextKey.optional<Address>(requestContexts)

val Request.principal: Address
    get() = principalRequestContextKey(this) ?: throw RequestProcessingError(unauthorizedResponse("Unauthorized"))

private fun unauthorizedResponse(message: String) = errorResponse(
    Status.UNAUTHORIZED,
    ApiError(ReasonCode.AuthenticationError, message),
)
