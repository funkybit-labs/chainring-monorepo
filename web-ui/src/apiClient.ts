import z from 'zod'
import { Zodios } from '@zodios/core'
import { pluginToken } from '@zodios/plugins'
import { loadAuthToken } from 'contexts/auth'
import { getOAuthRelayAuthToken } from 'contexts/oauthRelayAuth'
import Decimal from 'decimal.js'
import { useEffect, useState } from 'react'
import { FEE_RATE_PIPS_MAX_VALUE } from 'utils'
import { AxiosInstance } from 'axios'

export const apiBaseUrl = import.meta.env.ENV_API_URL

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
          message: `${value} can't be parsed into Decimal: ${error}`
        })
        return z.NEVER
      }
    })

const feeRatePips = () =>
  decimal().transform((decimalFee) => {
    return BigInt(decimalFee.mul(FEE_RATE_PIPS_MAX_VALUE).toNumber())
  })

export const EvmAddressSchema = z.custom<`0x${string}`>((val: unknown) =>
  /^0x/.test(val as string)
)
export type EvmAddressType = z.infer<typeof EvmAddressSchema>
export const evmAddress = (address: string): EvmAddressType => {
  // will throw if the address is invalid
  return EvmAddressSchema.parse(address)
}
export const AddressSchema = z.string().min(1)
export type AddressType = z.infer<typeof AddressSchema>

const ContractSchema = z.object({
  name: z.string(),
  address: AddressSchema
})

export type Contract = z.infer<typeof ContractSchema>

const SymbolSchema = z.object({
  name: z.string(),
  description: z.string(),
  contractAddress: AddressSchema.nullable(),
  decimals: z.number(),
  faucetSupported: z.boolean(),
  iconUrl: z.string(),
  withdrawalFee: z.coerce.bigint()
})

export type SymbolType = z.infer<typeof SymbolSchema>

const AdminSymbolSchema = z.object({
  chainId: z.number(),
  name: z.string(),
  description: z.string(),
  contractAddress: AddressSchema.nullable(),
  decimals: z.number(),
  iconUrl: z.string(),
  withdrawalFee: z.coerce.bigint(),
  addToWallets: z.boolean()
})

export type AdminSymbol = z.infer<typeof AdminSymbolSchema>

const AdminMarketSchema = z.object({
  id: z.string(),
  tickSize: decimal(),
  lastPrice: decimal(),
  minFee: z.coerce.bigint()
})

export type AdminMarket = z.infer<typeof AdminMarketSchema>

const NetworkTypeSchema = z.enum(['Evm', 'Bitcoin'])
export type NetworkType = z.infer<typeof NetworkTypeSchema>

const ChainSchema = z.object({
  id: z.number(),
  name: z.string(),
  contracts: z.array(ContractSchema),
  symbols: z.array(SymbolSchema),
  jsonRpcUrl: z.string(),
  blockExplorerNetName: z.string(),
  blockExplorerUrl: z.string(),
  networkType: NetworkTypeSchema
})
export type Chain = z.infer<typeof ChainSchema>

const MarketSchema = z.object({
  id: z.string(),
  baseSymbol: z.string(),
  quoteSymbol: z.string(),
  tickSize: decimal(),
  lastPrice: decimal(),
  minFee: z.coerce.bigint()
})
export type Market = z.infer<typeof MarketSchema>

const FeeRatesSchema = z.object({
  maker: feeRatePips(),
  taker: feeRatePips()
})
export type FeeRates = z.infer<typeof FeeRatesSchema>

const SetFeeRatesSchema = z.object({
  maker: z.coerce.bigint(),
  taker: z.coerce.bigint()
})

export type SetFeeRates = z.infer<typeof SetFeeRatesSchema>

export const ConfigurationApiResponseSchema = z.object({
  chains: z.array(ChainSchema),
  markets: z.array(MarketSchema),
  feeRates: FeeRatesSchema
})
export type ConfigurationApiResponse = z.infer<
  typeof ConfigurationApiResponseSchema
>

const TestnetChallengeStatusSchema = z.enum([
  'Unenrolled',
  'PendingAirdrop',
  'PendingDeposit',
  'PendingDepositConfirmation',
  'Enrolled',
  'Disqualified'
])
export type TestnetChallengeStatus = z.infer<
  typeof TestnetChallengeStatusSchema
>

export const AuthorizedAddressSchema = z.object({
  address: z.string(),
  networkType: NetworkTypeSchema
})

export const TestnetChallengeDepositLimitSchema = z.object({
  symbol: z.string(),
  limit: z.coerce.bigint()
})

export const AccountConfigurationApiResponseSchema = z.object({
  newSymbols: z.array(SymbolSchema),
  role: z.enum(['User', 'Admin']),
  authorizedAddresses: z.array(AuthorizedAddressSchema),
  testnetChallengeStatus: TestnetChallengeStatusSchema,
  testnetChallengeDepositSymbol: z.string().nullable(),
  testnetChallengeDepositContract: AddressSchema.nullable(),
  testnetChallengeDepositLimits: z
    .array(TestnetChallengeDepositLimitSchema)
    .transform((data) => {
      return Object.fromEntries(data.map((it) => [it.symbol, it.limit]))
    }),
  nickName: z.string().nullable(),
  avatarUrl: z.string().nullable(),
  inviteCode: z.string(),
  pointsBalance: decimal()
})
export type AccountConfigurationApiResponse = z.infer<
  typeof AccountConfigurationApiResponseSchema
>

const OrderSideSchema = z.enum(['Buy', 'Sell'])
export type OrderSide = z.infer<typeof OrderSideSchema>

const FixedAmountSchema = z.object({
  type: z.literal('fixed'),
  value: z.coerce.bigint()
})

const PercentAmountSchema = z.object({
  type: z.literal('percent'),
  value: z.number()
})

const OrderAmountSchema = z.discriminatedUnion('type', [
  FixedAmountSchema,
  PercentAmountSchema
])

const CreateMarketOrderSchema = z.object({
  nonce: z.string(),
  type: z.literal('market'),
  marketId: z.string(),
  side: OrderSideSchema,
  amount: OrderAmountSchema,
  signature: z.string(),
  verifyingChainId: z.number()
})
export type CreateMarketOrder = z.infer<typeof CreateMarketOrderSchema>

const CreateBackToBackMarketOrderSchema = z.object({
  nonce: z.string(),
  type: z.literal('backToBackMarket'),
  marketId: z.string(),
  secondMarketId: z.string(),
  side: OrderSideSchema,
  amount: OrderAmountSchema,
  signature: z.string(),
  verifyingChainId: z.number()
})
export type CreateBackToBackMarketOrder = z.infer<
  typeof CreateBackToBackMarketOrderSchema
>

const CreateLimitOrderSchema = z.object({
  nonce: z.string(),
  type: z.literal('limit'),
  marketId: z.string(),
  side: OrderSideSchema,
  amount: OrderAmountSchema,
  price: decimal(),
  signature: z.string(),
  verifyingChainId: z.number()
})
export type CreateLimitOrder = z.infer<typeof CreateLimitOrderSchema>

const CreateOrderRequestSchema = z.discriminatedUnion('type', [
  CreateMarketOrderSchema,
  CreateLimitOrderSchema,
  CreateBackToBackMarketOrderSchema
])
export type CreateOrderRequest = z.infer<typeof CreateOrderRequestSchema>

const RequestStatusSchema = z.enum(['Accepted', 'Rejected'])
const CreateOrderApiResponseSchema = z.object({
  orderId: z.string(),
  requestStatus: RequestStatusSchema
})

const CancelOrderRequestSchema = z.object({
  orderId: z.string(),
  amount: z.coerce.bigint(),
  marketId: z.string(),
  side: OrderSideSchema,
  nonce: z.string(),
  signature: z.string(),
  verifyingChainId: z.number()
})
export type CancelOrderRequest = z.infer<typeof CancelOrderRequestSchema>

const ExecutionRoleSchema = z.enum(['Maker', 'Taker'])
export type ExecutionRole = z.infer<typeof ExecutionRoleSchema>

const OrderExecutionSchema = z.object({
  timestamp: z.coerce.date(),
  amount: z.coerce.bigint(),
  price: decimal(),
  role: ExecutionRoleSchema,
  feeAmount: z.coerce.bigint(),
  feeSymbol: z.string(),
  marketId: z.string()
})
export type OrderExecution = z.infer<typeof OrderExecutionSchema>

const OrderTimingSchema = z.object({
  createdAt: z.coerce.date(),
  updatedAt: z.coerce.date().nullable(),
  closedAt: z.coerce.date().nullable(),
  sequencerTimeNs: z.coerce.bigint()
})
export type OrderTiming = z.infer<typeof OrderTimingSchema>

const OrderStatusSchema = z.enum([
  'Open',
  'Partial',
  'Filled',
  'Cancelled',
  'Expired',
  'Failed',
  'Rejected'
])
export type OrderStatus = z.infer<typeof OrderStatusSchema>

const MarketOrderSchema = z.object({
  id: z.string(),
  type: z.literal('market'),
  status: OrderStatusSchema,
  marketId: z.string(),
  side: OrderSideSchema,
  amount: z.coerce.bigint(),
  executions: z.array(OrderExecutionSchema),
  timing: OrderTimingSchema
})
export type MarketOrder = z.infer<typeof MarketOrderSchema>

const LimitOrderSchema = z.object({
  id: z.string(),
  type: z.literal('limit'),
  status: OrderStatusSchema,
  marketId: z.string(),
  side: OrderSideSchema,
  amount: z.coerce.bigint(),
  price: decimal(),
  originalAmount: z.coerce.bigint(),
  autoReduced: z.boolean(),
  executions: z.array(OrderExecutionSchema),
  timing: OrderTimingSchema
})
export type LimitOrder = z.infer<typeof LimitOrderSchema>

const BackToBackMarketOrderSchema = z.object({
  id: z.string(),
  type: z.literal('backToBackMarket'),
  status: OrderStatusSchema,
  marketId: z.string(),
  secondMarketId: z.string(),
  side: OrderSideSchema,
  amount: z.coerce.bigint(),
  executions: z.array(OrderExecutionSchema),
  timing: OrderTimingSchema
})
export type BackToBackMarketOrder = z.infer<typeof BackToBackMarketOrderSchema>

export const OrderSchema = z
  .discriminatedUnion('type', [
    MarketOrderSchema,
    LimitOrderSchema,
    BackToBackMarketOrderSchema
  ])
  .transform((data) => {
    return {
      ...data,
      isFinal: function (): boolean {
        return (
          ['Filled', 'Cancelled', 'Expired', 'Failed', 'Rejected'].includes(
            data.status
          ) ||
          (data.status == 'Partial' &&
            (data.type === 'market' || data.type === 'backToBackMarket'))
        )
      }
    }
  })
export type Order = z.infer<typeof OrderSchema>

const TradeSettlementStatusSchema = z.enum([
  'Pending',
  'Settling',
  'FailedSettling',
  'Completed',
  'Failed'
])
export type TradeSettlementStatus = z.infer<typeof TradeSettlementStatusSchema>

export const TradeSchema = z.object({
  id: z.string(),
  timestamp: z.coerce.date(),
  orderId: z.string(),
  executionRole: ExecutionRoleSchema,
  counterOrderId: z.string(),
  marketId: z.string(),
  side: OrderSideSchema,
  amount: z.coerce.bigint(),
  price: decimal(),
  feeAmount: z.coerce.bigint(),
  feeSymbol: z.string(),
  settlementStatus: TradeSettlementStatusSchema
})
export type Trade = z.infer<typeof TradeSchema>

export const BalanceSchema = z.object({
  symbol: z.string(),
  total: z.coerce.bigint(),
  available: z.coerce.bigint()
})
export type Balance = z.infer<typeof BalanceSchema>

const GetBalancesApiResponseSchema = z.object({
  balances: z.array(BalanceSchema)
})

const CreateDepositApiRequestSchema = z.object({
  symbol: z.string(),
  amount: z.coerce.bigint(),
  txHash: z.string()
})

const DepositStatusSchema = z.enum(['Pending', 'Complete', 'Failed'])
export type DepositStatus = z.infer<typeof DepositStatusSchema>

const DepositSchema = z.object({
  id: z.string(),
  symbol: z.string(),
  txHash: z.string(),
  amount: z.coerce.bigint(),
  status: DepositStatusSchema,
  error: z.string().nullable(),
  createdAt: z.coerce.date()
})
export type Deposit = z.infer<typeof DepositSchema>

const DepositApiResponseSchema = z.object({
  deposit: DepositSchema
})

const ListDepositsApiResponseSchema = z.object({
  deposits: z.array(DepositSchema)
})

const CreateWithdrawalApiRequestSchema = z.object({
  symbol: z.string(),
  amount: z.coerce.bigint(),
  nonce: z.number(),
  signature: z.string()
})

const WithdrawalStatusSchema = z.enum([
  'Pending',
  'Sequenced',
  'Settling',
  'Complete',
  'Failed'
])
export type WithdrawalStatus = z.infer<typeof WithdrawalStatusSchema>
const WithdrawalSchema = z.object({
  id: z.string(),
  symbol: z.string(),
  txHash: z.string().nullable(),
  amount: z.coerce.bigint(),
  status: WithdrawalStatusSchema,
  error: z.string().nullable(),
  createdAt: z.coerce.date(),
  fee: z.coerce.bigint()
})
export type Withdrawal = z.infer<typeof WithdrawalSchema>

const WithdrawalApiResponseSchema = z.object({
  withdrawal: WithdrawalSchema
})

const ListWithdrawalsApiResponseSchema = z.object({
  withdrawals: z.array(WithdrawalSchema)
})

const FaucetRequestSchema = z.object({
  symbol: z.string(),
  address: AddressSchema
})
export type FaucetRequest = z.infer<typeof FaucetRequestSchema>

const AuthorizeWalletRequestSchema = z.object({
  authorizedAddress: z.string(),
  chainId: z.number(),
  address: z.string(),
  timestamp: z.string(),
  signature: z.string()
})

const EnrollChallengeSchema = z.object({
  inviteCode: z.string().nullable()
})

const SetNicknameSchema = z.object({
  name: z.string()
})

const SetAvatarUrlSchema = z.object({
  url: z.string()
})

const UserLinkedAccountTypeSchema = z.enum(['Discord', 'X'])
export type UserLinkedAccountType = z.infer<typeof UserLinkedAccountTypeSchema>

const StartOAuth2AccountLinkingApiResponse = z.object({
  authorizeUrl: z.string()
})

const CompleteOAuth2AccountLinkingApiRequestSchema = z.object({
  authorizationCode: z.string()
})

const LeaderboardTypeSchema = z.enum(['DailyPNL', 'WeeklyPNL', 'OverallPNL'])

export type LeaderboardType = z.infer<typeof LeaderboardTypeSchema>

const LeaderboardEntrySchema = z.object({
  label: z.string(),
  iconUrl: z.string().nullable(),
  value: z.number(),
  pnl: z.number()
})

const LeaderboardSchema = z.object({
  type: LeaderboardTypeSchema,
  page: z.number(),
  lastPage: z.number(),
  entries: z.array(LeaderboardEntrySchema)
})

export type Leaderboard = z.infer<typeof LeaderboardSchema>

const EnrolledCardSchema = z.object({
  type: z.literal('Enrolled')
})

const BitcoinConnectCardSchema = z.object({
  type: z.literal('BitcoinConnect')
})

const BitcoinWithdrawalCardSchema = z.object({
  type: z.literal('BitcoinWithdrawal')
})

const EvmWithdrawalCardSchema = z.object({
  type: z.literal('EvmWithdrawal')
})

const LinkDiscordCardSchema = z.object({
  type: z.literal('LinkDiscord')
})

const LinkXCardSchema = z.object({
  type: z.literal('LinkX')
})

const PointTypeSchema = z.enum([
  'DailyReward',
  'WeeklyReward',
  'OverallReward',
  'ReferralBonus',
  'EvmWalletConnected',
  'EvmWithdrawalDeposit',
  'BitcoinWalletConnected',
  'BitcoinWithdrawalDeposit'
])

const RewardCategorySchema = z.enum([
  'Top1',
  'Top1Percent',
  'Top5Percent',
  'Top10Percent',
  'Top25Percent',
  'Top50Percent',
  'Bottom5Percent',
  'Bottom1'
])

const RecentPointsCardSchema = z.object({
  type: z.literal('RecentPoints'),
  points: z.number(),
  pointType: PointTypeSchema,
  category: RewardCategorySchema.nullable()
})

const CardSchema = z.discriminatedUnion('type', [
  EnrolledCardSchema,
  RecentPointsCardSchema,
  BitcoinConnectCardSchema,
  BitcoinWithdrawalCardSchema,
  EvmWithdrawalCardSchema,
  LinkDiscordCardSchema,
  LinkXCardSchema
])

export type Card = z.infer<typeof CardSchema>

const ApiErrorSchema = z.object({
  displayMessage: z.string()
})
export type ApiError = z.infer<typeof ApiErrorSchema>

export const ApiErrorsSchema = z.object({
  errors: z.array(ApiErrorSchema)
})
export type ApiErrors = z.infer<typeof ApiErrorsSchema>

export const apiClient = new Zodios(apiBaseUrl, [
  {
    method: 'get',
    path: '/v1/account-config',
    alias: 'getAccountConfiguration',
    response: AccountConfigurationApiResponseSchema
  },
  {
    method: 'post',
    path: '/v1/account-config/:symbolName',
    alias: 'markSymbolAsAdded',
    parameters: [
      {
        name: 'symbolName',
        type: 'Path',
        schema: z.string()
      }
    ],
    response: z.undefined(),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'post',
    path: '/v1/orders',
    alias: 'createOrder',
    parameters: [
      {
        name: 'payload',
        type: 'Body',
        schema: CreateOrderRequestSchema
      }
    ],
    response: CreateOrderApiResponseSchema,
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'delete',
    path: '/v1/orders/:id',
    alias: 'cancelOrder',
    parameters: [
      {
        name: 'payload',
        type: 'Body',
        schema: CancelOrderRequestSchema
      }
    ],
    response: z.undefined(),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'post',
    path: '/v1/deposits',
    alias: 'createDeposit',
    parameters: [
      {
        name: 'payload',
        type: 'Body',
        schema: CreateDepositApiRequestSchema
      }
    ],
    response: DepositApiResponseSchema,
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'get',
    path: '/v1/deposits',
    alias: 'listDeposits',
    response: ListDepositsApiResponseSchema
  },
  {
    method: 'post',
    path: '/v1/withdrawals',
    alias: 'createWithdrawal',
    parameters: [
      {
        name: 'payload',
        type: 'Body',
        schema: CreateWithdrawalApiRequestSchema
      }
    ],
    response: WithdrawalApiResponseSchema,
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'get',
    path: '/v1/withdrawals',
    alias: 'listWithdrawals',
    response: ListWithdrawalsApiResponseSchema
  },
  {
    method: 'get',
    path: '/v1/balances',
    alias: 'getBalances',
    response: GetBalancesApiResponseSchema
  },
  {
    method: 'post',
    path: '/v1/faucet',
    alias: 'faucet',
    parameters: [
      {
        name: 'payload',
        type: 'Body',
        schema: FaucetRequestSchema
      }
    ],
    response: z.any(),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'post',
    path: '/v1/admin/fee-rates',
    alias: 'setFeeRates',
    parameters: [
      {
        name: 'payload',
        type: 'Body',
        schema: SetFeeRatesSchema
      }
    ],
    response: z.undefined(),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'get',
    path: '/v1/admin/admin',
    alias: 'listAdmins',
    parameters: [],
    response: z.array(AddressSchema),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'put',
    path: '/v1/admin/admin/:address',
    alias: 'addAdmin',
    parameters: [
      {
        name: 'address',
        type: 'Path',
        schema: AddressSchema
      }
    ],
    response: z.undefined(),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'delete',
    path: '/v1/admin/admin/:address',
    alias: 'removeAdmin',
    parameters: [
      {
        name: 'address',
        type: 'Path',
        schema: AddressSchema
      }
    ],
    response: z.undefined(),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'get',
    path: '/v1/admin/symbol',
    alias: 'listSymbols',
    parameters: [],
    response: z.array(AdminSymbolSchema),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'post',
    path: '/v1/admin/symbol',
    alias: 'addSymbol',
    parameters: [
      {
        name: 'payload',
        type: 'Body',
        schema: AdminSymbolSchema
      }
    ],
    response: z.undefined(),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'patch',
    path: '/v1/admin/symbol/:symbol',
    alias: 'patchSymbol',
    parameters: [
      {
        name: 'payload',
        type: 'Body',
        schema: AdminSymbolSchema
      },
      {
        name: 'symbol',
        type: 'Path',
        schema: z.string()
      }
    ],
    response: z.undefined(),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'get',
    path: '/v1/admin/market',
    alias: 'listMarkets',
    parameters: [],
    response: z.array(AdminMarketSchema),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'post',
    path: '/v1/admin/market',
    alias: 'addMarket',
    parameters: [
      {
        name: 'payload',
        type: 'Body',
        schema: AdminMarketSchema
      }
    ],
    response: z.undefined(),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'patch',
    path: '/v1/admin/market/:base/:quote',
    alias: 'patchMarket',
    parameters: [
      {
        name: 'payload',
        type: 'Body',
        schema: AdminMarketSchema
      },
      {
        name: 'base',
        type: 'Path',
        schema: z.string()
      },
      {
        name: 'quote',
        type: 'Path',
        schema: z.string()
      }
    ],
    response: z.undefined(),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'post',
    path: '/v1/testnet-challenge',
    alias: 'testnetChallengeEnroll',
    parameters: [
      {
        name: 'payload',
        type: 'Body',
        schema: EnrollChallengeSchema
      }
    ],
    response: z.undefined(),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'post',
    path: '/v1/testnet-challenge/nickname',
    alias: 'testnetChallengeSetNickname',
    parameters: [
      {
        name: 'payload',
        type: 'Body',
        schema: SetNicknameSchema
      }
    ],
    response: z.undefined(),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'post',
    path: '/v1/testnet-challenge/avatar',
    alias: 'testnetChallengeSetAvatarUrl',
    parameters: [
      {
        name: 'payload',
        type: 'Body',
        schema: SetAvatarUrlSchema
      }
    ],
    response: z.undefined(),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'get',
    path: '/v1/testnet-challenge/leaderboard/:type',
    alias: 'testnetChallengeGetLeaderboard',
    parameters: [
      {
        name: 'type',
        type: 'Path',
        schema: LeaderboardTypeSchema
      },
      {
        name: 'page',
        type: 'Query',
        schema: z.number()
      }
    ],
    response: LeaderboardSchema,
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'get',
    path: '/v1/testnet-challenge/cards',
    alias: 'testnetChallengeGetCards',
    parameters: [],
    response: z.array(CardSchema),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'post',
    path: '/v1/testnet-challenge/account-link/:accountType',
    alias: 'testnetChallengeStartAccountLinking',
    parameters: [
      {
        name: 'accountType',
        type: 'Path',
        schema: UserLinkedAccountTypeSchema
      }
    ],
    response: StartOAuth2AccountLinkingApiResponse,
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'put',
    path: '/v1/testnet-challenge/account-link/:accountType',
    alias: 'testnetChallengeCompleteAccountLinking',
    parameters: [
      {
        name: 'accountType',
        type: 'Path',
        schema: UserLinkedAccountTypeSchema
      },
      {
        name: 'payload',
        type: 'Body',
        schema: CompleteOAuth2AccountLinkingApiRequestSchema
      }
    ],
    response: z.undefined(),
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
      return loadAuthToken()
    },
    renewToken: async () => {
      return loadAuthToken({ forceRefresh: true })
    }
  })
)

export const noAuthApiClient = new Zodios(apiBaseUrl, [
  {
    method: 'get',
    path: '/v1/config',
    alias: 'getConfiguration',
    response: ConfigurationApiResponseSchema
  },
  {
    method: 'post',
    path: '/v1/wallets/authorize',
    alias: 'authorizeWallet',
    parameters: [
      {
        name: 'payload',
        type: 'Body',
        schema: AuthorizeWalletRequestSchema
      }
    ],
    response: z.undefined(),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  }
])

export const oauthRelayApiClient = new Zodios(apiBaseUrl, [
  {
    method: 'post',
    path: '/tma/v1/oauth-relay/discord/:code',
    alias: 'oauthRelayCompleteDiscordLinking',
    parameters: [
      {
        name: 'code',
        type: 'Path',
        schema: z.string()
      }
    ],
    response: z.undefined(),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  }
])

oauthRelayApiClient.use(
  pluginToken({
    getToken: async () => {
      return new Promise((resolve) => {
        const interval = setInterval(() => {
          const token = getOAuthRelayAuthToken()
          if (token !== '') {
            clearInterval(interval)
            resolve(token)
          }
        }, 100)
      })
    },
    renewToken: async () => {
      const token = getOAuthRelayAuthToken()
      return Promise.resolve(token)
    }
  })
)

export type AccessRestriction =
  | 'none'
  | 'underMaintenance'
  | 'geoBlocked'
  | 'requestLimitExceeded'
export function useAccessRestriction(): AccessRestriction {
  const [restriction, setRestriction] = useState<AccessRestriction>('none')

  useEffect(() => {
    const handleResponseCode = (status: number) => {
      if (status === 418) {
        setRestriction('underMaintenance')
      } else if (status === 451) {
        setRestriction('geoBlocked')
      } else if (status === 429) {
        setRestriction('requestLimitExceeded')
      } else {
        setRestriction('none')
      }
    }

    const setupInterceptors = (client: { axios: AxiosInstance }) => {
      return client.axios.interceptors.response.use(
        (response) => {
          handleResponseCode(response.status)
          return response
        },
        (error) => {
          handleResponseCode(error.response?.status || 0)
          return Promise.reject(error)
        }
      )
    }

    const id1 = setupInterceptors(apiClient)
    const id2 = setupInterceptors(noAuthApiClient)
    const id3 = setupInterceptors(oauthRelayApiClient)

    return () => {
      apiClient.axios.interceptors.response.eject(id1)
      apiClient.axios.interceptors.response.eject(id2)
      apiClient.axios.interceptors.response.eject(id3)
    }
  }, [])
  return restriction
}
