import z from 'zod'
import { Zodios } from '@zodios/core'
import { pluginToken } from '@zodios/plugins'
import { loadAuthToken } from 'auth'
import Decimal from 'decimal.js'
import { useEffect, useState } from 'react'

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

export const AddressSchema = z.custom<`0x${string}`>((val: unknown) =>
  /^0x/.test(val as string)
)
export type AddressType = z.infer<typeof AddressSchema>

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

export type SymbolType = z.infer<typeof SymbolSchema>

const ChainSchema = z.object({
  id: z.number(),
  name: z.string(),
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

const FeeRatesSchema = z.object({
  maker: z.coerce.bigint(),
  taker: z.coerce.bigint()
})
export type FeeRates = z.infer<typeof FeeRatesSchema>

const ConfigurationApiResponseSchema = z.object({
  chains: z.array(ChainSchema),
  markets: z.array(MarketSchema),
  feeRates: FeeRatesSchema
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
  signature: z.string(),
  verifyingChainId: z.number()
})
export type CreateMarketOrder = z.infer<typeof CreateMarketOrderSchema>

const CreateLimitOrderSchema = z.object({
  nonce: z.string(),
  type: z.literal('limit'),
  marketId: z.string(),
  side: OrderSideSchema,
  amount: z.bigint(),
  price: decimal(),
  signature: z.string(),
  verifyingChainId: z.number()
})
export type CreateLimitOrder = z.infer<typeof CreateLimitOrderSchema>

const CreateOrderRequestSchema = z.discriminatedUnion('type', [
  CreateMarketOrderSchema,
  CreateLimitOrderSchema
])
export type CreateOrderRequest = z.infer<typeof CreateOrderRequestSchema>

const RequestStatusSchema = z.enum(['Accepted', 'Rejected'])
const CreateOrderApiResponseSchema = z.object({
  orderId: z.string(),
  requestStatus: RequestStatusSchema
})

const UpdateOrderRequestSchema = z.object({
  type: z.literal('limit'),
  orderId: z.string(),
  amount: z.coerce.bigint(),
  price: decimal(),
  marketId: z.string(),
  side: OrderSideSchema,
  nonce: z.string(),
  signature: z.string(),
  verifyingChainId: z.number()
})
export type UpdateOrderRequest = z.infer<typeof UpdateOrderRequestSchema>

const UpdateOrderApiResponseSchema = z.object({
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
        return (
          ['Filled', 'Cancelled', 'Expired', 'Failed', 'Rejected'].includes(
            data.status
          ) ||
          (data.status == 'Partial' && data.type == 'market')
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
  amount: z.coerce.bigint(),
  status: WithdrawalStatusSchema,
  error: z.string().nullable(),
  createdAt: z.coerce.date()
})
export type Withdrawal = z.infer<typeof WithdrawalSchema>

const WithdrawalApiResponseSchema = z.object({
  withdrawal: WithdrawalSchema
})

const ListWithdrawalsApiResponseSchema = z.object({
  withdrawals: z.array(WithdrawalSchema)
})

const FaucetRequestSchema = z.object({
  chainId: z.number(),
  address: AddressSchema
})
export type FaucetRequest = z.infer<typeof FaucetRequestSchema>

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
    response: CreateOrderApiResponseSchema,
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
    response: UpdateOrderApiResponseSchema,
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

export function useMaintenance() {
  const [maintenance, setMaintenance] = useState(false)

  useEffect(() => {
    const id = apiClient.axios.interceptors.response.use(
      (response) => {
        setMaintenance(false)
        return response
      },
      (error) => {
        if (error.response.status == 418) {
          setMaintenance(true)
        } else if (maintenance) {
          setMaintenance(false)
        }
        return Promise.reject(error)
      }
    )
    return () => {
      apiClient.axios.interceptors.response.eject(id)
    }
  })
  return maintenance
}
