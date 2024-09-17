import { formatUnits } from 'viem'
import TradingSymbols from 'tradingSymbols'
import React, {
  Fragment,
  useCallback,
  useEffect,
  useMemo,
  useState
} from 'react'
import { AccountConfigurationApiResponse, Balance } from 'apiClient'
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
import { useConfig } from 'wagmi'
import TradingSymbol from 'tradingSymbol'
import { SymbolAndChain } from 'components/common/SymbolAndChain'
import { ExpandableValue } from 'components/common/ExpandableValue'
import { useWallets } from 'contexts/walletProvider'
import { ConnectWallet } from 'components/Screens/HomeScreen/swap/ConnectWallet'
import { useSwitchToEthChain } from 'utils/switchToEthChain'

export function BalancesTable({
  walletAddress,
  exchangeContractAddress,
  symbols,
  accountConfig
}: {
  walletAddress?: string
  exchangeContractAddress?: string
  symbols: TradingSymbols
  accountConfig?: AccountConfigurationApiResponse
}) {
  const wallets = useWallets()
  const evmConfig = useConfig()

  const [depositSymbol, setDepositSymbol] = useState<TradingSymbol | null>(null)
  const [showDepositModal, setShowDepositModal] = useState<boolean>(false)

  const [withdrawSymbol, setWithdrawSymbol] = useState<TradingSymbol | null>(
    null
  )

  const switchToEthChain = useSwitchToEthChain()
  const [switchToEthChainId, setSwitchToEthChainId] = useState<number | null>(
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

  useEffect(() => {
    if (switchToEthChainId) {
      switchToEthChain(switchToEthChainId)
    }
    setSwitchToEthChainId(null)
  }, [switchToEthChainId, switchToEthChain])

  function openDepositModal(symbol: TradingSymbol) {
    if (symbol.networkType === 'Bitcoin') {
      alert('Not implemented')
    } else if (symbol.networkType === 'Evm') {
      setWithdrawSymbol(null)
      setDepositSymbol(symbol)
      setShowDepositModal(true)
      if (symbol.chainId != evmConfig.state.chainId) {
        setSwitchToEthChainId(symbol.chainId)
      }
    }
  }

  function openWithdrawModal(symbol: TradingSymbol) {
    setDepositSymbol(null)
    setWithdrawSymbol(symbol)
    setShowWithdrawalModal(true)
    if (symbol.chainId != evmConfig.state.chainId) {
      setSwitchToEthChainId(symbol.chainId)
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
              <div className="mb-4 inline-block space-x-4 text-xs">
                {wallets.isConnected(symbol.networkType) ? (
                  <>
                    <Button
                      style={'normal'}
                      width={'normal'}
                      caption={() => (
                        <span className="whitespace-nowrap">
                          <span className="mr-4 hidden narrow:inline">
                            Deposit
                          </span>
                          <img
                            className="inline"
                            src={Deposit}
                            alt={'Deposit'}
                          />
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
                  </>
                ) : (
                  <ConnectWallet
                    onSwitchToChain={(chainId) => switchToEthChain(chainId)}
                  />
                )}
              </div>
            </Fragment>
          )
        })}
      </div>

      {depositSymbol && depositSymbol.chainId == evmConfig.state.chainId && (
        <DepositModal
          isOpen={showDepositModal}
          exchangeContractAddress={exchangeContractAddress!}
          walletAddress={walletAddress!}
          symbol={depositSymbol}
          testnetChallengeDepositLimit={
            accountConfig?.testnetChallengeDepositLimits?.find(
              (dl) => dl.symbol == depositSymbol.name
            )?.limit
          }
          close={() => setShowDepositModal(false)}
          onClosed={() => setDepositSymbol(null)}
        />
      )}

      {withdrawSymbol && withdrawSymbol.chainId == evmConfig.state.chainId && (
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
