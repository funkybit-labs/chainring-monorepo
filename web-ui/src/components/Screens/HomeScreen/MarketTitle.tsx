import { Market } from 'markets'
import { classNames } from 'utils'
import { OrderSide } from 'apiClient'
import { SymbolAndChain } from 'components/common/SymbolAndChain'

export function MarketTitle({
  market,
  side,
  alwaysShowLabel
}: {
  market: Market
  side: OrderSide
  alwaysShowLabel: boolean
}) {
  const [sellSymbol, buySymbol] =
    side === 'Buy'
      ? [market.quoteSymbol, market.baseSymbol]
      : [market.baseSymbol, market.quoteSymbol]
  return (
    <div
      className={classNames(
        'flex place-items-center truncate',
        alwaysShowLabel || 'justify-center narrow:justify-normal'
      )}
    >
      <div className="mr-4 flex items-center">
        Sell:
        <SymbolAndChain symbol={sellSymbol} />
      </div>
      <div className="flex items-center">
        Buy:
        <SymbolAndChain symbol={buySymbol} />
      </div>
    </div>
  )
}
