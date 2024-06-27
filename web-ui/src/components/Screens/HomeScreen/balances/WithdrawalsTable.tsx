import { Chain, Withdrawal } from 'apiClient'
import TradingSymbols from 'tradingSymbols'
import { format } from 'date-fns'
import { formatUnits } from 'viem'
import React, { Fragment } from 'react'
import { Status } from 'components/common/Status'
import { SymbolAndChain } from 'components/common/SymbolAndChain'
import { ExpandableValue } from 'components/common/ExpandableValue'
import { TxHashDisplay } from 'components/common/TxHashDisplay'
import { classNames } from 'utils'

export function WithdrawalsTable({
  withdrawals,
  symbols,
  chains
}: {
  withdrawals: Withdrawal[]
  symbols: TradingSymbols
  chains: Chain[]
}) {
  return (
    <>
      <div className="max-h-72 min-h-24 overflow-scroll">
        <table className="relative w-full text-left text-sm">
          <thead className="sticky top-0 z-10 bg-darkBluishGray9 font-normal text-darkBluishGray2">
            <tr key="header">
              <td className="pl-4">Date</td>
              <td className="pl-4">Token</td>
              <td className="hidden pl-4 narrow:table-cell">Amount</td>
              <td className="hidden pl-4 narrow:table-cell">Fee</td>
              <td className="table-cell pl-4 narrow:hidden">
                Amount
                <br />
                Fee
              </td>
              <td className="pl-4">Tx Hash</td>
              <td className="pl-4">Status</td>
            </tr>
          </thead>
          <tbody>
            {withdrawals.map((withdrawal) => {
              const symbol = symbols.getByName(withdrawal.symbol)

              return (
                <tr
                  key={withdrawal.id}
                  className={classNames(
                    'duration-200 ease-in-out hover:cursor-default hover:bg-darkBluishGray6'
                  )}
                >
                  <td className="h-12 rounded-l pl-4">
                    <span className="mr-2 inline-block text-lightBluishGray5">
                      {format(withdrawal.createdAt, 'MM/dd')}
                    </span>
                    <span className="inline-block whitespace-nowrap text-white">
                      {format(withdrawal.createdAt, 'HH:mm:ss a')}
                    </span>
                  </td>
                  <td className="pl-4">
                    <SymbolAndChain symbol={symbol} />
                  </td>
                  <td className="hidden pl-4 narrow:table-cell">
                    <ExpandableValue
                      value={formatUnits(
                        withdrawal.amount - withdrawal.fee,
                        symbol.decimals
                      )}
                    />
                  </td>
                  <td className="hidden pl-4 narrow:table-cell">
                    <ExpandableValue
                      value={formatUnits(withdrawal.fee, symbol.decimals)}
                    />
                  </td>
                  <td className="table-cell pl-4 narrow:hidden">
                    <ExpandableValue
                      value={formatUnits(
                        withdrawal.amount - withdrawal.fee,
                        symbol.decimals
                      )}
                    />
                    <br />
                    <ExpandableValue
                      value={formatUnits(withdrawal.fee, symbol.decimals)}
                    />
                  </td>
                  <td className="pl-4">
                    {withdrawal.txHash && (
                      <TxHashDisplay
                        txHash={withdrawal.txHash}
                        blockExplorerUrl={
                          chains.find((chain) => chain.id == symbol.chainId)
                            ?.blockExplorerUrl
                        }
                      />
                    )}
                  </td>
                  <td className="pl-4 text-center">
                    <Status status={withdrawal.status} />
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </>
  )
}
