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
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.apps.api.middleware.principal
import xyz.funkybit.apps.api.middleware.signedTokenSecurity
import xyz.funkybit.apps.api.model.ApiError
import xyz.funkybit.apps.api.model.Card
import xyz.funkybit.apps.api.model.CompleteOAuth2AccountLinkingApiRequest
import xyz.funkybit.apps.api.model.Enroll
import xyz.funkybit.apps.api.model.Leaderboard
import xyz.funkybit.apps.api.model.LeaderboardEntry
import xyz.funkybit.apps.api.model.ReasonCode
import xyz.funkybit.apps.api.model.RequestProcessingError
import xyz.funkybit.apps.api.model.SetAvatarUrl
import xyz.funkybit.apps.api.model.SetNickname
import xyz.funkybit.apps.api.model.StartOAuth2AccountLinkingApiResponse
import xyz.funkybit.apps.api.model.errorResponse
import xyz.funkybit.apps.api.model.processingError
import xyz.funkybit.apps.api.model.testnetChallengeDisqualifiedError
import xyz.funkybit.core.blockchain.evm.EvmChainManager
import xyz.funkybit.core.model.db.DepositEntity
import xyz.funkybit.core.model.db.NetworkType
import xyz.funkybit.core.model.db.OrderEntity
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.TestnetChallengePNLEntity
import xyz.funkybit.core.model.db.TestnetChallengePNLType
import xyz.funkybit.core.model.db.TestnetChallengeStatus
import xyz.funkybit.core.model.db.TestnetChallengeUserRewardEntity
import xyz.funkybit.core.model.db.UserAccountLinkingIntentEntity
import xyz.funkybit.core.model.db.UserEntity
import xyz.funkybit.core.model.db.UserLinkedAccountEntity
import xyz.funkybit.core.model.db.UserLinkedAccountType
import xyz.funkybit.core.model.db.WalletEntity
import xyz.funkybit.core.model.db.WithdrawalEntity
import xyz.funkybit.core.utils.DiscordClient
import xyz.funkybit.core.utils.IconUtils.resolveSymbolUrl
import xyz.funkybit.core.utils.IconUtils.sanitizeImageUrl
import xyz.funkybit.core.utils.OAuth2
import xyz.funkybit.core.utils.TestnetChallengeUtils
import xyz.funkybit.core.utils.XClient

class TestnetChallengeRoutes {
    private val logger = KotlinLogging.logger { }

    val enroll: ContractRoute = run {
        val requestBody = Body.auto<Enroll>().toLens()

        "testnet-challenge" meta {
            operationId = "testnet-challenge-enroll"
            summary = "Enroll in Testnet Challenge"
            tags += listOf(Tag("testnet-challenge"))
            security = signedTokenSecurity
            receiving(requestBody to Enroll("WYZFRORSQDI"))
            returning(
                Status.OK,
            )
        } bindContract Method.POST to { request ->
            val body = requestBody(request)

            transaction {
                val user = request.principal.user
                if (TestnetChallengeUtils.enabled && user.testnetChallengeStatus == TestnetChallengeStatus.Unenrolled) {
                    val symbol = TestnetChallengeUtils.depositSymbol()

                    if (DepositEntity.existsForUserAndSymbol(user, symbol)) {
                        user.testnetChallengeStatus = TestnetChallengeStatus.Disqualified
                        return@transaction testnetChallengeDisqualifiedError("Disqualified due to prior deposit")
                    }

                    val airDropperEvmClient = EvmChainManager.getAirdropperEvmClient(symbol.chainId.value)
                    val txHash = airDropperEvmClient.testnetChallengeAirdrop(
                        request.principal.address,
                        tokenSymbol = symbol,
                        tokenAmount = TestnetChallengeUtils.depositAmount,
                        gasSymbol = SymbolEntity.forChainAndContractAddress(symbol.chainId.value, null),
                        gasAmount = TestnetChallengeUtils.gasDepositAmount,
                    )

                    if (txHash != null) {
                        user.testnetChallengeStatus = TestnetChallengeStatus.PendingAirdrop
                        user.testnetAirdropTxHash = txHash.value

                        body.inviteCode?.let {
                            UserEntity.findByInviteCode(body.inviteCode)?.let { invitor ->
                                if (user.guid != invitor.guid) {
                                    user.invitedBy = invitor.guid
                                }
                            }
                        }
                    } else {
                        return@transaction errorResponse(Status.UNPROCESSABLE_ENTITY, ApiError(ReasonCode.UnexpectedError, "Enrollment failed"))
                    }
                }
                Response(Status.OK)
            }
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
                        if (body.name.length > 18) {
                            throw RequestProcessingError("Nickname is too long, 18 character max")
                        }
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
                responseBody to Leaderboard(
                    type = TestnetChallengePNLType.DailyPNL,
                    page = 1,
                    lastPage = 10,
                    entries = listOf(
                        LeaderboardEntry(
                            "label",
                            "iconUrl",
                            10000.0,
                            0.0,
                        ),
                    ),
                ),
            )
        } bindContract Method.GET to { testnetChallengePNLType ->
            { request ->
                val leaderboard = transaction {
                    TestnetChallengePNLEntity.getLeaderboard(testnetChallengePNLType, (request.query("page")?.toLong() ?: 1L) - 1)
                }
                Response(Status.OK).with(responseBody of leaderboard)
            }
        }
    }

    val getCards: ContractRoute = run {
        val responseBody = Body.auto<List<Card>>().toLens()
        "testnet-challenge/cards" meta {
            operationId = "testnet-challenge-get-cards"
            summary = "Get Cards"
            security = signedTokenSecurity
            tags += listOf(Tag("testnet-challenge"))
            returning(
                Status.OK,
                responseBody to listOf(Card.Enrolled),
            )
        } bindContract Method.GET to { request ->
            val cards = mutableListOf<Card>()
            transaction {
                // until they have placed an order, they get the newly enrolled card
                if (!OrderEntity.existsForUser(request.principal.user)) {
                    cards.add(Card.Enrolled)
                }
                // get up to 3 most recent rewards
                TestnetChallengeUserRewardEntity.findRecentForUser(request.principal.user).forEach { reward ->
                    cards.add(
                        Card.RecentPoints(
                            points = reward.amount.toLong(),
                            pointType = reward.type,
                            category = reward.rewardCategory,
                        ),
                    )
                }
                if (!request.principal.user.hasLinkedAccount(UserLinkedAccountType.Discord)) {
                    cards.add(Card.LinkDiscord)
                }
                if (!request.principal.user.hasLinkedAccount(UserLinkedAccountType.X)) {
                    cards.add(Card.LinkX)
                }
                // have they ever connected a bitcoin wallet?
                if (WalletEntity.existsForUserAndNetworkType(request.principal.user, NetworkType.Bitcoin)) {
                    if (!WithdrawalEntity.existsForUserAndNetworkType(request.principal.user, NetworkType.Bitcoin)) {
                        cards.add(Card.BitcoinWithdrawal)
                    }
                } else {
                    cards.add(Card.BitcoinConnect)
                }
                if (!WithdrawalEntity.existsForUserAndNetworkType(request.principal.user, NetworkType.Evm)) {
                    cards.add(Card.EvmWithdrawal)
                }
            }
            Response(Status.OK).with(responseBody of cards.toList())
        }
    }

    private val accountLinkTypePathParam =
        Path.map(UserLinkedAccountType::valueOf, UserLinkedAccountType::name).of("account-type", "Account link type")

    val startAccountLinking: ContractRoute = run {
        val responseBody = Body.auto<StartOAuth2AccountLinkingApiResponse>().toLens()

        "testnet-challenge/account-link" / accountLinkTypePathParam meta {
            operationId = "testnet-challenge-start-account-linking"
            summary = "Start OAuth2 account linking"
            tags += listOf(Tag("testnet-challenge"))
            security = signedTokenSecurity
            returning(
                Status.OK,
                responseBody to StartOAuth2AccountLinkingApiResponse(authorizeUrl = "https://x.com/i/oauth2/authorize?response_type=code&client_id=M1M5R3BMVy13QmpScXkzTUt5OE46MTpjaQ&redirect_uri=https://www.example.com&scope=tweet.read%20users.read%20follows.read%20offline.access&state=state&code_challenge=challenge&code_challenge_method=plain"),
            )
        } bindContract Method.POST to { accountType ->
            { request ->
                val authorizeUrl = transaction {
                    UserAccountLinkingIntentEntity.create(request.principal.user, accountType)
                    when (accountType) {
                        UserLinkedAccountType.Discord -> {
                            DiscordClient.getOAuth2AuthorizeUrl()
                        }
                        UserLinkedAccountType.X -> {
                            val intent = UserAccountLinkingIntentEntity.getForUser(request.principal.user, accountType)
                            XClient.getOAuth2AuthorizeUrl(intent.oauth2CodeVerifier!!.toChallenge())
                        }
                    }
                }
                Response(Status.OK).with(
                    responseBody of StartOAuth2AccountLinkingApiResponse(
                        authorizeUrl = authorizeUrl,
                    ),
                )
            }
        }
    }

    val completeAccountLinking: ContractRoute = run {
        val requestBody = Body.auto<CompleteOAuth2AccountLinkingApiRequest>().toLens()

        "testnet-challenge/account-link" / accountLinkTypePathParam meta {
            operationId = "testnet-challenge-complete-account-linking"
            summary = "Complete OAuth2 account linking"
            tags += listOf(Tag("testnet-challenge"))
            security = signedTokenSecurity
            receiving(requestBody to CompleteOAuth2AccountLinkingApiRequest(OAuth2.AuthorizationCode("code")))
            returning(
                Status.OK,
            )
        } bindContract Method.PUT to { accountType ->
            { request ->
                when (accountType) {
                    UserLinkedAccountType.Discord -> {
                        val authorizationCode = requestBody(request).authorizationCode
                        val authTokens = DiscordClient.getAuthTokens(authorizationCode)
                        val discordUserId = DiscordClient.getUserId(authTokens)
                        transaction {
                            UserLinkedAccountEntity.create(request.principal.user, UserLinkedAccountType.Discord, discordUserId, authTokens)
                        }
                    }
                    UserLinkedAccountType.X -> {
                        val authorizationCode = requestBody(request).authorizationCode
                        transaction {
                            val intent = UserAccountLinkingIntentEntity.getForUser(request.principal.user, accountType)
                            val authTokens = XClient.getAuthTokens(authorizationCode, intent.oauth2CodeVerifier!!)
                            val xUserId = XClient.getUserId(authTokens)
                            UserLinkedAccountEntity.create(request.principal.user, UserLinkedAccountType.X, xUserId, authTokens)
                        }
                    }
                }
                Response(Status.OK)
            }
        }
    }
}
