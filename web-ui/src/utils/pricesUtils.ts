import { OHLC, OHLCDuration, OrderBook } from 'websocketMessages'
import { OrderSide } from 'apiClient'
import { parseUnits } from 'viem'
import Markets, { Market } from 'markets'
import Decimal from 'decimal.js'
import { calculateNotional } from 'utils/index'

export const ohlcDurationsMs: Record<OHLCDuration, number> = {
  ['P1M']: 60 * 1000,
  ['P5M']: 5 * 60 * 1000,
  ['P15M']: 15 * 60 * 1000,
  ['P1H']: 60 * 60 * 1000,
  ['P4H']: 4 * 60 * 60 * 1000,
  ['P1D']: 24 * 60 * 60 * 1000
}

export function mergeOHLC(
  draft: OHLC[],
  incoming: OHLC[],
  duration: OHLCDuration,
  currentTimeMs: number = new Date().getTime()
): OHLC[] {
  // merge new data
  incoming.forEach((newItem) => {
    // lookup if recent item is being replaced
    const index = draft.findIndex(
      (i) => i.start.getTime() == newItem.start.getTime()
    )
    if (index == -1) {
      // fill gaps before pushing new ohlc
      fillGaps(
        draft,
        newItem.start.getTime(),
        ohlcDurationsMs[duration],
        currentTimeMs
      )

      draft.push(newItem)
    } else {
      draft[index] = newItem
    }
  })
  fillGaps(draft, Date.now(), ohlcDurationsMs[duration], currentTimeMs)
  return draft
}

function fillGaps(
  draft: OHLC[],
  untilTime: number,
  ohlcDuration: number,
  currentTimeMs: number
) {
  while (
    draft.length > 0 &&
    untilTime - draft[draft.length - 1].start.getTime() > ohlcDuration &&
    draft[draft.length - 1].start.getTime() + ohlcDuration < currentTimeMs
  ) {
    const lastItem = draft[draft.length - 1]

    draft.push({
      start: new Date(lastItem.start.getTime() + ohlcDuration),
      duration: lastItem.duration,
      open: lastItem.close,
      high: lastItem.close,
      low: lastItem.close,
      close: lastItem.close
    })
  }
}

export function getMarketPrice(
  side: OrderSide,
  amount: bigint,
  market: Market,
  orderBook: OrderBook,
  markets: Markets,
  secondMarketOrderBook: OrderBook | undefined
): bigint {
  if (market.isBackToBack()) {
    if (secondMarketOrderBook === undefined) {
      return 0n
    }

    const firstMarket = markets.getById(market.marketIds[0])
    const secondMarket = markets.getById(market.marketIds[1])
    const firstOrderPrice = calculateMarketPrice(
      side,
      amount,
      markets.getById(market.marketIds[0]),
      orderBook
    )
    const firstOrderNotional = calculateNotional(
      firstOrderPrice,
      amount,
      market.baseSymbol
    )
    const secondOrderPrice = calculateMarketPrice(
      side,
      firstOrderNotional,
      secondMarket,
      secondMarketOrderBook
    )
    return scaledDecimalToBigint(
      bigintToScaledDecimal(
        firstOrderPrice,
        firstMarket.quoteSymbol.decimals
      ).mul(
        bigintToScaledDecimal(
          secondOrderPrice,
          secondMarket.quoteSymbol.decimals
        )
      ),
      secondMarket.quoteSymbol.decimals
    )
  } else {
    return calculateMarketPrice(side, amount, market, orderBook)
  }
}

function calculateMarketPrice(
  side: OrderSide,
  amount: bigint,
  market: Market,
  orderBook: OrderBook
): bigint {
  const levels = (
    side == 'Buy' ? orderBook.sell : orderBook.buy.toReversed()
  ).map((l) => {
    return {
      size: parseUnits(l.size.toString(), market.baseSymbol.decimals),
      price: parseUnits(l.price, market.quoteSymbol.decimals)
    }
  })

  if (amount == 0n) {
    return levels.length > 0 ? levels[levels.length - 1].price : 0n
  }

  let amountCovered = 0n
  const orderChunks: { size: bigint; price: bigint }[] = []
  while (amountCovered < amount && levels.length > 0) {
    const level = levels.pop()!
    const amountLeftToCover = amount - amountCovered
    const orderChunk = {
      size: amountLeftToCover < level.size ? amountLeftToCover : level.size,
      price: level.price
    }
    amountCovered += orderChunk.size
    orderChunks.push(orderChunk)
  }

  if (amountCovered == 0n) {
    return 0n
  }

  // size-weighted average across levels consumed
  return (
    orderChunks.reduce((acc, c) => acc + c.price * c.size, 0n) / amountCovered
  )
}

export function bigintToScaledDecimal(bi: bigint, decimals: number): Decimal {
  const scaleFactor = new Decimal(10).pow(decimals)
  return new Decimal(bi.toString()).div(scaleFactor)
}

export function scaledDecimalToBigint(sd: Decimal, decimals: number): bigint {
  const scaleFactor = new Decimal(10).pow(decimals)
  const scaled = sd.mul(scaleFactor).floor()
  return BigInt(scaled.toHex())
}
