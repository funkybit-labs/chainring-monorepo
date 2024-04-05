import z from 'zod'
import { OrderSchema, TradeSchema } from 'ApiClient'

export type SubscriptionTopic =
  | { type: 'OrderBook'; marketId: string }
  | { type: 'Prices'; marketId: string }
  | { type: 'Trades' }
  | { type: 'Orders' }

export function orderBookTopic(marketId: string): SubscriptionTopic {
  return { type: 'OrderBook', marketId }
}

export function pricesTopic(marketId: string): SubscriptionTopic {
  return { type: 'Prices', marketId }
}

export const tradesTopic: SubscriptionTopic = { type: 'Trades' }
export const ordersTopic: SubscriptionTopic = { type: 'Orders' }

export type Publish = {
  type: 'Publish'
  data: Publishable
}

const OrderBookEntrySchema = z.object({
  price: z.string(),
  size: z.number()
})
export type OrderBookEntry = z.infer<typeof OrderBookEntrySchema>

const DirectionSchema = z.enum(['Up', 'Down'])
export type Direction = z.infer<typeof DirectionSchema>

const LastTradeSchema = z.object({
  price: z.string(),
  direction: DirectionSchema
})
export type LastTrade = z.infer<typeof LastTradeSchema>

export const OrderBookSchema = z.object({
  type: z.literal('OrderBook'),
  buy: z.array(OrderBookEntrySchema),
  sell: z.array(OrderBookEntrySchema),
  last: LastTradeSchema
})
export type OrderBook = z.infer<typeof OrderBookSchema>

const OHLCSchema = z.object({
  start: z.coerce.date(),
  durationMs: z.number(),
  open: z.number(),
  high: z.number(),
  low: z.number(),
  close: z.number(),
  incomplete: z.boolean().optional()
})
export type OHLC = z.infer<typeof OHLCSchema>

export const PricesSchema = z.object({
  type: z.literal('Prices'),
  full: z.boolean(),
  ohlc: z.array(OHLCSchema)
})
export type Prices = z.infer<typeof PricesSchema>

export const TradesSchema = z.object({
  type: z.literal('Trades'),
  trades: z.array(TradeSchema)
})
export type Trades = z.infer<typeof TradesSchema>

export const OrdersSchema = z.object({
  type: z.literal('Orders'),
  orders: z.array(OrderSchema)
})
export type Orders = z.infer<typeof OrdersSchema>

export const OrderCreatedSchema = z.object({
  type: z.literal('OrderCreated'),
  order: OrderSchema
})
export type OrderCreated = z.infer<typeof OrderCreatedSchema>

export const OrderUpdatedSchema = z.object({
  type: z.literal('OrderUpdated'),
  order: OrderSchema
})
export type OrderUpdated = z.infer<typeof OrderUpdatedSchema>

export const PublishableSchema = z.discriminatedUnion('type', [
  OrderBookSchema,
  PricesSchema,
  TradesSchema,
  OrdersSchema,
  OrderCreatedSchema,
  OrderUpdatedSchema
])
export type Publishable = z.infer<typeof PublishableSchema>

export type IncomingWSMessage = Publish
