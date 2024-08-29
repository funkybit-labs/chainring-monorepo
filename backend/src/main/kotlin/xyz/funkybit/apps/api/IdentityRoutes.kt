package xyz.funkybit.apps.api

import arrow.core.Either
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.http4k.contract.ContractRoute
import org.http4k.contract.Tag
import org.http4k.contract.meta
import org.http4k.core.Body
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.format.KotlinxSerialization.auto
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.apps.api.middleware.principal
import xyz.funkybit.apps.api.middleware.signedTokenSecurity
import xyz.funkybit.apps.api.model.ApiError
import xyz.funkybit.apps.api.model.BitcoinLinkAddressProof
import xyz.funkybit.apps.api.model.EvmLinkAddressProof
import xyz.funkybit.apps.api.model.LinkIdentityApiRequest
import xyz.funkybit.apps.api.model.ReasonCode
import xyz.funkybit.apps.api.model.errorResponse
import xyz.funkybit.core.evm.ECHelper
import xyz.funkybit.core.evm.EIP712Helper
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.BitcoinSignature
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.EvmSignature
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.WalletEntity
import xyz.funkybit.core.utils.bitcoin.BitcoinSignatureVerification
import java.math.BigInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

object IdentityRoutes {
    private val logger = KotlinLogging.logger {}

    val linkIdentities: ContractRoute = run {
        val requestBody = Body.auto<LinkIdentityApiRequest>().toLens()

        "identities/link" meta {
            operationId = "link-identities"
            summary = "Link identities"
            security = signedTokenSecurity
            tags += listOf(Tag("link"))
            receiving(
                requestBody to LinkIdentityApiRequest(
                    bitcoinLinkAddressProof = BitcoinLinkAddressProof(
                        message = "[funkybit] Please sign this message to link your wallets. This action will not cost any gas fees.",
                        address = BitcoinAddress.canonicalize("bcrt1qdca3sam9mldju3ssryrrcmjvd8pgnw30ccaggx"),
                        linkAddress = EvmAddress.zero,
                        timestamp = "2024-08-21T14:14:13.095Z",
                        signature = BitcoinSignature.emptySignature(),
                    ),
                    evmLinkAddressProof = EvmLinkAddressProof(
                        message = "[funkybit] Please sign this message to link your wallets. This action will not cost any gas fees.",
                        address = EvmAddress.zero,
                        linkAddress = BitcoinAddress.canonicalize("bcrt1qdca3sam9mldju3ssryrrcmjvd8pgnw30ccaggx"),
                        chainId = ChainId(BigInteger.ZERO),
                        timestamp = "2024-08-21T14:14:13.095Z",
                        signature = EvmSignature.emptySignature(),
                    ),
                ),
            )
            returning(Status.NO_CONTENT)
        } bindContract Method.POST to { request ->
            val wallet = request.principal
            val apiRequest = requestBody(request)

            validateLinkRequest(wallet, apiRequest).fold(
                ifLeft = { apiError ->
                    errorResponse(
                        status = Status.UNPROCESSABLE_ENTITY,
                        apiError,
                    )
                },
                ifRight = {
                    transaction {
                        val linkAddress = when (wallet.address) {
                            is BitcoinAddress -> apiRequest.bitcoinLinkAddressProof.linkAddress
                            is EvmAddress -> apiRequest.evmLinkAddressProof.linkAddress
                        }

                        when (val linkWallet = WalletEntity.findByAddress(linkAddress)) {
                            null -> {
                                WalletEntity.createForUser(wallet.user, linkAddress)
                                Response(Status.NO_CONTENT)
                            }

                            else -> {
                                if (linkWallet.userGuid == wallet.userGuid) {
                                    Response(Status.NO_CONTENT) // no-op, already linked
                                } else {
                                    errorResponse(
                                        status = Status.UNPROCESSABLE_ENTITY,
                                        ApiError(ReasonCode.LinkIdentityError, "Link address is already in use"),
                                    )
                                }
                            }
                        }
                    }
                },
            )
        }
    }

    private fun validateLinkRequest(wallet: WalletEntity, apiRequest: LinkIdentityApiRequest): Either<ApiError, Unit> {
        // check that wallets link each other
        if (
            apiRequest.evmLinkAddressProof.address != apiRequest.bitcoinLinkAddressProof.linkAddress ||
            apiRequest.bitcoinLinkAddressProof.address != apiRequest.evmLinkAddressProof.linkAddress
        ) {
            return Either.Left(
                ApiError(ReasonCode.LinkIdentityError, "Invalid identity links"),
            )
        }

        // check both signatures are valid
        if (!evmSignatureValid(apiRequest.evmLinkAddressProof) || !bitcoinSignatureValid(apiRequest.bitcoinLinkAddressProof)) {
            return Either.Left(
                ApiError(ReasonCode.LinkIdentityError, "Signature can't be verified"),
            )
        }

        // check timestamps are recent
        val signatureToleranceBeforeNow = 10.seconds
        val maxSignatureAge = 5.minutes
        val currentTime = Clock.System.now()

        val bitcoinSignatureTimestamp = Instant.parse(apiRequest.bitcoinLinkAddressProof.timestamp)
        val bitcoinSignatureNotValidYet = bitcoinSignatureTimestamp - signatureToleranceBeforeNow > currentTime
        val bitcoinSignatureExpired = bitcoinSignatureTimestamp + maxSignatureAge < currentTime

        val evmSignatureTimestamp = Instant.parse(apiRequest.evmLinkAddressProof.timestamp)
        val evmSignatureNotValidYet = evmSignatureTimestamp - signatureToleranceBeforeNow > currentTime
        val evmSignatureExpired = evmSignatureTimestamp + maxSignatureAge < currentTime

        if (bitcoinSignatureNotValidYet || bitcoinSignatureExpired || evmSignatureNotValidYet || evmSignatureExpired) {
            return Either.Left(
                ApiError(ReasonCode.LinkIdentityError, "Link proof has expired or not valid yet"),
            )
        }

        return Either.Right(Unit)
    }

    private fun bitcoinSignatureValid(bitcoinLinkAddressProof: BitcoinLinkAddressProof): Boolean {
        val bitcoinAddress = BitcoinAddress.canonicalize(bitcoinLinkAddressProof.address.toString())
        val linkAddress = EvmAddress.canonicalize(bitcoinLinkAddressProof.linkAddress.toString())

        val bitcoinLinkAddressMessage = bitcoinLinkAddressProof.message +
            "\nAddress: ${bitcoinAddress.value}, LinkAddress: ${linkAddress.value}, Timestamp: ${bitcoinLinkAddressProof.timestamp}"

        return BitcoinSignatureVerification.verifyMessage(bitcoinAddress, bitcoinLinkAddressProof.signature.value.replace(" ", "+"), bitcoinLinkAddressMessage)
    }

    private fun evmSignatureValid(evmLinkAddressProof: EvmLinkAddressProof): Boolean {
        val walletAddress = EvmAddress.canonicalize(evmLinkAddressProof.address.toString())

        return ECHelper.isValidSignature(
            messageHash = EIP712Helper.computeHash(evmLinkAddressProof),
            signature = evmLinkAddressProof.signature,
            signerAddress = walletAddress,
        )
    }
}
