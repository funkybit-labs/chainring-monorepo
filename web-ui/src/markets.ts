import { Market as ApiMarket, OrderSide } from 'apiClient'
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
  readonly backToBackSides?: OrderSide[]

  constructor(
    id: string,
    baseSymbol: TradingSymbol,
    quoteSymbol: TradingSymbol,
    tickSize: Decimal,
    lastPrice: Decimal,
    minFee: bigint,
    marketIds: string[],
    backToBackSides?: OrderSide[]
  ) {
    this.id = id
    this.baseSymbol = baseSymbol
    this.quoteSymbol = quoteSymbol
    this.tickSize = tickSize
    this.lastPrice = lastPrice
    this.quoteDecimalPlaces = this.tickSize.decimalPlaces() + 1
    this.minFee = minFee
    this.marketIds = marketIds
    this.backToBackSides = backToBackSides
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
          [],
          undefined
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
      const backToBackMarketsById = new Map<string, Market>()

      singleMarketsById.forEach((market, id) => {
        singleMarketsById.forEach((otherMarket, otherId) => {
          let potentialBackToBackSymbols: string[] = []
          let backToBackSides: OrderSide[] = []
          let tickSize: Decimal | undefined
          let lastPrice: Decimal | undefined
          if (otherId !== id) {
            if (otherMarket.baseSymbol === market.baseSymbol) {
              potentialBackToBackSymbols = [
                market.quoteSymbol,
                otherMarket.quoteSymbol
              ]
              tickSize = otherMarket.tickSize
              lastPrice = new Decimal(1)
                .div(market.lastPrice)
                .mul(otherMarket.lastPrice)
              backToBackSides = ['Buy', 'Sell']
            } else if (otherMarket.baseSymbol === market.quoteSymbol) {
              potentialBackToBackSymbols = [
                market.baseSymbol,
                otherMarket.quoteSymbol
              ]
              tickSize = otherMarket.tickSize
              lastPrice = market.lastPrice.mul(otherMarket.lastPrice)
              backToBackSides = ['Sell', 'Sell']
            } else if (otherMarket.quoteSymbol === market.baseSymbol) {
              potentialBackToBackSymbols = [
                market.quoteSymbol,
                otherMarket.baseSymbol
              ]
              tickSize = new Decimal(1).div(otherMarket.tickSize)
              lastPrice = new Decimal(1)
                .div(market.lastPrice)
                .mul(new Decimal(1).div(otherMarket.lastPrice))
              backToBackSides = ['Buy', 'Buy']
            } else if (otherMarket.quoteSymbol === market.quoteSymbol) {
              potentialBackToBackSymbols = [
                market.baseSymbol,
                otherMarket.baseSymbol
              ]
              tickSize = new Decimal(1).div(otherMarket.tickSize)
              lastPrice = market.lastPrice.mul(
                new Decimal(1).div(otherMarket.lastPrice)
              )
              backToBackSides = ['Sell', 'Buy']
            }
          }
          if (
            potentialBackToBackSymbols.length > 0 &&
            tickSize !== undefined &&
            lastPrice !== undefined
          ) {
            const marketId = `${potentialBackToBackSymbols[0]}/${potentialBackToBackSymbols[1]}`
            const reversedMarketId = `${potentialBackToBackSymbols[1]}/${potentialBackToBackSymbols[0]}`
            if (
              !backToBackMarketsById.has(marketId) &&
              !backToBackMarketsById.has(reversedMarketId)
            ) {
              // new back-to-back
              backToBackMarketsById.set(
                marketId,
                new Market(
                  marketId,
                  symbols.getByName(potentialBackToBackSymbols[0]),
                  symbols.getByName(potentialBackToBackSymbols[1]),
                  tickSize,
                  lastPrice,
                  otherMarket.minFee,
                  [market.id, otherMarket.id],
                  backToBackSides
                )
              )
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
