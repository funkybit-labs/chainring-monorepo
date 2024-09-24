import {
  AccountConfigurationApiResponse,
  AddressType,
  apiClient,
  Balance,
  Chain
} from 'apiClient'
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import TradingSymbol from 'tradingSymbol'
import { useSwitchToEthChain } from 'utils/switchToEthChain'
import { useConfig } from 'wagmi'
import TradingSymbols from 'tradingSymbols'
import { ConnectedWallet, useWallets } from 'contexts/walletProvider'
import ZeppelinSvg from 'assets/zeppelin.svg'
import PointRightSvg from 'assets/point-right.svg'
import StopHandSvg from 'assets/stop-hand.svg'
import ClockSvg from 'assets/clock.svg'
import DiscoBall from 'components/Screens/HomeScreen/DiscoBall'
import DepositModal from 'components/Screens/HomeScreen/DepositModal'
import { Leaderboard } from 'components/Screens/HomeScreen/testnetchallenge/Leaderboard'
import { Tab, Widget } from 'components/Screens/Header'
import { accountConfigQueryKey } from 'components/Screens/HomeScreen'
import { useWebsocketSubscription } from 'contexts/websocket'
import { balancesTopic, Publishable } from 'websocketMessages'
import WithdrawalModal from 'components/Screens/HomeScreen/WithdrawalModal'

export const testnetChallengeInviteCodeKey = 'testnetChallengeInviteCode'
export function TestnetChallengeTab({
  chains,
  symbols,
  exchangeContract,
  accountConfig,
  onChangeTab
}: {
  chains: Chain[]
  symbols: TradingSymbols
  exchangeContract?: { name: string; address: string }
  accountConfig?: AccountConfigurationApiResponse
  onChangeTab: (tab: Tab, widget?: Widget) => void
}) {
  const evmConfig = useConfig()
  const queryClient = useQueryClient()
  const wallets = useWallets()

  const [balances, setBalances] = useState<Balance[]>(() => [])
  useWebsocketSubscription({
    topics: useMemo(() => [balancesTopic], []),
    handler: useCallback(
      (message: Publishable) => {
        if (message.type === 'Balances') {
          setBalances(message.balances)
          queryClient.invalidateQueries({ queryKey: accountConfigQueryKey })
        }
      },
      [queryClient]
    )
  })
  const [showWithdrawalModal, setShowWithdrawalModal] = useState(false)
  const [withdrawalWallet, setWithdrawalWallet] = useState<ConnectedWallet>()
  const [withdrawalSymbol, setWithdrawalSymbol] = useState<TradingSymbol>()
  const [withdrawalExchangeContract, setWithdrawalExchangeContract] =
    useState<AddressType>()

  const [
    showTestnetChallengeDepositModal,
    setShowTestnetChallengeDepositModal
  ] = useState(false)
  const [testnetChallengeDepositSymbol, setTestnetChallengeDepositSymbol] =
    useState<TradingSymbol>()
  const [testnetChallengeDepositContract, setTestnetChallengeDepositContract] =
    useState<AddressType>()

  const switchToEthChain = useSwitchToEthChain()
  const [switchToEthChainId, setSwitchToEthChainId] = useState<number | null>(
    null
  )

  useEffect(() => {
    if (switchToEthChainId) {
      switchToEthChain(switchToEthChainId)
    }
    setSwitchToEthChainId(null)
  }, [switchToEthChainId, switchToEthChain])

  const testnetChallengeEnrollMutation = useMutation({
    mutationFn: async () => {
      await apiClient.testnetChallengeEnroll({
        inviteCode: localStorage.getItem(testnetChallengeInviteCodeKey)
      })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: accountConfigQueryKey })
      localStorage.removeItem(testnetChallengeInviteCodeKey)
    }
  })

  const {
    testnetChallengeStatus,
    nickName,
    avatarUrl,
    inviteCode,
    pointsBalance
  } = useMemo(() => {
    if (accountConfig) {
      return {
        testnetChallengeStatus: accountConfig.testnetChallengeStatus,
        nickName: accountConfig.nickName ?? undefined,
        avatarUrl: accountConfig.avatarUrl ?? undefined,
        inviteCode: accountConfig.inviteCode,
        pointsBalance: accountConfig.pointsBalance
      }
    }
    return {}
  }, [accountConfig])

  const accountRefreshRef = useRef<NodeJS.Timeout | null>(null)

  useEffect(() => {
    if (
      testnetChallengeStatus === 'Enrolled' ||
      testnetChallengeStatus === 'Disqualified'
    ) {
      if (accountRefreshRef.current) clearInterval(accountRefreshRef.current)
    } else {
      accountRefreshRef.current = setInterval(() => {
        queryClient.invalidateQueries({
          queryKey: accountConfigQueryKey,
          fetchStatus: 'idle'
        })
      }, 3000)
    }
    return () => {
      if (accountRefreshRef.current) clearInterval(accountRefreshRef.current)
    }
  }, [testnetChallengeStatus, queryClient])

  const triggerDepositModal = useCallback(() => {
    const symbolName = accountConfig?.testnetChallengeDepositSymbol
    const symbolContract = accountConfig?.testnetChallengeDepositContract
    if (symbolName && symbolContract) {
      const symbol = symbols?.findByName(symbolName)
      if (symbol) {
        setTestnetChallengeDepositSymbol(symbol)
        setTestnetChallengeDepositContract(symbolContract)
        if (symbol.chainId != evmConfig.state.chainId) {
          setSwitchToEthChainId(symbol.chainId)
        }
        setShowTestnetChallengeDepositModal(true)
      }
    }
  }, [accountConfig, evmConfig.state.chainId, symbols])

  const [autoDepositTriggered, setAutoDepositTriggered] = useState(false)

  const [enrollWhenConnected, setEnrollWhenConnected] = useState(false)

  useEffect(() => {
    switch (testnetChallengeStatus) {
      case 'Unenrolled':
        if (enrollWhenConnected) {
          testnetChallengeEnrollMutation.mutate()
          setEnrollWhenConnected(false)
        }
        break
      case 'PendingDeposit':
        if (!autoDepositTriggered) triggerDepositModal()
        setAutoDepositTriggered(true)
        break
    }
  }, [
    testnetChallengeStatus,
    triggerDepositModal,
    autoDepositTriggered,
    enrollWhenConnected,
    testnetChallengeEnrollMutation
  ])

  // once the wallet is connected, invalidate the accountConfigQuery if it becomes unconnected
  const [walletHasConnected, setWalletHasConnected] = useState(false)
  useEffect(() => {
    if (wallets.connected.length === 0) {
      if (walletHasConnected) {
        setWalletHasConnected(false)
        queryClient.invalidateQueries({ queryKey: accountConfigQueryKey })
      }
    } else if (!walletHasConnected) {
      setWalletHasConnected(true)
    }
  }, [wallets, walletHasConnected, queryClient])

  function onWithdrawal(symbol: TradingSymbol) {
    if (wallets.isConnected(symbol.networkType)) {
      setWithdrawalWallet(
        wallets.connected.find(
          (wallet) => wallet.networkType == symbol.networkType
        )
      )
      setWithdrawalSymbol(symbol)

      if (symbol.networkType === 'Bitcoin') {
        const contractAddress = chains
          .filter((chain) => chain.networkType === 'Bitcoin')[0]
          .contracts.find((c) => c.name == 'Exchange')?.address

        setWithdrawalExchangeContract(contractAddress)
      } else if (symbol.networkType === 'Evm') {
        const contractAddress = chains
          .filter((chain) => chain.networkType === 'Evm')
          .find((chain) => chain.id === symbol.chainId)!
          .contracts.find((c) => c.name == 'Exchange')?.address

        setWithdrawalExchangeContract(contractAddress)
      }
      setShowWithdrawalModal(true)
    }
  }

  return (
    <>
      <div className="flex h-[85vh] flex-col">
        <div className="my-auto grow laptop:max-w-[1800px]">
          <div className="grid h-full w-screen min-w-[1250px] grid-cols-1 gap-2 px-4 text-lg text-white narrow:grid-cols-3">
            {testnetChallengeStatus === 'Enrolled' ? (
              <Leaderboard
                pointsBalance={pointsBalance}
                avatarUrl={avatarUrl}
                nickName={nickName}
                inviteCode={inviteCode}
                wallets={wallets}
                balances={balances}
                symbols={symbols}
                onChangeTab={onChangeTab}
                onWithdrawal={onWithdrawal}
              />
            ) : (
              <div className="col-span-1 laptop:col-span-3">
                <div className="flex h-full flex-col place-content-center">
                  <div className="flex w-full flex-row place-content-center gap-20">
                    {(testnetChallengeStatus === 'Unenrolled' ||
                      testnetChallengeStatus === undefined) && (
                      <>
                        <div className="my-auto">
                          <DiscoBall size={250} />
                        </div>
                        <div className="my-auto flex max-w-64 flex-col items-center gap-4 text-3xl">
                          <div className="animate-fall">
                            Get your groove on with the funkybit Testnet
                            Challenge!
                          </div>
                          <div className="animate-fall self-start">
                            <button
                              className="my-2 rounded-xl bg-darkBluishGray8 px-4 py-2 text-lg"
                              onClick={() => {
                                if (wallets.connected.length === 0) {
                                  setEnrollWhenConnected(true)
                                  wallets.connect('Evm')
                                } else {
                                  testnetChallengeEnrollMutation.mutate()
                                }
                              }}
                            >
                              Enroll
                            </button>
                            {wallets.connected.length === 0 && (
                              <div className="text-sm ">
                                Already enrolled?{' '}
                                <span
                                  className="cursor-pointer underline"
                                  onClick={() => wallets.connect('Evm')}
                                >
                                  Connect your wallet
                                </span>
                                .
                              </div>
                            )}
                          </div>
                        </div>
                      </>
                    )}
                    {testnetChallengeStatus === 'PendingAirdrop' && (
                      <>
                        <div className="my-auto">
                          <img
                            src={ZeppelinSvg}
                            alt={'zeppelin'}
                            className="size-48"
                          />
                        </div>
                        <div className="my-auto max-w-64 text-3xl">
                          Your $10,000 of tUSDC to enter the funkybit Testnet
                          Challenge is on its way!
                        </div>
                      </>
                    )}
                    {testnetChallengeStatus === 'PendingDeposit' &&
                      accountConfig && (
                        <>
                          <div className="my-auto">
                            <img
                              src={PointRightSvg}
                              alt={'safe'}
                              className="size-48"
                            />
                          </div>
                          <div className="my-auto flex max-w-64 flex-col items-start gap-4 text-3xl">
                            <div>Hold on!</div>
                            <div className="text-lg">
                              Before you can trade in the Testnet Challenge, you
                              must first deposit $10,000 of tUSDC.
                            </div>
                            <div>
                              <button
                                className="rounded-xl bg-darkBluishGray8 px-4 py-2 text-lg"
                                onClick={() => triggerDepositModal()}
                              >
                                Deposit
                              </button>
                            </div>
                          </div>
                        </>
                      )}
                    {testnetChallengeStatus ===
                      'PendingDepositConfirmation' && (
                      <>
                        <div className="my-auto">
                          <img
                            src={ClockSvg}
                            alt={'safe'}
                            className="size-48"
                          />
                        </div>
                        <div className="my-auto flex max-w-64 flex-col items-center text-3xl">
                          Waiting for your deposit to be confirmed.
                        </div>
                      </>
                    )}
                    {testnetChallengeStatus === 'Disqualified' && (
                      <>
                        <div className="my-auto">
                          <img
                            src={StopHandSvg}
                            alt={'safe'}
                            className="size-48"
                          />
                        </div>
                        <div className="my-auto flex max-w-64 flex-col items-center text-3xl">
                          You are not eligible for the funkybit Testnet
                          Challenge.
                        </div>
                      </>
                    )}
                  </div>
                </div>
              </div>
            )}
          </div>
        </div>
        {testnetChallengeDepositSymbol &&
          testnetChallengeDepositContract &&
          exchangeContract &&
          exchangeContract?.address &&
          wallets.primary?.address &&
          testnetChallengeDepositSymbol.chainId === evmConfig.state.chainId && (
            <DepositModal
              isOpen={showTestnetChallengeDepositModal}
              exchangeContractAddress={exchangeContract.address}
              walletAddress={wallets.primary.address}
              symbol={testnetChallengeDepositSymbol}
              testnetChallengeDepositLimit={
                accountConfig?.testnetChallengeDepositLimits[
                  testnetChallengeDepositSymbol.name
                ]
              }
              close={() => setShowTestnetChallengeDepositModal(false)}
              onClosed={() => {
                setTestnetChallengeDepositSymbol(undefined)
                queryClient.invalidateQueries({
                  queryKey: accountConfigQueryKey
                })
              }}
              initialAmount={'10000'}
              title={'Almost there!'}
              message={
                'You now have $10,000 of tUSDC in your wallet, click Submit to deposit it to funkybit.'
              }
            />
          )}
        {withdrawalWallet?.address &&
          withdrawalExchangeContract &&
          withdrawalSymbol &&
          (withdrawalSymbol.networkType === 'Bitcoin' ||
            withdrawalSymbol?.chainId === evmConfig.state.chainId) && (
            <WithdrawalModal
              exchangeContractAddress={withdrawalExchangeContract}
              walletAddress={withdrawalWallet.address}
              symbol={withdrawalSymbol}
              isOpen={showWithdrawalModal}
              close={() => setShowWithdrawalModal(false)}
              onClosed={() => {
                setWithdrawalWallet(undefined)
                setWithdrawalSymbol(undefined)
                setWithdrawalExchangeContract(undefined)
                queryClient.invalidateQueries({
                  queryKey: accountConfigQueryKey
                })
              }}
            />
          )}
      </div>
    </>
  )
}
