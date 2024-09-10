package xyz.funkybit.apps.api

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.http4k.contract.ContractRoute
import org.http4k.contract.Tag
import org.http4k.contract.div
import org.http4k.contract.meta
import org.http4k.core.Body
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.format.KotlinxSerialization.auto
import org.http4k.lens.Path
import org.http4k.lens.Query
import org.http4k.lens.int
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.div
import org.jetbrains.exposed.sql.SqlExpressionBuilder.minus
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.apps.api.middleware.principal
import xyz.funkybit.apps.api.middleware.signedTokenSecurity
import xyz.funkybit.apps.api.model.Leaderboard
import xyz.funkybit.apps.api.model.LeaderboardEntry
import xyz.funkybit.apps.api.model.RequestProcessingError
import xyz.funkybit.apps.api.model.SetAvatarUrl
import xyz.funkybit.apps.api.model.SetNickname
import xyz.funkybit.apps.api.model.processingError
import xyz.funkybit.core.blockchain.BlockchainClient
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.db.NetworkType
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.TestnetChallengePNLTable
import xyz.funkybit.core.model.db.TestnetChallengePNLType
import xyz.funkybit.core.model.db.TestnetChallengeStatus
import xyz.funkybit.core.model.db.UserEntity
import xyz.funkybit.core.model.db.UserTable
import xyz.funkybit.core.model.db.WalletTable
import xyz.funkybit.core.utils.IconUtils.resolveSymbolUrl
import xyz.funkybit.core.utils.IconUtils.sanitizeImageUrl
import xyz.funkybit.core.utils.TestnetChallengeUtils
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

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
                    val tokenContractAddress = symbol.contractAddress as? EvmAddress
                        ?: throw RuntimeException("Only ERC-20s supported for testnet challenge deposit symbol")

                    val gasAmount = TestnetChallengeUtils.gasDepositAmount
                    val gasSymbol = SymbolEntity.forChainAndContractAddress(symbol.chainId.value, null)
                    val gasAmountInFundamentalUnits =
                        gasAmount.movePointRight(gasSymbol.decimals.toInt()).toBigInteger()

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
                            throw RequestProcessingError("Nickname is already taken")
                        } else {
                            user.nickname = body.name
                        }
                    }
                }
            }
            Response(Status.OK)
        }
    }

    val setAvatarUrl: ContractRoute = run {
        val requestBody = Body.auto<SetAvatarUrl>().toLens()

        "testnet-challenge/avatar" meta {
            operationId = "testnet-challenge-set-avatar"
            summary = "Set Avatar URL"
            tags += listOf(Tag("testnet-challenge"))
            security = signedTokenSecurity
            receiving(requestBody to SetAvatarUrl("URL"))
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
            result.fold(
                ifLeft = { error -> processingError("Unable to process avatar URL: $error") },
                ifRight = { Response(Status.OK) },
            )
        }
    }
    private val leaderboardTypePathParam =
        Path.map(TestnetChallengePNLType::valueOf, TestnetChallengePNLType::name).of("type", "Leaderboard type")

    val getLeaderboard: ContractRoute = run {
        val responseBody = Body.auto<Leaderboard>().toLens()
        "testnet-challenge/leaderboard" / leaderboardTypePathParam meta {
            operationId = "testnet-challenge-get-leaderboard"
            summary = "Get Leaderboard"
            tags += listOf(Tag("testnet-challenge"))
            queries += Query.int().optional("page", "Page number to retrieve, 1-indexed")
            returning(
                Status.OK,
            )
        } bindContract Method.GET to { testnetChallengePNLType ->
            { request ->
                transaction {
                    val count = TestnetChallengePNLTable
                        .selectAll()
                        .where { TestnetChallengePNLTable.type eq testnetChallengePNLType }
                        .count()

                    val rowsPerPage = 20
                    val maxPage = ceil(count.div(rowsPerPage.toDouble())).toLong()
                    // ensure page is sane
                    val page = min(
                        maxPage - 1,
                        max(
                            (request.query("page")?.toLong() ?: 1L) - 1,
                            0L,
                        ),
                    )
                    val pnlRatioExpr = (
                        (TestnetChallengePNLTable.currentBalance - TestnetChallengePNLTable.initialBalance)
                            .div(TestnetChallengePNLTable.initialBalance)
                        )
                        .alias("pnl_percentage")
                    val entries = TestnetChallengePNLTable.innerJoin(
                        UserTable,
                    ).join(
                        WalletTable,
                        JoinType.LEFT,
                        UserTable.guid,
                        WalletTable.userGuid,
                        additionalConstraint = { WalletTable.networkType eq NetworkType.Evm },
                    ).select(
                        TestnetChallengePNLTable.id,
                        TestnetChallengePNLTable.initialBalance,
                        TestnetChallengePNLTable.currentBalance,
                        UserTable.nickName,
                        UserTable.avatarUrl,
                        WalletTable.address,
                        pnlRatioExpr,
                    )
                        .where { TestnetChallengePNLTable.type eq testnetChallengePNLType }
                        .orderBy(pnlRatioExpr, SortOrder.DESC)
                        .limit(rowsPerPage, offset = page * rowsPerPage)
                        .toList()
                    Response(Status.OK).with(
                        responseBody of Leaderboard(
                            type = testnetChallengePNLType,
                            page = page.toInt() + 1,
                            lastPage = maxPage.toInt(),
                            entries.map { entry ->
                                LeaderboardEntry(
                                    entry[UserTable.nickName] ?: entry[WalletTable.address] ?: "",
                                    entry[UserTable.avatarUrl],
                                    entry[TestnetChallengePNLTable.currentBalance].toDouble(),
                                    entry[pnlRatioExpr].toDouble(),
                                )
                            },
                        ),
                    )
                }
            }
        }
    }
}
