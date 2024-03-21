import { Address } from 'viem'
import axios from 'axios'

export const apiBaseUrl = import.meta.env.ENV_API_URL

export type DeployedContract = {
  name: string
  address: Address
}

export type ERC20Token = {
  name: string
  symbol: string
  address: Address
  decimals: number
}

export type NativeToken = {
  name: string
  symbol: string
  decimals: number
}

export type Token = NativeToken | ERC20Token

export type Chain = {
  id: number
  contracts: DeployedContract[]
  erc20Tokens: ERC20Token[]
  nativeToken: NativeToken
}

export type ConfigurationApiResponse = {
  chains: Chain[]
}

export enum OrderSide {
  Buy = 'Buy',
  Sell = 'Sell'
}

export type TimeInForce =
  | { type: 'GoodTillCancelled' }
  | { type: 'GoodTillTime'; timestamp: number }
  | { type: 'ImmediateOrCancel' }

export type CreateMarketOrder = {
  nonce: string
  type: 'market'
  instrument: string
  side: string
  amount: number
}

export type CreateLimitOrder = {
  nonce: string
  type: 'limit'
  instrument: string
  side: string
  amount: number
  price: number
  timeInForce: TimeInForce
}

export type CreateOrderRequest = CreateMarketOrder | CreateLimitOrder

export type OrderExecution = {
  fee: number
  feeSymbol: string
  amountExecuted: number
}

export type OrderTiming = {
  createdAt: string
  updatedAt?: string
  filledAt?: string
  closedAt?: string
  expiredAt?: string
}

export type MarketOrderApiResponse = {
  id: string
  type: 'market'
  status: string
  instrument: string
  side: string
  amount: number
  originalAmount: number
  execution?: OrderExecution | null
  timing: OrderTiming
}

export type LimitOrderApiResponse = {
  id: string
  type: 'limit'
  status: string
  instrument: string
  side: string
  amount: number
  price: number
  originalAmount: number
  execution?: OrderExecution | null
  timing: OrderTiming
  timeInForce: TimeInForce
}

export type OrderApiResponse = MarketOrderApiResponse | LimitOrderApiResponse

export type OrderBook = {
  type: 'OrderBook'
  buy: OrderBookEntry[]
  sell: OrderBookEntry[]
  last: LastTrade
}

export type OrderBookEntry = {
  price: string
  size: number
}

export type LastTradeDirection = 'Up' | 'Down'

export type LastTrade = {
  price: string
  direction: LastTradeDirection
}

export type Publish = {
  type: 'Publish'
  data: Publishable
}

export type Publishable = OrderBook

export type OutgoingWSMessage = Publish

export async function getConfiguration(): Promise<ConfigurationApiResponse> {
  const response = await fetch(`${apiBaseUrl}/v1/config`)
  return (await response.json()) as ConfigurationApiResponse
}

export function createOrder(orderDetails: CreateOrderRequest) {
  return axios.post<OrderApiResponse>(`${apiBaseUrl}/v1/orders`, orderDetails)
}
