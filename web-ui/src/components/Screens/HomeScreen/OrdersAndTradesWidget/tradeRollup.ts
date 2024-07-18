import TradingSymbol from 'tradingSymbol'
import Decimal from 'decimal.js'
import { Trade, TradeSettlementStatus } from 'apiClient'
import { calculateNotional } from 'utils'
import { bigintToScaledDecimal, scaledDecimalToBigint } from 'utils/pricesUtils'
import Markets from 'markets'

export type OrderTradesGroup = {
  id: string
  timestamp: Date
  sellSymbol: TradingSymbol
  buySymbol: TradingSymbol
  aggregatedAmount: bigint
  aggregatedPrice: Decimal
  priceDecimalPlaces: number
  aggregatedFeeAmount: bigint
  feeSymbol: TradingSymbol
  settlementStatus: TradeSettlementStatus
  trades: OrderTradesRow[]
  expanded: boolean
}

export type OrderTradesRow = {
  id: string
  orderId: string
  marketId: string
  sellSymbol: TradingSymbol
  buySymbol: TradingSymbol
  amount: bigint
  price: Decimal
  priceDecimalPlaces: number
  feeAmount: bigint
  feeSymbol: TradingSymbol
}

export function rollupTrades(
  trades: Trade[],
  markets: Markets
): OrderTradesGroup[] {
  const tradeGroups: Trade[][] = []
  trades.forEach((trade) => {
    if (tradeGroups.length == 0) {
      tradeGroups.push([trade])
    } else {
      const lastTradeGroup = tradeGroups[tradeGroups.length - 1]
      if (belongToSameGroup(lastTradeGroup[0], trade)) {
        lastTradeGroup.push(trade)
      } else {
        tradeGroups.push([trade])
      }
    }
  })
  return tradeGroups.map((tg) => rollupTradesGroup(tg, markets))
}

function belongToSameGroup(trade1: Trade, trade2: Trade): boolean {
  if (trade1.executionRole != trade2.executionRole) {
    return false
  }

  return tradeGroupId(trade1) == tradeGroupId(trade2)
}

export function tradeGroupId(trade: Trade): string {
  if (trade.executionRole == 'Taker') {
    return `taker:${trade.orderId}`
  } else {
    return `maker:${trade.counterOrderId}`
  }
}

export function rollupTradesGroup(
  trades: Trade[],
  markets: Markets
): OrderTradesGroup {
  const tradeRows = trades.map((t) => {
    const market = markets.getById(t.marketId)
    if (t.side === 'Buy') {
      return {
        id: t.id,
        orderId: t.orderId,
        marketId: t.marketId,
        sellSymbol: market.quoteSymbol,
        buySymbol: market.baseSymbol,
        amount: calculateNotional(
          scaledDecimalToBigint(t.price, market.quoteSymbol.decimals),
          t.amount,
          market.baseSymbol
        ),
        price: new Decimal(1).div(t.price),
        priceDecimalPlaces: 6,
        feeAmount: t.feeAmount,
        feeSymbol: market.quoteSymbol
      }
    } else {
      return {
        id: t.id,
        orderId: t.orderId,
        marketId: t.marketId,
        sellSymbol: market.baseSymbol,
        buySymbol: market.quoteSymbol,
        amount: t.amount,
        price: t.price,
        priceDecimalPlaces: market.quoteDecimalPlaces,
        feeAmount: t.feeAmount,
        feeSymbol: market.quoteSymbol
      }
    }
  })

  const firstTrade = tradeRows[0]
  const lastTrade = tradeRows[trades.length - 1]

  if (firstTrade.marketId == lastTrade.marketId) {
    const market = markets.getById(firstTrade.marketId)
    const aggregate = aggregateRows(tradeRows)

    return {
      id: tradeGroupId(trades[0]),
      timestamp: trades[0].timestamp,
      sellSymbol: firstTrade.sellSymbol,
      buySymbol: firstTrade.buySymbol,
      aggregatedAmount: aggregate.amount,
      aggregatedPrice: aggregate.price,
      priceDecimalPlaces: firstTrade.priceDecimalPlaces,
      aggregatedFeeAmount: tradeRows.reduce((acc, c) => acc + c.feeAmount, 0n),
      feeSymbol: market.quoteSymbol,
      settlementStatus: trades[0].settlementStatus,
      trades: tradeRows,
      expanded: false
    }
  } else {
    const firstTradeMarket = markets.getById(firstTrade.marketId)
    const lastTradeMarket = markets.getById(lastTrade.marketId)

    const firstMarketAggregate = aggregateRows(
      tradeRows.filter((c) => c.marketId == firstTradeMarket.id)
    )

    const lastMarketAggregate = aggregateRows(
      tradeRows.filter((c) => c.marketId == lastTradeMarket.id)
    )

    return {
      id: tradeGroupId(trades[0]),
      timestamp: trades[0].timestamp,
      sellSymbol: firstTrade.sellSymbol,
      buySymbol: lastTrade.buySymbol,
      aggregatedAmount: firstMarketAggregate.amount,
      aggregatedPrice: firstMarketAggregate.price.mul(
        lastMarketAggregate.price
      ),
      priceDecimalPlaces: firstTrade.priceDecimalPlaces,
      aggregatedFeeAmount: tradeRows.reduce((acc, c) => acc + c.feeAmount, 0n),
      feeSymbol: tradeRows.find((tr) => tr.feeAmount > 0)!.feeSymbol,
      settlementStatus: trades[0].settlementStatus,
      trades: tradeRows,
      expanded: false
    }
  }
}

function aggregateRows(tradeRows: OrderTradesRow[]): {
  amount: bigint
  price: Decimal
} {
  const priceDecimals = tradeRows[0].buySymbol.decimals

  const aggregatedAmount = tradeRows.reduce((acc, c) => acc + c.amount, 0n)
  const aggregatedPrice = bigintToScaledDecimal(
    tradeRows.reduce(
      (acc, tr) =>
        acc + scaledDecimalToBigint(tr.price, priceDecimals) * tr.amount,
      0n
    ) / aggregatedAmount,
    priceDecimals
  )

  return { amount: aggregatedAmount, price: aggregatedPrice }
}
