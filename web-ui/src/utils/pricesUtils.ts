import { OHLC, OHLCDuration, OrderBook } from 'websocketMessages'
import { OrderSide } from 'apiClient'
import { parseUnits } from 'viem'
import { Market } from 'markets'

export const olhcDurationsMs: Record<OHLCDuration, number> = {
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
  duration: OHLCDuration
): OHLC[] {
  // update completes of last item before merge
  updateLastItemCompleteness(draft, olhcDurationsMs[duration])

  // merge new data
  incoming.forEach((newItem) => {
    // lookup if recent item is being replaced
    const index = draft.findIndex(
      (i) => i.start.getTime() == newItem.start.getTime()
    )
    if (index == -1) {
      // fill gaps before pushing new ohlc
      fillGaps(draft, newItem, olhcDurationsMs[duration])

      draft.push(newItem)
    } else {
      draft[index] = newItem
    }
  })
  // update completes of last item after merge
  updateLastItemCompleteness(draft, olhcDurationsMs[duration])
  return draft
}

function updateLastItemCompleteness(ohlc: OHLC[], duration: number): OHLC[] {
  if (ohlc.length > 0) {
    const mostRecentItem = ohlc[ohlc.length - 1]
    mostRecentItem.incomplete =
      mostRecentItem.start.getTime() + duration > Date.now()
  }
  return ohlc
}

function fillGaps(draft: OHLC[], newItem: OHLC, duration: number) {
  while (
    draft.length > 0 &&
    newItem.start > draft[draft.length - 1].start &&
    newItem.start.getTime() - draft[draft.length - 1].start.getTime() > duration
  ) {
    const lastItem = draft[draft.length - 1]

    draft.push({
      start: new Date(lastItem.start.getTime() + duration),
      duration: lastItem.duration,
      open: lastItem.close,
      high: lastItem.close,
      low: lastItem.close,
      close: lastItem.close,
      incomplete: false
    })
  }
}

export function getMarketPrice(
  side: OrderSide,
  amount: bigint,
  market: Market,
  orderBook: OrderBook
): bigint | undefined {
  const levels = (
    side == 'Buy' ? orderBook.sell : orderBook.buy.toReversed()
  ).map((l) => {
    return {
      size: parseUnits(l.size.toString(), market.baseSymbol.decimals),
      price: parseUnits(l.price, market.quoteSymbol.decimals)
    }
  })

  if (amount == 0n) {
    return levels.length > 0 ? levels[levels.length - 1].price : undefined
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

  return (
    orderChunks.reduce((acc, c) => acc + c.price * c.size, 0n) /
    orderChunks.reduce((acc, c) => acc + c.size, 0n)
  )
}
