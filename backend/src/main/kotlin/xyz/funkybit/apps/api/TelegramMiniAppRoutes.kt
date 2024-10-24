package xyz.funkybit.apps.api

import kotlinx.datetime.Clock
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
import org.http4k.lens.boolean
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.apps.api.middleware.oauthRelaySecurity
import xyz.funkybit.apps.api.middleware.oauthRelayTelegramMiniAppUser
import xyz.funkybit.apps.api.middleware.telegramMiniAppPrincipal
import xyz.funkybit.apps.api.middleware.telegramMiniAppSecurity
import xyz.funkybit.apps.api.middleware.telegramMiniAppUser
import xyz.funkybit.apps.api.model.ApiError
import xyz.funkybit.apps.api.model.ApiErrors
import xyz.funkybit.apps.api.model.CompleteOAuth2AccountLinkingApiRequest
import xyz.funkybit.apps.api.model.ReasonCode
import xyz.funkybit.apps.api.model.StartOAuth2AccountLinkingApiResponse
import xyz.funkybit.apps.api.model.errorResponse
import xyz.funkybit.apps.api.model.invalidInviteCodeError
import xyz.funkybit.apps.api.model.processingError
import xyz.funkybit.apps.api.model.tma.ClaimRewardApiRequest
import xyz.funkybit.apps.api.model.tma.GetUserApiResponse
import xyz.funkybit.apps.api.model.tma.ReactionTimeApiRequest
import xyz.funkybit.apps.api.model.tma.ReactionsTimeApiResponse
import xyz.funkybit.apps.api.model.tma.SingUpApiRequest
import xyz.funkybit.core.model.db.UserAccountLinkingIntentEntity
import xyz.funkybit.core.model.db.UserLinkedAccountEntity
import xyz.funkybit.core.model.db.UserLinkedAccountType
import xyz.funkybit.core.model.telegram.miniapp.OauthRelayToken
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppGoal
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppInviteCode
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppUserEntity
import xyz.funkybit.core.model.telegram.miniapp.sum
import xyz.funkybit.core.utils.DiscordClient
import xyz.funkybit.core.utils.OAuth2
import xyz.funkybit.core.utils.XClient
import java.math.BigDecimal
import java.sql.SQLException

object TelegramMiniAppRoutes {
    val getUser: ContractRoute = run {
        val responseBody = Body.auto<GetUserApiResponse>().toLens()
        val errorResponseBody = Body.auto<ApiErrors>().toLens()
        val firstTimeQuery = Query.boolean().required("firstTime")

        "user" meta {
            operationId = "telegram-mini-app-get-user"
            summary = "Get user"
            security = telegramMiniAppSecurity
            tags += listOf(Tag("tma"))
            queries += firstTimeQuery
            returning(
                Status.OK,
                responseBody to GetUserApiResponse(
                    balance = BigDecimal.ZERO,
                    referralBalance = BigDecimal.ZERO,
                    goals = emptyList(),
                    gameTickets = 3L,
                    checkInStreak = GetUserApiResponse.CheckInStreak(
                        days = 1,
                        reward = BigDecimal.ZERO,
                        gameTickets = 1,
                        grantedAt = Clock.System.now(),
                    ),
                    invites = 0L,
                    inviteCode = TelegramMiniAppInviteCode("12345"),
                    nextMilestoneAt = BigDecimal.ZERO,
                    lastMilestone = null,
                ),
            )
            returning(
                Status.NOT_FOUND,
                errorResponseBody to ApiErrors(
                    listOf(
                        ApiError(ReasonCode.SignupRequired, "Signup required"),
                    ),
                ),
            )
        } bindContract Method.GET to { request ->
            val firstTime = firstTimeQuery(request)
            transaction {
                request.telegramMiniAppPrincipal.maybeUser
                    ?.let {
                        if (firstTime) {
                            exec("""NOTIFY telegram_bot_app_ctl, '${request.telegramMiniAppPrincipal.userData.userId.value}:WelcomeBack'""")
                        }
                        Response(Status.OK).with(
                            responseBody of GetUserApiResponse.fromEntity(it),
                        )
                    }
                    ?: errorResponse(Status.NOT_FOUND, ApiError(ReasonCode.SignupRequired, "Signup required"))
            }
        }
    }

    val signUp: ContractRoute = run {
        val requestBody = Body.auto<SingUpApiRequest>().toLens()
        val responseBody = Body.auto<GetUserApiResponse>().toLens()

        "user" meta {
            operationId = "telegram-mini-app-create-user"
            summary = "Create user"
            security = telegramMiniAppSecurity
            tags += listOf(Tag("tma"))
            receiving(
                requestBody to SingUpApiRequest(
                    inviteCode = null,
                ),
            )
            returning(
                Status.OK,
                responseBody to GetUserApiResponse(
                    balance = BigDecimal.ZERO,
                    referralBalance = BigDecimal.ZERO,
                    goals = emptyList(),
                    gameTickets = 3L,
                    checkInStreak = GetUserApiResponse.CheckInStreak(
                        days = 1,
                        reward = BigDecimal.ZERO,
                        gameTickets = 1,
                        grantedAt = Clock.System.now(),
                    ),
                    inviteCode = TelegramMiniAppInviteCode("12345"),
                    invites = 0L,
                    nextMilestoneAt = BigDecimal.ZERO,
                    lastMilestone = null,
                ),
            )
        } bindContract Method.POST to { request ->
            val apiRequest = requestBody(request)

            try {
                transaction {
                    val user = request.telegramMiniAppPrincipal.maybeUser ?: run {
                        val inviter = apiRequest.inviteCode
                            ?.let {
                                TelegramMiniAppUserEntity.findByInviteCode(apiRequest.inviteCode)
                                    ?.takeIf { it.invites == -1L || it.invites > 0L }
                                    ?: return@transaction invalidInviteCodeError
                            }

                        TelegramMiniAppUserEntity.create(
                            request.telegramMiniAppPrincipal.userData.userId,
                            invitedBy = inviter,
                        ).also {
                            exec("""NOTIFY telegram_bot_app_ctl, '${request.telegramMiniAppPrincipal.userData.userId.value}:FirstTouch'""")
                        }
                    }

                    Response(Status.CREATED).with(
                        responseBody of GetUserApiResponse.fromEntity(user),
                    )
                }
            } catch (e: SQLException) {
                if (e.message?.contains("duplicate key value violates unique constraint") == true) {
                    Response(Status.CREATED).with(
                        responseBody of transaction {
                            GetUserApiResponse.fromEntity(TelegramMiniAppUserEntity.findByTelegramUserId(request.telegramMiniAppPrincipal.userData.userId)!!)
                        },
                    )
                } else {
                    throw (e)
                }
            }
        }
    }

    val claimReward: ContractRoute = run {
        val requestBody = Body.auto<ClaimRewardApiRequest>().toLens()
        val responseBody = Body.auto<GetUserApiResponse>().toLens()

        "rewards" meta {
            operationId = "telegram-mini-app-clam-reward"
            summary = "Claim reward"
            security = telegramMiniAppSecurity
            tags += listOf(Tag("tma"))
            receiving(
                requestBody to ClaimRewardApiRequest(
                    goalId = TelegramMiniAppGoal.Id.GithubSubscription,
                ),
            )
            returning(
                Status.OK,
                responseBody to GetUserApiResponse(
                    balance = BigDecimal.ZERO,
                    referralBalance = BigDecimal.ZERO,
                    goals = emptyList(),
                    gameTickets = 3L,
                    checkInStreak = GetUserApiResponse.CheckInStreak(
                        days = 1,
                        reward = BigDecimal.ZERO,
                        gameTickets = 1,
                        grantedAt = Clock.System.now(),
                    ),
                    inviteCode = TelegramMiniAppInviteCode("12345"),
                    invites = 0L,
                    nextMilestoneAt = BigDecimal.ZERO,
                    lastMilestone = null,
                ),
            )
        } bindContract Method.POST to { request ->
            transaction {
                val user = request.telegramMiniAppUser
                user.grantReward(requestBody(request).goalId)

                Response(Status.OK).with(
                    responseBody of GetUserApiResponse.fromEntity(user),
                )
            }
        }
    }

    val recordReactionTime: ContractRoute = run {
        val requestBody = Body.auto<ReactionTimeApiRequest>().toLens()
        val responseBody = Body.auto<ReactionsTimeApiResponse>().toLens()

        "reaction-time" meta {
            operationId = "record-reaction-time"
            summary = "Record reaction time"
            security = telegramMiniAppSecurity
            tags += listOf(Tag("tma"))
            receiving(
                requestBody to ReactionTimeApiRequest(
                    reactionTimeMs = 250L,
                ),
            )
            returning(
                Status.OK,
                responseBody to ReactionsTimeApiResponse(
                    percentile = 0,
                    reward = BigDecimal.ZERO,
                    balance = BigDecimal.ZERO,
                ),
            )
        } bindContract Method.POST to { request ->
            val apiRequest = requestBody(request)
            if (apiRequest.reactionTimeMs in 1..5000) {
                transaction {
                    val lockedUser = request.telegramMiniAppUser.lockForUpdate()

                    if (lockedUser.gameTickets <= 0) {
                        return@transaction processingError("No game tickets available")
                    }

                    val percentile = lockedUser.useGameTicket(apiRequest.reactionTimeMs)

                    Response(Status.OK).with(
                        responseBody of ReactionsTimeApiResponse(
                            percentile = percentile,
                            reward = percentile.toBigDecimal(),
                            balance = lockedUser.pointsBalances().sum(),
                        ),
                    )
                }
            } else {
                errorResponse(
                    Status.BAD_REQUEST,
                    ApiError(ReasonCode.ProcessingError, "reactionTimeMs must be in range [1, 5000]"),
                )
            }
        }
    }

    val oauthRelayAuthToken: ContractRoute = run {
        val responseBody = Body.auto<OauthRelayToken>().toLens()

        "oauth-relay-auth-token" meta {
            operationId = "oauth-relay-auth-token"
            summary = "Get Oauth Relay Auth Token"
            security = telegramMiniAppSecurity
            tags += listOf(Tag("tma"))
            returning(
                Status.OK,
                responseBody to OauthRelayToken("YXNkZmFzZGZhc2RmYXNkZmFzZGZhc2RmYXNkZmFzZGZhc2RmYXNkZmFzZGZhc2RmYXNkZmFzZGZhc2RmYXNkZg=="),
            )
        } bindContract Method.POST to { request ->
            Response(Status.OK).with(
                responseBody of transaction { request.telegramMiniAppUser.createOauthRelayToken() },
            )
        }
    }

    private val accountLinkTypePathParam =
        Path.map(UserLinkedAccountType::valueOf, UserLinkedAccountType::name).of("code", "Account linking type")

    val startAccountLinking: ContractRoute = run {
        val responseBody = Body.auto<StartOAuth2AccountLinkingApiResponse>().toLens()

        "oauth-relay" / accountLinkTypePathParam meta {
            operationId = "oauth-relay-start-linking"
            summary = "Start OAuth2 account linking"
            tags += listOf(Tag("tma"))
            security = oauthRelaySecurity
            returning(
                Status.OK,
                responseBody to StartOAuth2AccountLinkingApiResponse(authorizeUrl = "https://x.com/i/oauth2/authorize?response_type=code&client_id=M1M5R3BMVy13QmpScXkzTUt5OE46MTpjaQ&redirect_uri=https://www.example.com&scope=tweet.read%20users.read%20follows.read%20offline.access&state=state&code_challenge=challenge&code_challenge_method=plain"),
            )
        } bindContract Method.POST to { accountType ->
            { request ->
                val authorizeUrl = transaction {
                    val user = request.oauthRelayTelegramMiniAppUser.user
                    UserAccountLinkingIntentEntity.create(user, accountType)
                    when (accountType) {
                        UserLinkedAccountType.Discord -> {
                            DiscordClient.getOAuth2AuthorizeUrl(scope = "guilds.join identify")
                        }

                        UserLinkedAccountType.X -> {
                            val intent = UserAccountLinkingIntentEntity.getForUser(user, accountType)
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
        "oauth-relay" / accountLinkTypePathParam meta {
            operationId = "oauth-relay-complete-linking"
            summary = "Complete OAuth2 account Linking"
            tags += listOf(Tag("tma"))
            security = oauthRelaySecurity
            receiving(requestBody to CompleteOAuth2AccountLinkingApiRequest(OAuth2.AuthorizationCode("code")))
            returning(
                Status.OK,
            )
        } bindContract Method.PUT to
            { linkType ->
                { request ->
                    transaction {
                        val user = request.oauthRelayTelegramMiniAppUser.user
                        if (user.hasLinkedAccount(linkType)) {
                            Response(Status.OK)
                        } else {
                            val code = requestBody(request).authorizationCode
                            val (authTokens, accountId) = when (linkType) {
                                UserLinkedAccountType.Discord -> {
                                    val authTokens = DiscordClient.getAuthTokens(code)
                                    val discordUserId = DiscordClient.getUserId(authTokens)
                                    Pair(authTokens, discordUserId)
                                }

                                UserLinkedAccountType.X -> {
                                    val intent = UserAccountLinkingIntentEntity.getForUser(user, linkType)
                                    val authTokens = XClient.getAuthTokens(code, intent.oauth2CodeVerifier!!)
                                    val xUserId = XClient.getUserId(authTokens)
                                    Pair(authTokens, xUserId)
                                }
                            }
                            if (accountId == "") {
                                errorResponse(
                                    Status.BAD_REQUEST,
                                    ApiError(ReasonCode.ProcessingError, "Could not identify $linkType user id"),
                                )
                            } else {
                                UserLinkedAccountEntity.create(user, linkType, accountId, authTokens)
                                val goalId = when (linkType) {
                                    UserLinkedAccountType.Discord -> {
                                        DiscordClient.joinFunkybitDiscord(accountId, authTokens.access)
                                        TelegramMiniAppGoal.Id.DiscordSubscription
                                    }

                                    UserLinkedAccountType.X -> TelegramMiniAppGoal.Id.XSubscription
                                }
                                request.oauthRelayTelegramMiniAppUser.grantVerifiedReward(goalId)
                                Response(Status.OK)
                            }
                        }
                    }
                }
            }
    }
}
