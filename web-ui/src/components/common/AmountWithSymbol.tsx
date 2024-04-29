import { TradingSymbol } from 'apiClient'
import { formatUnits } from 'viem'
import React from 'react'

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
      {formatUnits(amount, symbol.decimals)} {symbol.name}
    </div>
  )
}
