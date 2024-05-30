import { formatUnits } from 'viem'
import React from 'react'
import TradingSymbol from 'tradingSymbol'

export function AmountWithSymbol({
  amount,
  symbol,
  approximate
}: {
  amount: bigint
  symbol: TradingSymbol
  approximate: boolean
}) {
  return (
    <div className={'inline-block whitespace-nowrap'}>
      {approximate && '~'}
      <span className="inline-block max-w-[10ch] overflow-x-clip text-ellipsis hover:max-w-full">
        {formatUnits(amount, symbol.decimals)}
      </span>{' '}
      {symbol.name}
    </div>
  )
}
