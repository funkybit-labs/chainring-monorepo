import { Address, formatUnits } from 'viem'
import TradingSymbols from 'tradingSymbols'
import React, { useCallback, useMemo, useState } from 'react'
import { Balance, TradingSymbol } from 'apiClient'
import { useQueryClient } from '@tanstack/react-query'
import { useWebsocketSubscription } from 'contexts/websocket'
import { balancesTopic, Publishable } from 'websocketMessages'
import SymbolIcon from 'components/common/SymbolIcon'
import { Button } from 'components/common/Button'
import DepositModal from 'components/Screens/HomeScreen/DepositModal'
import WithdrawalModal from 'components/Screens/HomeScreen/WithdrawalModal'
import {
  depositsQueryKey,
  withdrawalsQueryKey
} from 'components/Screens/HomeScreen/balances/BalancesWidget'

export function BalancesTable({
  walletAddress,
  exchangeContractAddress,
  symbols
}: {
  walletAddress: Address
  exchangeContractAddress: Address
  symbols: TradingSymbols
}) {
  const [depositSymbol, setDepositSymbol] = useState<TradingSymbol | null>(null)
  const [showDepositModal, setShowDepositModal] = useState<boolean>(false)

  const [withdrawSymbol, setWithdrawSymbol] = useState<TradingSymbol | null>(
    null
  )
  const [showWithdrawalModal, setShowWithdrawalModal] = useState<boolean>(false)

  const queryClient = useQueryClient()
  const [balances, setBalances] = useState<Balance[]>(() => [])

  useWebsocketSubscription({
    topics: useMemo(() => [balancesTopic], []),
    handler: useCallback(
      (message: Publishable) => {
        if (message.type === 'Balances') {
          setBalances(message.balances)
          queryClient.invalidateQueries({ queryKey: withdrawalsQueryKey })
          queryClient.invalidateQueries({ queryKey: depositsQueryKey })
        }
      },
      [queryClient]
    )
  })

  function openDepositModal(symbol: TradingSymbol) {
    setDepositSymbol(symbol)
    setShowDepositModal(true)
  }

  function openWithdrawModal(symbol: TradingSymbol) {
    setWithdrawSymbol(symbol)
    setShowWithdrawalModal(true)
  }

  return (
    <div className="h-64 overflow-scroll">
      <table className="relative w-full text-left text-sm">
        <thead className="sticky top-0 bg-black">
          <tr key="header">
            <th className="min-w-32 pb-1">Asset</th>
            <th className="min-w-32 pb-1">Balance</th>
            <th className="pb-1"></th>
            <th className="pb-1"></th>
          </tr>
          <tr key="header-divider">
            <th className="h-px bg-lightBackground p-0"></th>
            <th className="h-px bg-lightBackground p-0"></th>
            <th className="h-px bg-lightBackground p-0"></th>
            <th className="h-px bg-lightBackground p-0"></th>
          </tr>
        </thead>
        <tbody>
          {[symbols.native].concat(symbols.erc20).map((symbol) => {
            const balance = balances.find(
              (balance) => balance.symbol == symbol.name
            ) || { symbol: symbol.name, total: 0n, available: 0n }
            return (
              <tr key={symbol.name}>
                <td className="mr-2 whitespace-nowrap pt-2 align-text-top">
                  <SymbolIcon
                    symbol={symbol}
                    className="mr-2 inline-block size-6"
                  />
                  {symbol.name}
                </td>
                <td className="w-full pt-2 text-left align-text-top">
                  {formatUnits(balance.available, symbol.decimals)}
                </td>
                <td className="pr-1 pt-2 text-xs">
                  <Button
                    caption={() => <>Deposit</>}
                    onClick={() => openDepositModal(symbol)}
                    disabled={false}
                  />
                </td>
                <td className="pt-2 text-xs">
                  <Button
                    caption={() => <>Withdraw</>}
                    onClick={() => openWithdrawModal(symbol)}
                    disabled={balance.available === 0n}
                  />
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>

      {depositSymbol && (
        <DepositModal
          isOpen={showDepositModal}
          exchangeContractAddress={exchangeContractAddress}
          walletAddress={walletAddress}
          symbol={depositSymbol}
          close={() => setShowDepositModal(false)}
          onClosed={() => setDepositSymbol(null)}
        />
      )}

      {withdrawSymbol && (
        <WithdrawalModal
          isOpen={showWithdrawalModal}
          exchangeContractAddress={exchangeContractAddress}
          walletAddress={walletAddress}
          symbol={withdrawSymbol}
          close={() => setShowWithdrawalModal(false)}
          onClosed={() => setWithdrawSymbol(null)}
        />
      )}
    </div>
  )
}
