import { OHLC, OHLCDuration, OrderBook } from 'websocketMessages'
import { OrderSide } from 'apiClient'
import { parseUnits } from 'viem'
import Markets, { Market } from 'markets'
import Decimal from 'decimal.js'
import { calculateNotional } from 'utils/index'
import TradingSymbol from 'tradingSymbol'

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

export function notionalToBaseWithScaling(
  notional: bigint,
  price: string,
  scaling: number
) {
  const decimalPrice = new Decimal(price)
  if (decimalPrice.isZero()) {
    return BigInt(0)
  }
  return BigInt(
    new Decimal(notional.toString())
      .div(new Decimal(price))
      .mul(Math.pow(10, scaling))
      .toDecimalPlaces(0)
      .toNumber()
  )
}

export function backToBackSetup(
  side: OrderSide,
  market: Market,
  markets: Markets
): {
  inputSymbol: TradingSymbol
  outputSymbol: TradingSymbol
  bridgeSymbol: TradingSymbol
  firstMarket: Market
  firstLegSide: OrderSide
  secondMarket: Market
  secondLegSide: OrderSide
} {
  const inputSymbol = side === 'Buy' ? market.quoteSymbol : market.baseSymbol
  const outputSymbol = side === 'Buy' ? market.baseSymbol : market.quoteSymbol
  const b2bMarkets = market.marketIds.map((id) => markets.getById(id))
  const firstMarket = b2bMarkets.filter((m) =>
    [m.baseSymbol.name, m.quoteSymbol.name].includes(inputSymbol.name)
  )[0]
  const firstLegSide = firstMarket.baseSymbol === inputSymbol ? 'Sell' : 'Buy'
  const secondMarket = b2bMarkets.filter((m) =>
    [m.baseSymbol.name, m.quoteSymbol.name].includes(outputSymbol.name)
  )[0]
  const secondLegSide =
    secondMarket.quoteSymbol === outputSymbol ? 'Sell' : 'Buy'
  const bridgeSymbol =
    firstMarket.baseSymbol === inputSymbol
      ? firstMarket.quoteSymbol
      : firstMarket.baseSymbol

  return {
    inputSymbol,
    outputSymbol,
    bridgeSymbol,
    firstMarket,
    firstLegSide,
    secondMarket,
    secondLegSide
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

    const {
      outputSymbol,
      bridgeSymbol,
      firstMarket,
      firstLegSide,
      secondMarket,
      secondLegSide
    } = backToBackSetup(side, market, markets)
    if (firstMarket === undefined || secondMarket === undefined) {
      return 0n
    }
    const firstOrderBook =
      firstMarket.id === market.marketIds[0] ? orderBook : secondMarketOrderBook
    const secondOrderBook =
      secondMarket.id === market.marketIds[0]
        ? orderBook
        : secondMarketOrderBook

    // the user specifies the amount of the input symbol to the back-to-back, but if the first back to back side is a
    // buy, then the amount they entered is actually the notional amount in the first market, so we need to adjust it to
    // be the base amount
    const firstLegAmount =
      firstLegSide === 'Buy'
        ? notionalToBaseWithScaling(
            amount,
            firstOrderBook.last.price,
            firstMarket.baseSymbol.decimals - market.baseSymbol.decimals
          )
        : amount

    const firstLegPrice = calculateMarketPrice(
      firstLegSide,
      firstLegAmount,
      firstMarket,
      firstOrderBook
    )
    if (firstLegPrice == 0n) {
      return 0n
    }
    const firstLegNotional = calculateNotional(
      firstLegPrice,
      firstLegAmount,
      firstMarket.baseSymbol
    )
    const bridgeAmount =
      firstLegSide === 'Buy' ? firstLegAmount : firstLegNotional
    const secondLegAmount =
      secondLegSide === 'Sell'
        ? bridgeAmount
        : notionalToBaseWithScaling(
            bridgeAmount,
            secondOrderBook.last.price,
            outputSymbol.decimals - bridgeSymbol.decimals
          )

    const secondLegPrice = calculateMarketPrice(
      secondLegSide,
      secondLegAmount,
      secondMarket,
      secondOrderBook
    )
    if (secondLegPrice == 0n) {
      return 0n
    }
    let scaledTotalPrice = new Decimal(1)
    if (side === 'Buy') {
      scaledTotalPrice = bigintToScaledDecimal(
        secondLegPrice,
        secondMarket.quoteSymbol.decimals
      )
      scaledTotalPrice = scaledTotalPrice.div(
        bigintToScaledDecimal(firstLegPrice, firstMarket.quoteSymbol.decimals)
      )
    } else {
      scaledTotalPrice = bigintToScaledDecimal(
        firstLegPrice,
        firstMarket.quoteSymbol.decimals
      )
      scaledTotalPrice = scaledTotalPrice.div(
        bigintToScaledDecimal(secondLegPrice, secondMarket.quoteSymbol.decimals)
      )
    }

    return scaledDecimalToBigint(scaledTotalPrice, market.quoteSymbol.decimals)
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
