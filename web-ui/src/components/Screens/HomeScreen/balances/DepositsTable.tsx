import { Deposit } from 'apiClient'
import TradingSymbols from 'tradingSymbols'
import { format } from 'date-fns'
import SymbolIcon from 'components/common/SymbolIcon'
import { formatUnits } from 'viem'
import React from 'react'

export function DepositsTable({
  deposits,
  symbols
}: {
  deposits: Deposit[]
  symbols: TradingSymbols
}) {
  return (
    <div className="h-64 overflow-scroll">
      <table className="relative w-full text-left text-sm">
        <thead className="sticky top-0 bg-black">
          <tr key="header">
            <th className="min-w-24 pb-1">Date</th>
            <th className="min-w-24 pb-1">Asset</th>
            <th className="min-w-24 pb-1">Amount</th>
            <th className="min-w-24 pb-1">Status</th>
          </tr>
          <tr key="header-divider">
            <th className="h-px bg-lightBackground p-0"></th>
            <th className="h-px bg-lightBackground p-0"></th>
            <th className="h-px bg-lightBackground p-0"></th>
            <th className="h-px bg-lightBackground p-0"></th>
          </tr>
        </thead>
        <tbody>
          {deposits.map((withdrawal) => {
            const symbol = symbols.getByName(withdrawal.symbol)

            return (
              <tr key={withdrawal.id}>
                <td className="pt-2 align-text-top text-sm">
                  {format(withdrawal.createdAt, 'y/MM/dd HH:mm:ss')}
                </td>
                <td className="mr-2 whitespace-nowrap pt-2 align-text-top text-sm">
                  <SymbolIcon
                    symbol={symbol}
                    className="mr-2 inline-block size-6"
                  />
                  {symbol.name}
                </td>
                <td className="w-full pt-2 text-left align-text-top text-sm">
                  {formatUnits(withdrawal.amount, symbol.decimals)}
                </td>
                <td className="pr-1 pt-2 align-text-top text-sm">
                  {withdrawal.status}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
      {deposits.length === 0 && (
        <div className="mt-12 text-center">No deposits yet</div>
      )}
    </div>
  )
}
