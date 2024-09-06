package xyz.funkybit.apps.api

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
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
import xyz.funkybit.apps.api.model.SetAvatarUrl
import xyz.funkybit.apps.api.model.SetNickname
import xyz.funkybit.apps.api.model.processingError
import xyz.funkybit.core.blockchain.BlockchainClient
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.TestnetChallengeStatus
import xyz.funkybit.core.model.db.UserEntity
import xyz.funkybit.core.utils.IconUtils.resolveSymbolUrl
import xyz.funkybit.core.utils.IconUtils.sanitizeImageUrl
import xyz.funkybit.core.utils.TestnetChallengeUtils

class TestnetChallengeRoutes(blockchainClients: Collection<BlockchainClient>) {
    private val logger = KotlinLogging.logger { }

    val enroll: ContractRoute = run {
        "testnet-challenge" meta {
            operationId = "testnet-challenge-enroll"
            summary = "Enroll in Testnet Challenge"
            tags += listOf(Tag("testnet-challenge"))
            security = signedTokenSecurity
            returning(
                Status.OK,
            )
        } bindContract Method.POST to { request ->
            transaction {
                val user = request.principal.user
                if (TestnetChallengeUtils.enabled && user.testnetChallengeStatus == TestnetChallengeStatus.Unenrolled) {
                    val symbol = TestnetChallengeUtils.depositSymbol()
                    val blockchainClient = blockchainClients.first { it.chainId == symbol.chainId.value }

                    val amount = TestnetChallengeUtils.depositAmount

                    val address = request.principal.address
                    logger.debug { "Sending $amount ${symbol.name} to $address" }

                    val amountInFundamentalUnits = amount.movePointRight(symbol.decimals.toInt()).toBigInteger()
                    val tokenContractAddress = symbol.contractAddress as? EvmAddress ?: throw RuntimeException("Only ERC-20s supported for testnet challenge deposit symbol")

                    val gasAmount = TestnetChallengeUtils.gasDepositAmount
                    val gasSymbol = SymbolEntity.forChainAndContractAddress(symbol.chainId.value, null)
                    val gasAmountInFundamentalUnits = gasAmount.movePointRight(gasSymbol.decimals.toInt()).toBigInteger()

                    blockchainClient.sendNativeDepositTx(
                        address,
                        gasAmountInFundamentalUnits,
                    )

                    val txHash = blockchainClient.sendMintERC20Tx(
                        tokenContractAddress,
                        address as EvmAddress,
                        amountInFundamentalUnits,
                    )
                    user.testnetChallengeStatus = TestnetChallengeStatus.PendingAirdrop
                    user.testnetAirdropTxHash = txHash.value
                }
            }
            Response(Status.OK)
        }
    }

    val setNickname: ContractRoute = run {
        val requestBody = Body.auto<SetNickname>().toLens()

        "testnet-challenge/nickname" meta {
            operationId = "testnet-challenge-set-nickname"
            summary = "Set Nickname"
            tags += listOf(Tag("testnet-challenge"))
            security = signedTokenSecurity
            receiving(requestBody to SetNickname("Name"))
            returning(
                Status.OK,
            )
        } bindContract Method.POST to { request ->
            val body = requestBody(request)
            transaction {
                val user = request.principal.user
                if (TestnetChallengeUtils.enabled && user.testnetChallengeStatus == TestnetChallengeStatus.Enrolled) {
                    if (user.nickname != body.name) {
                        if (UserEntity.findByNickname(body.name) != null) {
                            processingError("Nickname is already taken")
                        } else {
                            user.nickname = body.name
                            null
                        }
                    } else {
                        null
                    }
                } else {
                    null
                }
            } ?: Response(Status.OK)
        }
    }

    val setAvatarUrl: ContractRoute = run {
        val requestBody = Body.auto<SetAvatarUrl>().toLens()

        "testnet-challenge/avatar" meta {
            operationId = "testnet-challenge-set-avatar"
            summary = "Set Avatar URL"
            tags += listOf(Tag("testnet-challenge"))
            security = signedTokenSecurity
            receiving(requestBody to SetAvatarUrl("Name"))
            returning(
                Status.OK,
            )
        } bindContract Method.POST to { request ->
            val body = requestBody(request)
            val result = sanitizeImageUrl(body.url).map { sanitized ->
                val url = runBlocking { resolveSymbolUrl(sanitized) }
                transaction {
                    val user = request.principal.user
                    if (TestnetChallengeUtils.enabled && user.testnetChallengeStatus == TestnetChallengeStatus.Enrolled) {
                        user.avatarUrl = url
                    }
                }
            }
            if (result.isLeft()) {
                processingError("Unable to processes avatar URL: ${result.leftOrNull()?.name}")
            } else {
                Response(Status.OK)
            }
        }
    }
}
