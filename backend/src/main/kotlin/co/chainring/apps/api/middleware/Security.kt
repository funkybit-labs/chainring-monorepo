package co.chainring.apps.api.middleware

import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.ReasonCode
import co.chainring.apps.api.model.RequestProcessingError
import co.chainring.apps.api.model.errorResponse
import co.chainring.apps.api.requestContexts
import co.chainring.core.evm.ECHelper
import co.chainring.core.model.Address
import co.chainring.core.model.EvmSignature
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

val signedDidTokenHeader = object : Security {
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
        val authHeader = request.header("Authorization")?.trim()
            ?: return missingHeader("Authorization")

        if (!authHeader.startsWith(AUTHORIZATION_SCHEME_PREFIX, ignoreCase = true)) {
            return authFailure("Invalid authentication scheme")
        }

        return validateDidToken(authHeader.removePrefix(AUTHORIZATION_SCHEME_PREFIX))
    }
}

fun validateDidToken(didToken: String): AuthResult {
    val message = didToken.substringBeforeLast('.')
    val signature = didToken.substringAfterLast('.')
    val claims = decodeJwtClaims(message) ?: return authFailure("Invalid token format")

    if (!validateTokenExpiry(claims)) {
        return authFailure("Token is expired or not valid yet")
    }

    if (!validateAudience(claims)) {
        return authFailure("Invalid audience")
    }

    val issuerAddress = extractIssuerAddress(claims) ?: return authFailure("Invalid issuer")

    if (validateSignature(message, signature, issuerAddress)) {
        return AuthResult.Success(issuerAddress, expiryDate(claims))
    }

    return authFailure("Invalid signature")
}

private fun decodeJwtClaims(message: String): TokenClaims? =
    message.substringAfter('.').let { encodedClaims ->
        runCatching {
            Json.decodeFromString<TokenClaims>(String(Base64.getUrlDecoder().decode(encodedClaims)))
        }.getOrNull()
    }

private fun validateTokenExpiry(claims: TokenClaims): Boolean {
    val currentTime = Clock.System.now().epochSeconds

    val notExpired = claims.ext - currentTime > -10
    val issuedBeforeExpiry = claims.ext > claims.iat
    val maximumDurationIsAcceptable = (claims.ext - claims.iat) < 30 * 24 * 60 * 60

    return notExpired && issuedBeforeExpiry && maximumDurationIsAcceptable
}

private fun expiryDate(claims: TokenClaims): Instant = claims.ext.let { Instant.fromEpochSeconds(it) }

private fun validateAudience(claims: TokenClaims): Boolean =
    claims.aud == "chainring"

private fun extractIssuerAddress(claims: TokenClaims): Address? =
    claims.iss.substringAfterLast(":").let { addressString ->
        runCatching {
            Address(addressString)
        }.getOrNull()
    }

private fun validateSignature(message: String, signature: String, issuerAddress: Address): Boolean {
    val messagePrefix = "\u0019Ethereum Signed Message:\n${message.length}"
    val messageHash = ECHelper.sha3(messagePrefix.toByteArray() + message.toByteArray())

    return ECHelper.isValidSignature(
        messageHash = messageHash,
        signature = EvmSignature(signature),
        signerAddress = issuerAddress,
    )
}

private fun missingHeader(name: String): AuthResult.Failure =
    authFailure("$name header is missing")

private fun authFailure(error: String): AuthResult.Failure =
    AuthResult.Failure(unauthorizedResponse(error))

@Serializable
data class TokenHeader(
    val alg: String,
    val typ: String,
)

@Serializable
data class TokenClaims(
    val iat: Long,
    val ext: Long,
    val iss: String,
    val aud: String,
    val tid: String,
)

private val logger = KotlinLogging.logger {}
private const val AUTHORIZATION_SCHEME_PREFIX = "Bearer "
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
