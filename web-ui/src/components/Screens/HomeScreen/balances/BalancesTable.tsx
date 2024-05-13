import { Address, formatUnits } from 'viem'
import TradingSymbols from 'tradingSymbols'
import React, { Fragment, useCallback, useMemo, useState } from 'react'
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
import Deposit from 'assets/Deposit.svg'
import Withdrawal from 'assets/Withdrawal.svg'
import Add from 'assets/Add.svg'

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
    <>
      <div className="grid max-h-72 auto-rows-max grid-cols-[max-content_3fr_1fr_1fr] overflow-scroll">
        {[symbols.native].concat(symbols.erc20).map((symbol) => {
          const balance = balances.find(
            (balance) => balance.symbol == symbol.name
          ) || { symbol: symbol.name, total: 0n, available: 0n }
          return (
            <Fragment key={symbol.name}>
              <div className="mb-4 inline-block whitespace-nowrap align-text-top">
                <SymbolIcon
                  symbol={symbol}
                  className="mr-2 inline-block size-6"
                />
                {symbol.name}
              </div>
              <div className="mb-4 inline-block w-full text-center align-text-top">
                {formatUnits(balance.available, symbol.decimals)}
              </div>
              <div className="mb-4 mr-4 inline-block text-xs">
                <Button
                  caption={() => (
                    <span className="whitespace-nowrap">
                      Deposit{' '}
                      <img className="inline" src={Deposit} alt={'Deposit'} />
                    </span>
                  )}
                  onClick={() => openDepositModal(symbol)}
                  disabled={false}
                />
              </div>
              <div className="mb-4 inline-block text-xs">
                <Button
                  caption={() => (
                    <span className="whitespace-nowrap">
                      Withdraw{' '}
                      <img
                        className="inline"
                        src={Withdrawal}
                        alt={'Withdrawal'}
                      />
                    </span>
                  )}
                  onClick={() => openWithdrawModal(symbol)}
                  disabled={balance.available === 0n}
                />
              </div>
            </Fragment>
          )
        })}
      </div>

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
    </>
  )
}
