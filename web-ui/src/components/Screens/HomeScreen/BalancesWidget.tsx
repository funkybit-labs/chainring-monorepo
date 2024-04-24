import { Address, formatUnits } from 'viem'
import { Balance, TradingSymbol } from 'apiClient'
import React, { Fragment, useCallback, useMemo, useState } from 'react'
import DepositModal from 'components/Screens/HomeScreen/DepositModal'
import WithdrawalModal from 'components/Screens/HomeScreen/WithdrawalModal'
import { Widget } from 'components/common/Widget'
import { Button } from 'components/common/Button'
import SymbolIcon from 'components/common/SymbolIcon'
import TradingSymbols from 'tradingSymbols'
import { useWebsocketSubscription } from 'contexts/websocket'
import { balancesTopic, Publishable } from 'websocketMessages'

export default function BalancesWidget({
  walletAddress,
  exchangeContractAddress,
  symbols
}: {
  walletAddress: Address
  exchangeContractAddress: Address
  symbols: TradingSymbols
}) {
  return (
    <Widget
      title={'Balances'}
      contents={
        <BalancesTable
          walletAddress={walletAddress}
          exchangeContractAddress={exchangeContractAddress}
          symbols={symbols}
        />
      }
    />
  )
}

function BalancesTable({
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

  const [balances, setBalances] = useState<Balance[]>(() => [])
  useWebsocketSubscription({
    topics: useMemo(() => [balancesTopic], []),
    handler: useCallback((message: Publishable) => {
      if (message.type === 'Balances') {
        setBalances(message.balances)
      }
    }, [])
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
      <div className="grid grid-cols-[min-content_max-content_1fr_1fr]">
        {[symbols.native].concat(symbols.erc20).map((symbol) => {
          const balance = balances.find(
            (balance) => balance.symbol == symbol.name
          )
          return (
            <Fragment key={symbol.name}>
              <div className="my-2 mr-2 flex items-center whitespace-nowrap pr-2">
                <SymbolIcon
                  symbol={symbol}
                  className="mr-2 inline-block size-6"
                />
                {symbol.name}
              </div>
              <div className="m-2 px-2 text-left">
                {balance
                  ? formatUnits(
                      balance?.available,
                      symbols.getByName(balance!.symbol).decimals
                    )
                  : '0'}
              </div>
              <Button
                caption={() => <>Deposit</>}
                onClick={() => openDepositModal(symbol)}
                disabled={false}
                narrow={true}
              />
              <Button
                caption={() => <>Withdraw</>}
                onClick={() => openWithdrawModal(symbol)}
                disabled={balance?.available === 0n}
                narrow={true}
              />
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
