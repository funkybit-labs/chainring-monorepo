package co.chainring.apps.api

import co.chainring.apps.api.middleware.telegramMiniAppPrincipal
import co.chainring.apps.api.middleware.telegramMiniAppSecurity
import co.chainring.apps.api.middleware.telegramMiniAppUser
import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.ApiErrors
import co.chainring.apps.api.model.ReasonCode
import co.chainring.apps.api.model.errorResponse
import co.chainring.apps.api.model.processingError
import co.chainring.apps.api.model.tma.ClaimRewardApiRequest
import co.chainring.apps.api.model.tma.GetUserApiResponse
import co.chainring.apps.api.model.tma.ReactionTimeApiRequest
import co.chainring.apps.api.model.tma.ReactionsTimeApiResponse
import co.chainring.core.model.telegram.miniapp.TelegramMiniAppGoal
import co.chainring.core.model.telegram.miniapp.TelegramMiniAppUserEntity
import kotlinx.datetime.Clock
import org.http4k.contract.ContractRoute
import org.http4k.contract.Tag
import org.http4k.contract.meta
import org.http4k.core.Body
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.format.KotlinxSerialization.auto
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal

object TelegramMiniAppRoutes {
    val getUser: ContractRoute = run {
        val responseBody = Body.auto<GetUserApiResponse>().toLens()
        val errorResponseBody = Body.auto<ApiErrors>().toLens()

        "user" meta {
            operationId = "telegram-mini-app-get-user"
            summary = "Get user"
            security = telegramMiniAppSecurity
            tags += listOf(Tag("tma"))
            returning(
                Status.OK,
                responseBody to GetUserApiResponse(
                    balance = BigDecimal.ZERO,
                    goals = emptyList(),
                    gameTickets = 3L,
                    checkInStreak = GetUserApiResponse.CheckInStreak(
                        days = 1,
                        reward = BigDecimal.ZERO,
                        gameTickets = 1,
                        grantedAt = Clock.System.now(),
                    ),
                    invites = 0L,
                    nextMilestoneIn = BigDecimal.ZERO,
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
            transaction {
                request.telegramMiniAppPrincipal.maybeUser
                    ?.let {
                        Response(Status.OK).with(
                            responseBody of GetUserApiResponse.fromEntity(it),
                        )
                    }
                    ?: errorResponse(Status.NOT_FOUND, ApiError(ReasonCode.SignupRequired, "Signup required"))
            }
        }
    }

    val signUp: ContractRoute = run {
        val responseBody = Body.auto<GetUserApiResponse>().toLens()

        "user" meta {
            operationId = "telegram-mini-app-create-user"
            summary = "Create user"
            security = telegramMiniAppSecurity
            tags += listOf(Tag("tma"))
            returning(
                Status.OK,
                responseBody to GetUserApiResponse(
                    balance = BigDecimal.ZERO,
                    goals = emptyList(),
                    gameTickets = 3L,
                    checkInStreak = GetUserApiResponse.CheckInStreak(
                        days = 1,
                        reward = BigDecimal.ZERO,
                        gameTickets = 1,
                        grantedAt = Clock.System.now(),
                    ),
                    invites = 0L,
                    nextMilestoneIn = BigDecimal.ZERO,
                    lastMilestone = null,
                ),
            )
        } bindContract Method.POST to { request ->
            transaction {
                val user = request.telegramMiniAppPrincipal.maybeUser
                    ?: TelegramMiniAppUserEntity.create(request.telegramMiniAppPrincipal.userData.userId)

                Response(Status.CREATED).with(
                    responseBody of GetUserApiResponse.fromEntity(user),
                )
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
                    goals = emptyList(),
                    gameTickets = 3L,
                    checkInStreak = GetUserApiResponse.CheckInStreak(
                        days = 1,
                        reward = BigDecimal.ZERO,
                        gameTickets = 1,
                        grantedAt = Clock.System.now(),
                    ),
                    invites = 0L,
                    nextMilestoneIn = BigDecimal.ZERO,
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
            if (apiRequest.reactionTimeMs > 0) {
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
                            balance = lockedUser.pointsBalance(),
                        ),
                    )
                }
            } else {
                errorResponse(Status.BAD_REQUEST, ApiError(ReasonCode.ProcessingError, "reactionTimeMs must be greater than 0"))
            }
        }
    }
}
