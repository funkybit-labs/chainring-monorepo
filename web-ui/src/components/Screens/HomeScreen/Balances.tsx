import { Address, formatUnits } from 'viem'
import { Balance, TradingSymbol } from 'apiClient'
import React, { useState } from 'react'
import DepositModal from 'components/Screens/HomeScreen/Balances/DepositModal'
import WithdrawalModal from 'components/Screens/HomeScreen/Balances/WithdrawalModal'
import { Widget } from 'components/common/Widget'
import { Button } from 'components/common/Button'
import SymbolIcon from 'components/common/SymbolIcon'
import TradingSymbols from 'tradingSymbols'
import { useWebsocketSubscription } from 'contexts/websocket'
import { balancesTopic, Publishable } from 'websocketMessages'

export default function Balances({
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
    topic: balancesTopic,
    handler: (message: Publishable) => {
      if (message.type === 'Balances') {
        setBalances(message.balances)
      }
    }
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
      <table>
        <tbody>
          {[symbols.native].concat(symbols.erc20).map((symbol) => {
            const balance = balances.find(
              (balance) => balance.symbol == symbol.name
            )
            return (
              <tr key={symbol.name}>
                <td className="min-w-12 whitespace-nowrap pr-2">
                  <SymbolIcon
                    symbol={symbol}
                    className="mr-2 inline-block size-6"
                  />
                  {symbol.name}
                </td>
                <td className="min-w-12 px-4 text-left">
                  {balance
                    ? formatUnits(
                        balance?.available,
                        symbols.getByName(balance!.symbol).decimals
                      )
                    : '0'}
                </td>
                <td className="px-2 py-1">
                  <Button
                    caption={() => <>Deposit</>}
                    onClick={() => openDepositModal(symbol)}
                    disabled={false}
                  />
                </td>
                <td className="py-1 pl-2">
                  <Button
                    caption={() => <>Withdraw</>}
                    onClick={() => openWithdrawModal(symbol)}
                    disabled={balance?.available === 0n}
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
    </>
  )
}
