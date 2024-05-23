import { Withdrawal } from 'apiClient'
import TradingSymbols from 'tradingSymbols'
import { format } from 'date-fns'
import SymbolIcon from 'components/common/SymbolIcon'
import { formatUnits } from 'viem'
import React, { Fragment } from 'react'
import { Status } from 'components/common/Status'

export function WithdrawalsTable({
  withdrawals,
  symbols
}: {
  withdrawals: Withdrawal[]
  symbols: TradingSymbols
}) {
  return (
    <div className="grid max-h-72 auto-rows-max grid-cols-[max-content_max-content_1fr_max-content] items-center overflow-scroll">
      {withdrawals.map((withdrawal) => {
        const symbol = symbols.getByName(withdrawal.symbol)

        return (
          <Fragment key={withdrawal.id}>
            <div className="mb-4 ml-4 mr-8 inline-block align-text-top text-sm">
              <span className="mr-2 text-lightBluishGray5">
                {format(withdrawal.createdAt, 'MM/dd')}
              </span>
              <span className="text-white">
                {format(withdrawal.createdAt, 'HH:mm:ss a')}
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
              {formatUnits(withdrawal.amount, symbol.decimals)}
            </div>
            <div className="mb-4 mr-4 inline-block text-center align-text-top text-sm">
              <Status status={withdrawal.status} />
            </div>
          </Fragment>
        )
      })}
      {withdrawals.length === 0 && (
        <div className="col-span-4 w-full text-center">No withdrawals yet</div>
      )}
    </div>
  )
}
