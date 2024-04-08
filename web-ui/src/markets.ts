import { Market as ApiMarket, TradingSymbol } from 'apiClient'
import TradingSymbols from 'tradingSymbols'
import Decimal from 'decimal.js'

export class Market {
  id: string
  baseSymbol: TradingSymbol
  quoteSymbol: TradingSymbol
  tickSize: Decimal

  constructor(
    id: string,
    baseSymbol: TradingSymbol,
    quoteSymbol: TradingSymbol,
    tickSize: Decimal
  ) {
    this.id = id
    this.baseSymbol = baseSymbol
    this.quoteSymbol = quoteSymbol
    this.tickSize = tickSize
  }

  getQuoteDecimalPlaces(): number {
    return this.tickSize.decimalPlaces() + 1
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
        m.tickSize
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

  first(): Market | null {
    return this.markets.length > 0 ? this.markets[0] : null
  }
}
