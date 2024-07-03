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
  readonly minAllowedBidPrice: Decimal
  readonly maxAllowedOfferPrice: Decimal
  readonly minFee: bigint

  constructor(
    id: string,
    baseSymbol: TradingSymbol,
    quoteSymbol: TradingSymbol,
    tickSize: Decimal,
    lastPrice: Decimal,
    minAllowedBidPrice: Decimal,
    maxAllowedOfferPrice: Decimal,
    minFee: bigint
  ) {
    this.id = id
    this.baseSymbol = baseSymbol
    this.quoteSymbol = quoteSymbol
    this.tickSize = tickSize
    this.lastPrice = lastPrice
    this.quoteDecimalPlaces = this.tickSize.decimalPlaces() + 1
    this.minAllowedBidPrice = minAllowedBidPrice
    this.maxAllowedOfferPrice = maxAllowedOfferPrice
    this.minFee = minFee
  }
}

export default class Markets {
  private readonly markets: Market[]
  private marketById: Map<string, Market>

  constructor(markets: ApiMarket[], symbols: TradingSymbols) {
    this.markets = markets.map((m) => {
      return new Market(
        m.id,
        symbols.getByName(m.baseSymbol),
        symbols.getByName(m.quoteSymbol),
        m.tickSize,
        m.lastPrice,
        m.minAllowedBidPrice,
        m.maxAllowedOfferPrice,
        m.minFee
      )
    })
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
}
