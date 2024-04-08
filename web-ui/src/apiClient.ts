import z from 'zod'
import { Zodios } from '@zodios/core'
import { pluginToken } from '@zodios/plugins'
import { loadAuthToken } from 'auth'
import Decimal from 'decimal.js'

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
          message: `${value} can't be parsed into Decimal`
        })
        return z.NEVER
      }
    })

const AddressSchema = z.custom<`0x${string}`>((val: unknown) =>
  /^0x/.test(val as string)
)

const DeployedContractSchema = z.object({
  name: z.string(),
  address: AddressSchema
})

export type DeployedContract = z.infer<typeof DeployedContractSchema>

const SymbolSchema = z.object({
  name: z.string(),
  description: z.string(),
  contractAddress: AddressSchema.nullable(),
  decimals: z.number()
})

export type TradingSymbol = z.infer<typeof SymbolSchema>

const ChainSchema = z.object({
  id: z.number(),
  contracts: z.array(DeployedContractSchema),
  symbols: z.array(SymbolSchema)
})
export type Chain = z.infer<typeof ChainSchema>

const MarketSchema = z.object({
  id: z.string(),
  baseSymbol: z.string(),
  quoteSymbol: z.string(),
  tickSize: decimal()
})
export type Market = z.infer<typeof MarketSchema>

const ConfigurationApiResponseSchema = z.object({
  chains: z.array(ChainSchema),
  markets: z.array(MarketSchema)
})
export type ConfigurationApiResponse = z.infer<
  typeof ConfigurationApiResponseSchema
>

const OrderSideSchema = z.enum(['Buy', 'Sell'])
export type OrderSide = z.infer<typeof OrderSideSchema>

const CreateMarketOrderSchema = z.object({
  nonce: z.string(),
  type: z.literal('market'),
  marketId: z.string(),
  side: OrderSideSchema,
  amount: z.coerce.bigint(),
  signature: z.string()
})
export type CreateMarketOrder = z.infer<typeof CreateMarketOrderSchema>

const CreateLimitOrderSchema = z.object({
  nonce: z.string(),
  type: z.literal('limit'),
  marketId: z.string(),
  side: OrderSideSchema,
  amount: z.bigint(),
  price: decimal(),
  signature: z.string()
})
export type CreateLimitOrder = z.infer<typeof CreateLimitOrderSchema>

const CreateOrderRequestSchema = z.discriminatedUnion('type', [
  CreateMarketOrderSchema,
  CreateLimitOrderSchema
])
export type CreateOrderRequest = z.infer<typeof CreateOrderRequestSchema>

const UpdateMarketOrderSchema = z.object({
  type: z.literal('market'),
  id: z.string(),
  amount: z.coerce.bigint()
})
export type UpdateMarketOrder = z.infer<typeof UpdateMarketOrderSchema>

const UpdateLimitOrderSchema = z.object({
  type: z.literal('limit'),
  id: z.string(),
  amount: z.coerce.bigint(),
  price: decimal()
})
export type UpdateLimitOrder = z.infer<typeof UpdateLimitOrderSchema>

const UpdateOrderRequestSchema = z.discriminatedUnion('type', [
  UpdateMarketOrderSchema,
  UpdateLimitOrderSchema
])
export type UpdateOrderRequest = z.infer<typeof UpdateOrderRequestSchema>

const ExecutionRoleSchema = z.enum(['Maker', 'Taker'])
export type ExecutionRole = z.infer<typeof ExecutionRoleSchema>

const OrderExecutionSchema = z.object({
  timestamp: z.coerce.date(),
  amount: z.coerce.bigint(),
  price: decimal(),
  role: ExecutionRoleSchema,
  feeAmount: z.coerce.bigint(),
  feeSymbol: z.string()
})
export type OrderExecution = z.infer<typeof OrderExecutionSchema>

const OrderTimingSchema = z.object({
  createdAt: z.coerce.date(),
  updatedAt: z.coerce.date().nullable(),
  closedAt: z.coerce.date().nullable()
})
export type OrderTiming = z.infer<typeof OrderTimingSchema>

const OrderStatusSchema = z.enum([
  'Open',
  'Partial',
  'Filled',
  'Cancelled',
  'Expired',
  'Failed',
  'Rejected',
  'CrossesMarket'
])
export type OrderStatus = z.infer<typeof OrderStatusSchema>

const MarketOrderSchema = z.object({
  id: z.string(),
  type: z.literal('market'),
  status: OrderStatusSchema,
  marketId: z.string(),
  side: OrderSideSchema,
  amount: z.coerce.bigint(),
  originalAmount: z.coerce.bigint(),
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
  executions: z.array(OrderExecutionSchema),
  timing: OrderTimingSchema
})
export type LimitOrder = z.infer<typeof LimitOrderSchema>

export const OrderSchema = z
  .discriminatedUnion('type', [MarketOrderSchema, LimitOrderSchema])
  .transform((data) => {
    return {
      ...data,
      isFinal: function (): boolean {
        return [
          'Filled',
          'Cancelled',
          'Expired',
          'Failed',
          'Rejected',
          'CrossesMarket'
        ].includes(data.status)
      }
    }
  })
export type Order = z.infer<typeof OrderSchema>

export const TradeSchema = z.object({
  id: z.string(),
  timestamp: z.coerce.date(),
  orderId: z.string(),
  marketId: z.string(),
  side: OrderSideSchema,
  amount: z.coerce.bigint(),
  price: decimal(),
  feeAmount: z.coerce.bigint(),
  feeSymbol: z.string()
})
export type Trade = z.infer<typeof TradeSchema>

const WithdrawTxSchema = z.object({
  sender: AddressSchema,
  token: AddressSchema.nullable(),
  amount: z.coerce.bigint(),
  nonce: z.coerce.bigint()
})
const CreateWithdrawalApiRequestSchema = z.object({
  tx: WithdrawTxSchema,
  signature: z.string()
})

const WithdrawalStatusSchema = z.enum(['Pending', 'Complete', 'Failed'])
const WithdrawalSchema = z.object({
  id: z.string(),
  tx: WithdrawTxSchema,
  status: WithdrawalStatusSchema,
  error: z.string().nullable()
})
const WithdrawalApiResponseSchema = z.object({
  withdrawal: WithdrawalSchema
})

const CreateSequencerDepositSchema = z.object({
  symbol: z.string(),
  amount: z.coerce.bigint()
})

const ApiErrorSchema = z.object({
  displayMessage: z.string()
})
export type ApiError = z.infer<typeof ApiErrorSchema>

const ApiErrorsSchema = z.object({
  errors: z.array(ApiErrorSchema)
})
export type ApiErrors = z.infer<typeof ApiErrorsSchema>

export const apiClient = new Zodios(apiBaseUrl, [
  {
    method: 'get',
    path: '/v1/config',
    alias: 'getConfiguration',
    response: ConfigurationApiResponseSchema
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
    response: OrderSchema,
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'patch',
    path: '/v1/orders/:id',
    alias: 'updateOrder',
    parameters: [
      {
        name: 'id',
        type: 'Path',
        schema: z.string()
      },
      {
        name: 'payload',
        type: 'Body',
        schema: UpdateOrderRequestSchema
      }
    ],
    response: OrderSchema,
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
    path: '/v1/withdrawals',
    alias: 'createWithdrawal',
    parameters: [
      {
        name: 'payload',
        type: 'Body',
        schema: CreateWithdrawalApiRequestSchema
      }
    ],
    response: WithdrawalApiResponseSchema
  },
  {
    method: 'get',
    path: '/v1/withdrawals/:id',
    alias: 'getWithdrawal',
    response: WithdrawalApiResponseSchema
  },
  {
    method: 'post',
    path: '/v1/sequencer-deposits',
    alias: 'createSequencerDeposit',
    parameters: [
      {
        name: 'payload',
        type: 'Body',
        schema: CreateSequencerDepositSchema
      }
    ],
    response: CreateSequencerDepositSchema
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
