package xyz.funkybit.apps.api

import arrow.core.Either
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.http4k.contract.ContractRoute
import org.http4k.contract.Tag
import org.http4k.contract.meta
import org.http4k.core.Body
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.format.KotlinxSerialization.auto
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.apps.api.middleware.address
import xyz.funkybit.apps.api.middleware.addressOnlySignedTokenSecurity
import xyz.funkybit.apps.api.model.ApiError
import xyz.funkybit.apps.api.model.AuthorizeWalletApiRequest
import xyz.funkybit.apps.api.model.ReasonCode
import xyz.funkybit.apps.api.model.errorResponse
import xyz.funkybit.core.evm.ECHelper
import xyz.funkybit.core.evm.EIP712Helper
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.EvmSignature
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.WalletAuthorizationEntity
import xyz.funkybit.core.model.db.WalletEntity
import xyz.funkybit.core.utils.bitcoin.BitcoinSignatureVerification
import java.math.BigInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

const val BASE_AUTHORIZE_MESSAGE = "[funkybit] Please sign this message to authorize %s wallet %s. This action will not cost any gas fees."

@Serializable
data class AuthorizeWalletAddressMessage(
    val message: String,
    val authorizedAddress: String,
    val address: String,
    val chainId: ChainId,
    val timestamp: String,
)

object WalletRoutes {
    private val logger = KotlinLogging.logger {}

    val authorizeWallet: ContractRoute = run {
        val requestBody = Body.auto<AuthorizeWalletApiRequest>().toLens()

        "wallets/authorization" meta {
            operationId = "wallet-authorization"
            summary = "Register wallet authorization"
            security = addressOnlySignedTokenSecurity
            tags += listOf(Tag("authorize"))
            receiving(
                requestBody to AuthorizeWalletApiRequest(
                    authorizedAddress = EvmAddress.zero,
                    address = BitcoinAddress.canonicalize("bcrt1qdca3sam9mldju3ssryrrcmjvd8pgnw30ccaggx"),
                    chainId = ChainId(BigInteger.ZERO),
                    timestamp = "2024-08-21T14:14:13.095Z",
                    signature = "",
                ),
            )
            returning(Status.NO_CONTENT)
        } bindContract Method.POST to { request ->
            val principalAddress = request.address
            val apiRequest = requestBody(request)

            validateWalletAuthorizationRequest(principalAddress, apiRequest).fold(
                ifLeft = { apiError ->
                    errorResponse(
                        status = Status.UNPROCESSABLE_ENTITY,
                        apiError,
                    )
                },
                ifRight = {
                    transaction {
                        val authorizingWallet = WalletEntity.findByAddress(apiRequest.address)
                            ?: return@transaction errorResponse(
                                status = Status.UNPROCESSABLE_ENTITY,
                                ApiError(ReasonCode.AuthorizeWallerError, "Invalid authorizing wallet address"),
                            )

                        when (val existingAuthorizedWallet = WalletEntity.findByAddress(principalAddress)) {
                            null -> {
                                // create a new wallet and link it to the user
                                val authorizedWallet = WalletEntity.createForUser(authorizingWallet.user, apiRequest.authorizedAddress)

                                // also store authorization proof for the future reference
                                val authorizationMessage = when (apiRequest.authorizedAddress) {
                                    is EvmAddress -> Json.encodeToString(evmWalletAuthorizationMessage(apiRequest))
                                    is BitcoinAddress -> bitcoinWalletAuthorizationMessage(apiRequest)
                                }
                                WalletAuthorizationEntity.store(wallet = authorizedWallet, message = authorizationMessage, signature = apiRequest.signature, createdBy = principalAddress)

                                Response(Status.NO_CONTENT)
                            }

                            else -> {
                                if (existingAuthorizedWallet.userGuid == authorizingWallet.userGuid) {
                                    Response(Status.NO_CONTENT) // no-op, already linked
                                } else {
                                    errorResponse(
                                        status = Status.UNPROCESSABLE_ENTITY,
                                        ApiError(ReasonCode.AuthorizeWallerError, "Authorized wallet address is already in use"),
                                    )
                                }
                            }
                        }
                    }
                },
            )
        }
    }

    private fun validateWalletAuthorizationRequest(principalAddress: Address, apiRequest: AuthorizeWalletApiRequest): Either<ApiError, Unit> {
        // check principal should provide authorization proof for its address
        if (principalAddress != apiRequest.authorizedAddress) {
            return Either.Left(
                ApiError(ReasonCode.AuthorizeWallerError, "Invalid authorization"),
            )
        }

        // check signature
        if (!isSignatureValid(apiRequest)) {
            return Either.Left(
                ApiError(ReasonCode.AuthorizeWallerError, "Authorization signature can't be verified"),
            )
        }

        // check timestamp is recent
        val signatureToleranceBeforeNow = 10.seconds
        val maxSignatureAge = 5.minutes
        val currentTime = Clock.System.now()

        val signatureTimestamp = Instant.parse(apiRequest.timestamp)
        val signatureNotValidYet = signatureTimestamp - signatureToleranceBeforeNow > currentTime
        val signatureExpired = signatureTimestamp + maxSignatureAge < currentTime

        if (signatureNotValidYet || signatureExpired) {
            return Either.Left(
                ApiError(ReasonCode.AuthorizeWallerError, "Authorization has expired or not valid yet"),
            )
        }

        return Either.Right(Unit)
    }

    private fun isSignatureValid(authorizationRequest: AuthorizeWalletApiRequest) =
        when (val address = authorizationRequest.address) {
            is EvmAddress -> {
                val authorizationMessage = evmWalletAuthorizationMessage(authorizationRequest)
                ECHelper.isValidSignature(
                    messageHash = EIP712Helper.computeHash(authorizationMessage),
                    signature = EvmSignature(authorizationRequest.signature),
                    signerAddress = address,
                )
            }

            is BitcoinAddress -> {
                val authorizationMessage = bitcoinWalletAuthorizationMessage(authorizationRequest)
                BitcoinSignatureVerification.verifyMessage(
                    address = address,
                    signature = authorizationRequest.signature.replace(" ", "+"),
                    message = authorizationMessage,
                )
            }
        }

    private fun bitcoinWalletAuthorizationMessage(authorizationRequest: AuthorizeWalletApiRequest): String {
        val bitcoinAddress = BitcoinAddress.canonicalize(authorizationRequest.address.toString())
        return baseMessage(authorizationRequest.authorizedAddress) + "\nAddress: ${bitcoinAddress.value}, Timestamp: ${authorizationRequest.timestamp}"
    }

    private fun evmWalletAuthorizationMessage(authorizationRequest: AuthorizeWalletApiRequest) =
        AuthorizeWalletAddressMessage(
            message = baseMessage(authorizationRequest.authorizedAddress),
            address = authorizationRequest.address.toString(),
            authorizedAddress = authorizationRequest.authorizedAddress.toString(),
            chainId = authorizationRequest.chainId,
            timestamp = authorizationRequest.timestamp,
        )

    private fun baseMessage(authorizedAddress: Address): String {
        val walletFamily = when (authorizedAddress) {
            is EvmAddress -> "EVM"
            is BitcoinAddress -> "Bitcoin"
        }

        return String.format(BASE_AUTHORIZE_MESSAGE, walletFamily, authorizedAddress.toString())
    }
}
