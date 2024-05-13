import { Deposit } from 'apiClient'
import TradingSymbols from 'tradingSymbols'
import { format } from 'date-fns'
import SymbolIcon from 'components/common/SymbolIcon'
import { formatUnits } from 'viem'
import React, { Fragment } from 'react'
import { Status } from 'components/common/Status'

export function DepositsTable({
  deposits,
  symbols
}: {
  deposits: Deposit[]
  symbols: TradingSymbols
}) {
  return (
    <div className="grid max-h-72 auto-rows-max grid-cols-[max-content_max-content_1fr_max-content] overflow-scroll">
      {deposits.map((deposit) => {
        const symbol = symbols.getByName(deposit.symbol)

        return (
          <Fragment key={deposit.id}>
            <div className="mb-4 ml-4 mr-8 inline-block align-text-top text-sm">
              <span className="mr-2 text-lightBluishGray5">
                {format(deposit.createdAt, 'MM/dd')}
              </span>
              <span className="text-white">
                {format(deposit.createdAt, 'HH:mm:ss a')}
              </span>
            </div>
            <div className="mb-4 mr-4 inline-block whitespace-nowrap align-text-top text-sm">
              <SymbolIcon
                symbol={symbol}
                className="mr-2 inline-block size-6"
              />
              {symbol.name}
            </div>
            <div className="mb-4 inline-block w-full text-center align-text-top text-sm">
              {formatUnits(deposit.amount, symbol.decimals)}
            </div>
            <div className="mb-4 mr-4 inline-block text-center align-text-top text-sm">
              <Status status={deposit.status} />
            </div>
          </Fragment>
        )
      })}
      {deposits.length === 0 && (
        <div className="col-span-4 text-center">No deposits yet</div>
      )}
    </div>
  )
}
