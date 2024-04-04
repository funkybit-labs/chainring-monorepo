import { TradingSymbol } from 'ApiClient'

export default class TradingSymbols {
  native: TradingSymbol
  erc20: TradingSymbol[]
  private symbolByName: Map<string, TradingSymbol>

  constructor(symbols: TradingSymbol[]) {
    this.native = symbols.find((s) => s.contractAddress === null)!
    this.erc20 = symbols.filter((s) => s.contractAddress !== null)
    this.symbolByName = new Map(symbols.map((s) => [s.name, s]))
  }

  getByName(name: string): TradingSymbol {
    return this.findByName(name)!
  }

  findByName(name: string): TradingSymbol | undefined {
    return this.symbolByName.get(name)
  }
}
