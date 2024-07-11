import React from 'react'
import { ExpandableValue } from 'components/common/ExpandableValue'
import TradingSymbol from 'tradingSymbol'
import { SymbolAndChain } from 'components/common/SymbolAndChain'

export default function MarketPrice({
  bottomSymbol,
  topSymbol,
  isInverted,
  price,
  onClick
}: {
  bottomSymbol: TradingSymbol
  topSymbol: TradingSymbol
  isInverted: boolean
  price: string
  onClick: () => void
}) {
  return (
    <div
      className="mt-2 flex cursor-pointer flex-row items-center gap-2 pl-4 text-sm text-darkBluishGray1 hover:text-statusOrange"
      onClick={onClick}
    >
      1
      {isInverted ? (
        <SymbolAndChain symbol={topSymbol} noIcon={true} />
      ) : (
        <SymbolAndChain symbol={bottomSymbol} noIcon={true} />
      )}
      ≈ <ExpandableValue value={price} />
      {isInverted ? (
        <SymbolAndChain symbol={bottomSymbol} noIcon={true} />
      ) : (
        <SymbolAndChain symbol={topSymbol} noIcon={true} />
      )}
    </div>
  )
}
