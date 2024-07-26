import z from 'zod'
import { BalanceSchema, OrderSchema, TradeSchema } from 'apiClient'

export type SubscriptionTopic =
  | { type: 'OrderBook'; marketId: string }
  | { type: 'Prices'; marketId: string; duration: string }
  | { type: 'MyTrades' }
  | { type: 'MyOrders' }
  | { type: 'Balances' }
  | { type: 'Limits' }

export function orderBookTopic(marketId: string): SubscriptionTopic {
  return { type: 'OrderBook', marketId }
}

export function pricesTopic(
  marketId: string,
  duration: string
): SubscriptionTopic {
  return { type: 'Prices', marketId, duration }
}

export const myTradesTopic: SubscriptionTopic = { type: 'MyTrades' }
export const myOrdersTopic: SubscriptionTopic = { type: 'MyOrders' }
export const balancesTopic: SubscriptionTopic = { type: 'Balances' }
export const limitsTopic: SubscriptionTopic = { type: 'Limits' }

export type Publish = {
  type: 'Publish'
  data: Publishable
}

const OrderBookEntrySchema = z.object({
  price: z.string(),
  size: z.coerce.number()
})
export type OrderBookEntry = z.infer<typeof OrderBookEntrySchema>

const DirectionSchema = z.enum(['Up', 'Down', 'Unchanged'])
export type Direction = z.infer<typeof DirectionSchema>

const LastTradeSchema = z.object({
  price: z.string(),
  direction: DirectionSchema
})
export type LastTrade = z.infer<typeof LastTradeSchema>

export const OrderBookSchema = z.object({
  marketId: z.string(),
  type: z.literal('OrderBook'),
  buy: z.array(OrderBookEntrySchema),
  sell: z.array(OrderBookEntrySchema),
  last: LastTradeSchema
})
export type OrderBook = z.infer<typeof OrderBookSchema>

export const OHLCDurationSchema = z.enum([
  'P1M',
  'P5M',
  'P15M',
  'P1H',
  'P4H',
  'P1D'
])
export type OHLCDuration = z.infer<typeof OHLCDurationSchema>

const OHLCSchema = z.object({
  start: z.coerce.date(),
  duration: OHLCDurationSchema,
  open: z.number(),
  high: z.number(),
  low: z.number(),
  close: z.number()
})
export type OHLC = z.infer<typeof OHLCSchema>

export const PricesSchema = z.object({
  type: z.literal('Prices'),
  full: z.boolean(),
  ohlc: z.array(OHLCSchema),
  dailyChange: z.number()
})
export type Prices = z.infer<typeof PricesSchema>

export const MyTradesSchema = z.object({
  type: z.literal('MyTrades'),
  trades: z.array(TradeSchema)
})
export type MyTrades = z.infer<typeof MyTradesSchema>

export const MyTradesCreatedSchema = z.object({
  type: z.literal('MyTradesCreated'),
  trades: z.array(TradeSchema)
})
export type MyTradesCreated = z.infer<typeof MyTradesCreatedSchema>

export const MyTradesUpdatedSchema = z.object({
  type: z.literal('MyTradesUpdated'),
  trades: z.array(TradeSchema)
})
export type MyTradesUpdated = z.infer<typeof MyTradesUpdatedSchema>

export const MyOrdersSchema = z.object({
  type: z.literal('MyOrders'),
  orders: z.array(OrderSchema)
})
export type MyOrders = z.infer<typeof MyOrdersSchema>

export const BalancesSchema = z.object({
  type: z.literal('Balances'),
  balances: z.array(BalanceSchema)
})
export type Balances = z.infer<typeof BalancesSchema>

export const MyOrderCreatedSchema = z.object({
  type: z.literal('MyOrderCreated'),
  order: OrderSchema
})
export type MyOrderCreated = z.infer<typeof MyOrderCreatedSchema>

export const MyOrderUpdatedSchema = z.object({
  type: z.literal('MyOrderUpdated'),
  order: OrderSchema
})
export type MyOrderUpdated = z.infer<typeof MyOrderUpdatedSchema>

export const MarketLimitsSchema = z
  .tuple([
    z.string(), // marketId
    z.coerce.bigint(), // base
    z.coerce.bigint() // quote
  ])
  .transform((tuple) => {
    return {
      marketId: tuple[0],
      base: tuple[1],
      quote: tuple[2]
    }
  })
export type MarketLimits = z.infer<typeof MarketLimitsSchema>

export const LimitsSchema = z.object({
  type: z.literal('Limits'),
  limits: z.array(MarketLimitsSchema)
})
export type Limits = z.infer<typeof LimitsSchema>

export function limitsForMarket(
  limits: Limits,
  marketId: string
): MarketLimits | undefined {
  return limits.limits.find((marketLimits) => marketLimits.marketId == marketId)
}

export const PublishableSchema = z.discriminatedUnion('type', [
  OrderBookSchema,
  PricesSchema,
  MyTradesSchema,
  MyTradesCreatedSchema,
  MyTradesUpdatedSchema,
  MyOrdersSchema,
  BalancesSchema,
  MyOrderCreatedSchema,
  MyOrderUpdatedSchema,
  LimitsSchema
])
export type Publishable = z.infer<typeof PublishableSchema>

export type IncomingWSMessage = Publish
