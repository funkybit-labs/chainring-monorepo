import { Market as ApiMarket } from 'apiClient'
import TradingSymbols from 'tradingSymbols'
import Decimal from 'decimal.js'
import TradingSymbol from 'tradingSymbol'

export class Market {
  id: string
  baseSymbol: TradingSymbol
  quoteSymbol: TradingSymbol
  readonly tickSize: Decimal
  readonly quoteDecimalPlaces: number
  readonly lastPrice: Decimal
  readonly minFee: bigint
  readonly marketIds: string[]

  constructor(
    id: string,
    baseSymbol: TradingSymbol,
    quoteSymbol: TradingSymbol,
    tickSize: Decimal,
    lastPrice: Decimal,
    minFee: bigint,
    marketIds: string[]
  ) {
    this.id = id
    this.baseSymbol = baseSymbol
    this.quoteSymbol = quoteSymbol
    this.tickSize = tickSize
    this.lastPrice = lastPrice
    this.quoteDecimalPlaces = this.tickSize.decimalPlaces() + 1
    this.minFee = minFee
    this.marketIds = marketIds
  }

  isBackToBack(): boolean {
    return this.marketIds.length == 2
  }
}

export default class Markets {
  private readonly markets: Market[]
  private marketById: Map<string, Market>

  constructor(
    markets: ApiMarket[],
    symbols: TradingSymbols,
    addBackToBackMarkets: boolean
  ) {
    this.markets = markets
      .map((m) => {
        return new Market(
          m.id,
          symbols.getByName(m.baseSymbol),
          symbols.getByName(m.quoteSymbol),
          m.tickSize,
          m.lastPrice,
          m.minFee,
          []
        )
      })
      .concat(
        this.resolveBackToBackMarkets(markets, symbols, addBackToBackMarkets)
      )

    this.marketById = new Map(this.markets.map((m) => [m.id, m]))
  }

  getById(id: string): Market {
    return this.findById(id)!
  }

  findById(id: string): Market | undefined {
    return this.marketById.get(id)
  }

  map<A>(f: (m: Market) => A): A[] {
    return this.markets.map(f)
  }

  flatMap<A>(f: (m: Market) => A[]): A[] {
    return this.markets.flatMap(f)
  }

  first(): Market | null {
    return this.markets.length > 0 ? this.markets[0] : null
  }

  find(f: (m: Market) => boolean): Market | undefined {
    return this.markets.find(f)
  }

  resolveBackToBackMarkets(
    markets: ApiMarket[],
    symbols: TradingSymbols,
    addBackToBackMarkets: boolean
  ): Market[] {
    if (addBackToBackMarkets) {
      const singleMarketsById = new Map(markets.map((m) => [m.id, m]))
      const allowedBases = markets.map((market) => market.baseSymbol)
      const allowedQuotes = markets.map((market) => market.quoteSymbol)
      const backToBackMarketsById = new Map<string, Market>()

      allowedBases.forEach((baseSymbol) => {
        allowedQuotes
          .filter((quoteSymbol) => quoteSymbol !== baseSymbol)
          .forEach((quoteSymbol) => {
            const marketId = `${baseSymbol}/${quoteSymbol}`
            const reverseMarketId = `${quoteSymbol}/${baseSymbol}`

            if (
              !backToBackMarketsById.has(marketId) &&
              !backToBackMarketsById.has(reverseMarketId) &&
              !singleMarketsById.has(marketId) &&
              !singleMarketsById.has(reverseMarketId)
            ) {
              // find all the markets with this base and group by the quote
              const marketsWithBase = markets
                .filter((market) => market.baseSymbol === baseSymbol)
                .reduce((acc, market) => {
                  if (!acc.has(market.quoteSymbol)) {
                    acc.set(market.quoteSymbol, [])
                  }
                  acc.get(market.quoteSymbol)!.push(market)
                  return acc
                }, new Map<string, ApiMarket[]>())

              // all the markets with this quote and group by the base
              const marketsWithQuote = markets
                .filter((market) => market.quoteSymbol === quoteSymbol)
                .reduce((acc, market) => {
                  if (!acc.has(market.baseSymbol)) {
                    acc.set(market.baseSymbol, [])
                  }
                  acc.get(market.baseSymbol)!.push(market)
                  return acc
                }, new Map<string, ApiMarket[]>())

              // if there are common symbols in the grouping keys then we can create a back to back market - sort and take first symbol for now
              const commonSymbols = Array.from(marketsWithBase.keys())
                .filter((symbol) => marketsWithQuote.has(symbol))
                .sort((a, b) => b.localeCompare(a))
              if (commonSymbols.length > 0) {
                const bridgeSymbol = commonSymbols[0]
                if (bridgeSymbol) {
                  const firstMarket = singleMarketsById.get(
                    `${baseSymbol}/${bridgeSymbol}`
                  )!
                  const secondMarket = singleMarketsById.get(
                    `${bridgeSymbol}/${quoteSymbol}`
                  )!
                  backToBackMarketsById.set(
                    marketId,
                    new Market(
                      marketId,
                      symbols.getByName(firstMarket.baseSymbol),
                      symbols.getByName(secondMarket.quoteSymbol),
                      secondMarket.tickSize,
                      firstMarket.lastPrice.mul(secondMarket.lastPrice),
                      secondMarket.minFee,
                      [firstMarket.id, secondMarket.id]
                    )
                  )
                }
              }
            }
          })
      })
      return Array.from(backToBackMarketsById.values())
    } else {
      return []
    }
  }
}
