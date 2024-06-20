import { Address, formatUnits } from 'viem'
import TradingSymbols from 'tradingSymbols'
import React, {
  Fragment,
  useCallback,
  useEffect,
  useMemo,
  useState
} from 'react'
import { Balance } from 'apiClient'
import { useQueryClient } from '@tanstack/react-query'
import { useWebsocketSubscription } from 'contexts/websocket'
import { balancesTopic, Publishable } from 'websocketMessages'
import { Button } from 'components/common/Button'
import DepositModal from 'components/Screens/HomeScreen/DepositModal'
import WithdrawalModal from 'components/Screens/HomeScreen/WithdrawalModal'
import {
  depositsQueryKey,
  withdrawalsQueryKey
} from 'components/Screens/HomeScreen/balances/BalancesWidget'
import Deposit from 'assets/Deposit.svg'
import Withdrawal from 'assets/Withdrawal.svg'
import { useConfig, useSwitchChain } from 'wagmi'
import { allChains } from 'wagmiConfig'
import TradingSymbol from 'tradingSymbol'
import { SymbolAndChain } from 'components/common/SymbolAndChain'
import { ExpandableValue } from 'components/common/ExpandableValue'

export function BalancesTable({
  walletAddress,
  exchangeContractAddress,
  symbols
}: {
  walletAddress?: Address
  exchangeContractAddress?: Address
  symbols: TradingSymbols
}) {
  const config = useConfig()
  const { switchChain } = useSwitchChain()

  const [depositSymbol, setDepositSymbol] = useState<TradingSymbol | null>(null)
  const [showDepositModal, setShowDepositModal] = useState<boolean>(false)

  const [withdrawSymbol, setWithdrawSymbol] = useState<TradingSymbol | null>(
    null
  )

  const [switchToChainId, setSwitchToChainId] = useState<number | null>(null)
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

  useEffect(() => {
    if (switchToChainId) {
      const chain = allChains.find((c) => c.id == switchToChainId)
      chain &&
        switchChain({
          addEthereumChainParameter: {
            chainName: chain.name,
            nativeCurrency: chain.nativeCurrency,
            rpcUrls: chain.rpcUrls.default.http,
            blockExplorerUrls: chain.blockExplorers
              ? [chain.blockExplorers.default.url]
              : undefined
          },
          chainId: chain.id
        })
    }
    setSwitchToChainId(null)
  }, [switchToChainId, switchChain])

  function openDepositModal(symbol: TradingSymbol) {
    setWithdrawSymbol(null)
    setDepositSymbol(symbol)
    setShowDepositModal(true)
    if (symbol.chainId != config.state.chainId) {
      setSwitchToChainId(symbol.chainId)
    }
  }

  function openWithdrawModal(symbol: TradingSymbol) {
    setDepositSymbol(null)
    setWithdrawSymbol(symbol)
    setShowWithdrawalModal(true)
    if (symbol.chainId != config.state.chainId) {
      setSwitchToChainId(symbol.chainId)
    }
  }

  return (
    <>
      <div className="grid max-h-72 auto-rows-max grid-cols-[max-content_1fr_max-content] overflow-y-scroll">
        {symbols.native.concat(symbols.erc20).map((symbol) => {
          const balance = balances.find(
            (balance) => balance.symbol == symbol.name
          ) || { symbol: symbol.name, total: 0n, available: 0n }
          return (
            <Fragment key={symbol.name}>
              <div className="mb-4 inline-block whitespace-nowrap align-text-top">
                <SymbolAndChain symbol={symbol} />
              </div>
              <div className="mb-4 inline-block w-full text-center align-text-top">
                <ExpandableValue
                  value={formatUnits(balance.available, symbol.decimals)}
                />
              </div>
              <div className="mb-4 inline-block space-x-4 justify-self-end text-xs">
                <Button
                  style={'normal'}
                  width={'normal'}
                  caption={() => (
                    <span className="whitespace-nowrap">
                      <span className="mr-4 hidden narrow:inline">Deposit</span>
                      <img className="inline" src={Deposit} alt={'Deposit'} />
                    </span>
                  )}
                  onClick={() => openDepositModal(symbol)}
                  disabled={false}
                />
                <Button
                  style={'normal'}
                  width={'normal'}
                  caption={() => (
                    <span className="whitespace-nowrap">
                      <span className="mr-4 hidden narrow:inline">
                        Withdraw
                      </span>
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

      {depositSymbol && depositSymbol.chainId == config.state.chainId && (
        <DepositModal
          isOpen={showDepositModal}
          exchangeContractAddress={exchangeContractAddress!}
          walletAddress={walletAddress!}
          symbol={depositSymbol}
          close={() => setShowDepositModal(false)}
          onClosed={() => setDepositSymbol(null)}
        />
      )}

      {withdrawSymbol && withdrawSymbol.chainId == config.state.chainId && (
        <WithdrawalModal
          isOpen={showWithdrawalModal}
          exchangeContractAddress={exchangeContractAddress!}
          walletAddress={walletAddress!}
          symbol={withdrawSymbol}
          close={() => setShowWithdrawalModal(false)}
          onClosed={() => setWithdrawSymbol(null)}
        />
      )}
    </>
  )
}
