package xyz.funkybit.apps.api.middleware

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
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
import xyz.funkybit.apps.api.model.ApiError
import xyz.funkybit.apps.api.model.ReasonCode
import xyz.funkybit.apps.api.model.RequestProcessingError
import xyz.funkybit.apps.api.model.errorResponse
import xyz.funkybit.apps.api.requestContexts
import xyz.funkybit.core.blockchain.evm.ECHelper
import xyz.funkybit.core.blockchain.evm.EIP712Helper
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.WalletEntity
import xyz.funkybit.core.model.telegram.TelegramUserId
import xyz.funkybit.core.model.telegram.miniapp.OauthRelayToken
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppUserEntity
import xyz.funkybit.core.model.toEvmSignature
import xyz.funkybit.core.sequencer.SequencerClient
import xyz.funkybit.core.services.LinkedSignerService
import xyz.funkybit.core.utils.bitcoin.BitcoinSignatureVerification
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

val signedTokenSecurity = object : Security {
    override val filter = Filter { next -> wrapWithAuthentication(next) }

    private val sequencerClient = SequencerClient()

    private fun wrapWithAuthentication(httpHandler: HttpHandler): HttpHandler = { request ->
        when (val authResult = authenticate(request)) {
            is AuthResult.Success -> {
                val wallet = transaction {
                    WalletEntity.getOrCreateWithUser(authResult.address).also { (wallet, created) ->
                        if (created) {
                            runBlocking {
                                sequencerClient.authorizeWallet(
                                    authorizedWallet = wallet,
                                    ownershipProof = SequencerClient.SignedMessage(
                                        message = EIP712Helper.structuredDataAsJson(authResult.message),
                                        signature = authResult.signature,
                                    ),
                                    // first wallet of the user
                                    authorizationProof = null,
                                )
                            }
                        }
                    }
                }.first

                val requestWithPrincipal = request.with(
                    principalRequestContextKey of wallet,
                    addressRequestContextKey of authResult.address.canonicalize(),
                    signInMessageRequestContextKey of (authResult.message to authResult.signature),
                )
                httpHandler(requestWithPrincipal)
            }
            is AuthResult.Failure -> {
                logger.info { "Authentication failed with status ${authResult.response.status.code} and error '${authResult.response.bodyString()}'" }
                authResult.response
            }
        }
    }
}

// only validate auth token, do not create wallet and user
val addressOnlySignedTokenSecurity = object : Security {
    override val filter = Filter { next -> wrapWithAuthentication(next) }

    private fun wrapWithAuthentication(httpHandler: HttpHandler): HttpHandler = { request ->
        when (val authResult = authenticate(request)) {
            is AuthResult.Success -> {
                val requestWithPrincipal = request.with(
                    addressRequestContextKey of authResult.address.canonicalize(),
                    signInMessageRequestContextKey of (authResult.message to authResult.signature),
                )
                httpHandler(requestWithPrincipal)
            }
            is AuthResult.Failure -> {
                logger.info { "Authentication failed with status ${authResult.response.status.code} and error '${authResult.response.bodyString()}'" }
                authResult.response
            }
        }
    }
}

val adminSecurity = object : Security {
    override val filter = Filter { next -> wrapWithAdminCheck(next) }

    private fun wrapWithAdminCheck(httpHandler: HttpHandler): HttpHandler = { request ->
        if (request.principal.isAdmin) {
            httpHandler(request)
        } else {
            unauthorizedResponse("Access denied")
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

fun validateAuthToken(token: String): AuthResult {
    val message = token.substringBefore('.')
    val signature = token.substringAfter('.')

    val signInMessage = decodeSignInMessage(message) ?: return authFailure("Invalid token format")

    if (!validateExpiry(signInMessage)) {
        return authFailure("Token is expired or not valid yet")
    }

    if (validateSignature(signInMessage, signature)) {
        return AuthResult.Success(
            address = Address.auto(signInMessage.address),
            expiresAt = endOfValidityInterval(signInMessage),
            message = signInMessage,
            signature = signature,
        )
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
        if (signInMessage.chainId.value == 0UL) {
            val bitcoinAddress = BitcoinAddress.canonicalize(signInMessage.address)
            val bitcoinSignInMessage = signInMessage.message + "\nAddress: ${bitcoinAddress.value}, Timestamp: ${signInMessage.timestamp}"
            BitcoinSignatureVerification.verifyMessage(bitcoinAddress, signature.replace(" ", "+"), bitcoinSignInMessage)
        } else {
            val walletAddress = EvmAddress.canonicalize(signInMessage.address)
            // TODO - support bitcoin linked signers
            val linkedSignerAddress = LinkedSignerService.getLinkedSigner(walletAddress, signInMessage.chainId) as? EvmAddress
            ECHelper.isValidSignature(
                messageHash = EIP712Helper.computeHash(signInMessage),
                signature = signature.toEvmSignature(),
                signerAddress = walletAddress,
                linkedSignerAddress = linkedSignerAddress,
            )
        }
    }.onFailure { e ->
        logger.debug(e) { "Error during signature validation" }
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
    data class Success(val address: Address, val expiresAt: Instant, val message: SignInMessage, val signature: String) : AuthResult()
    data class Failure(val response: Response) : AuthResult()
}

private val principalRequestContextKey =
    RequestContextKey.optional<WalletEntity>(requestContexts)
val Request.principal: WalletEntity
    get() = principalRequestContextKey(this)
        ?: throw RequestProcessingError(Status.UNAUTHORIZED, ApiError(ReasonCode.AuthenticationError, "Unauthorized"))

private val addressRequestContextKey =
    RequestContextKey.optional<Address>(requestContexts)
val Request.address: Address
    get() = addressRequestContextKey(this)
        ?: throw RequestProcessingError(Status.UNAUTHORIZED, ApiError(ReasonCode.AuthenticationError, "Unauthorized"))

private val signInMessageRequestContextKey =
    RequestContextKey.optional<Pair<SignInMessage, String>>(requestContexts)
val Request.signInMessage: Pair<SignInMessage, String>
    get() = signInMessageRequestContextKey(this)
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

data class OauthRelayPrincipal(
    val telegramMiniAppUser: TelegramMiniAppUserEntity?,
)

private val oauthRelayPrincipalRequestContextKey =
    RequestContextKey.optional<OauthRelayPrincipal>(requestContexts)

val Request.oauthRelayPrincipal: OauthRelayPrincipal
    get() = oauthRelayPrincipalRequestContextKey(this)
        ?: throw RequestProcessingError(Status.UNAUTHORIZED, ApiError(ReasonCode.AuthenticationError, "Unauthorized"))

val oauthRelaySecurity = object : Security {
    override val filter = Filter { next -> wrapWithAuthentication(next) }

    private fun wrapWithAuthentication(httpHandler: HttpHandler): HttpHandler = { request ->
        authenticate(request).fold(
            ifLeft = { error ->
                logger.info { "Authentication failed with error $error" }
                errorResponse(Status.UNAUTHORIZED, error)
            },
            ifRight = { user ->
                val requestWithPrincipal = request.with(
                    oauthRelayPrincipalRequestContextKey of OauthRelayPrincipal(user),
                )
                httpHandler(requestWithPrincipal)
            },
        )
    }

    private fun authenticate(request: Request): Either<ApiError, TelegramMiniAppUserEntity> {
        val authHeader = request.header("Authorization")?.trim()
            ?: return ApiError(ReasonCode.AuthenticationError, "Authorization header is missing").left()

        if (!authHeader.startsWith(AUTHORIZATION_SCHEME_PREFIX, ignoreCase = true)) {
            return ApiError(ReasonCode.AuthenticationError, "Invalid authentication scheme").left()
        }

        val authToken = authHeader.removePrefix(AUTHORIZATION_SCHEME_PREFIX)
        return transaction {
            TelegramMiniAppUserEntity.findByOauthRelayAuthToken(OauthRelayToken(authToken))
        }?.right() ?: ApiError(ReasonCode.AuthenticationError, "Token not found or expired").left()
    }
}

val Request.oauthRelayTelegramMiniAppUser: TelegramMiniAppUserEntity
    get() =
        oauthRelayPrincipal.telegramMiniAppUser
            ?: throw RequestProcessingError(Status.UNAUTHORIZED, ApiError(ReasonCode.AuthenticationError, "Unauthorized"))
