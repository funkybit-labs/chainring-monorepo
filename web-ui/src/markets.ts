import { Market as ApiMarket, TradingSymbol } from 'ApiClient'
import TradingSymbols from 'tradingSymbols'

export type Market = {
  id: string
  baseSymbol: TradingSymbol
  quoteSymbol: TradingSymbol
}

export default class Markets {
  private readonly markets: Market[]
  private marketById: Map<string, Market>

  constructor(markets: ApiMarket[], symbols: TradingSymbols) {
    this.markets = markets.map((m) => {
      return {
        id: m.id,
        baseSymbol: symbols.getByName(m.baseSymbol),
        quoteSymbol: symbols.getByName(m.quoteSymbol)
      }
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
