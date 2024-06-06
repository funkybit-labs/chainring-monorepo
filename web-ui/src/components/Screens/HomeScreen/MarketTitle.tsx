import { Market } from 'markets'
import { classNames } from 'utils'
import { OrderSide } from 'apiClient'
import { SymbolAndChain } from 'components/common/SymbolAndChain'
import { useState } from 'react'

export function MarketTitle({
  market,
  side,
  alwaysShowLabel,
  onSideChange
}: {
  market: Market
  side: OrderSide
  alwaysShowLabel: boolean
  onSideChange: (s: OrderSide) => void
}) {
  const [animateSide, setAnimateSide] = useState(false)
  function onChangedSide(s: OrderSide) {
    onSideChange(s)
    setAnimateSide(true)
    setTimeout(() => {
      setAnimateSide(false)
    }, 1000)
  }
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
      <div
        className={classNames(
          animateSide && 'animate-swivel',
          'block cursor-pointer mr-4 size-8 bg-switch'
        )}
        onClick={() => {
          onChangedSide(side === 'Buy' ? 'Sell' : 'Buy')
        }}
      />
      <div className="flex items-center">
        Buy:
        <SymbolAndChain symbol={buySymbol} />
      </div>
    </div>
  )
}
