import z from 'zod'
import { Zodios } from '@zodios/core'
import { pluginToken } from '@zodios/plugins'
import Decimal from 'decimal.js'

export const apiBaseUrl = import.meta.env.ENV_API_URL + '/tma'

const decimal = () =>
  z
    .instanceof(Decimal)
    .or(z.string())
    .or(z.number())
    .transform((value, ctx) => {
      try {
        return new Decimal(value)
      } catch (error) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: `${value} can't be parsed into Decimal`
        })
        return z.NEVER
      }
    })

const ApiErrorSchema = z.object({
  reason: z.string(),
  displayMessage: z.string()
})
export type ApiError = z.infer<typeof ApiErrorSchema>

export const ApiErrorsSchema = z.object({
  errors: z.array(ApiErrorSchema)
})
export type ApiErrors = z.infer<typeof ApiErrorsSchema>

const GoalIdSchema = z.enum([
  'GithubSubscription',
  'DiscordSubscription',
  'LinkedinSubscription',
  'XSubscription'
])
export type GoalId = z.infer<typeof GoalIdSchema>

const UserGoalSchema = z.object({
  id: GoalIdSchema,
  reward: decimal(),
  achieved: z.boolean()
})
export type UserGoal = z.infer<typeof UserGoalSchema>

const CheckInStreakSchema = z.object({
  days: z.number(),
  reward: decimal(),
  gameTickets: z.number(),
  grantedAt: z.coerce.date()
})
export type CheckInStreak = z.infer<typeof CheckInStreakSchema>

const LastMilestoneSchema = z.object({
  invites: z.number(),
  grantedAt: z.coerce.date(),
  points: decimal()
})
export type LastMilestone = z.infer<typeof LastMilestoneSchema>

const UserSchema = z.object({
  balance: decimal(),
  referralBalance: decimal(),
  goals: z.array(UserGoalSchema),
  gameTickets: z.number(),
  checkInStreak: CheckInStreakSchema,
  invites: z.number(),
  inviteCode: z.string(),
  nextMilestoneAt: decimal().optional(),
  lastMilestone: LastMilestoneSchema.nullable()
})
export type User = z.infer<typeof UserSchema>

const SignUpApiRequest = z.object({
  inviteCode: z.string().nullable()
})

const ClaimRewardApiRequest = z.object({
  goalId: GoalIdSchema
})

const ReactionTimeApiRequest = z.object({
  reactionTimeMs: z.number()
})

const ReactionTimeApiResponse = z.object({
  percentile: z.number(),
  reward: decimal(),
  balance: decimal()
})

export const apiClient = new Zodios(apiBaseUrl, [
  {
    method: 'get',
    path: '/v1/user',
    alias: 'getUser',
    response: UserSchema,
    parameters: [
      {
        name: 'firstTime',
        type: 'Query',
        schema: z.boolean()
      }
    ],
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'post',
    path: '/v1/user',
    alias: 'signUp',
    parameters: [
      {
        name: 'payload',
        type: 'Body',
        schema: SignUpApiRequest
      }
    ],
    response: UserSchema,
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'post',
    path: '/v1/rewards',
    alias: 'claimReward',
    parameters: [
      {
        name: 'payload',
        type: 'Body',
        schema: ClaimRewardApiRequest
      }
    ],
    response: UserSchema,
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'post',
    path: '/v1/reaction-time',
    alias: 'recordReactionTime',
    parameters: [
      {
        name: 'payload',
        type: 'Body',
        schema: ReactionTimeApiRequest
      }
    ],
    response: ReactionTimeApiResponse,
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  }
])

apiClient.use(
  pluginToken({
    getToken: async () => {
      // @ts-expect-error silence compiler error since this is a global object Typescript does not know about
      return window.Telegram.WebApp.initData
    }
  })
)

export const userQueryKey = ['user']
