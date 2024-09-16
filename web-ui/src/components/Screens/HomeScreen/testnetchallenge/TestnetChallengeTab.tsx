import { AddressType, apiClient } from 'apiClient'
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import TradingSymbol from 'tradingSymbol'
import { useSwitchToEthChain } from 'utils/switchToEthChain'
import { useConfig } from 'wagmi'
import TradingSymbols from 'tradingSymbols'
import { useWallets } from 'contexts/walletProvider'
import ZeppelinSvg from 'assets/zeppelin.svg'
import PointRightSvg from 'assets/point-right.svg'
import StopHandSvg from 'assets/stop-hand.svg'
import ClockSvg from 'assets/clock.svg'
import DiscoBall from 'components/Screens/HomeScreen/DiscoBall'
import DepositModal from 'components/Screens/HomeScreen/DepositModal'
import { Leaderboard } from 'components/Screens/HomeScreen/testnetchallenge/Leaderboard'
import { Tab } from 'components/Screens/Header'

export const testnetChallengeInviteCodeKey = 'testnetChallengeInviteCode'
export function TestnetChallengeTab({
  symbols,
  exchangeContract,
  onChangeTab
}: {
  symbols: TradingSymbols
  exchangeContract?: { name: string; address: string }
  onChangeTab: (tab: Tab) => void
}) {
  const evmConfig = useConfig()
  const queryClient = useQueryClient()
  const wallets = useWallets()
  const accountConfigQuery = useQuery({
    queryKey: ['accountConfiguration'],
    queryFn: apiClient.getAccountConfiguration,
    enabled: wallets.connected.length > 0
  })

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
      queryClient.invalidateQueries({ queryKey: ['accountConfiguration'] })
      localStorage.removeItem(testnetChallengeInviteCodeKey)
    }
  })

  const { testnetChallengeStatus, nickName, avatarUrl, inviteCode } =
    useMemo(() => {
      if (accountConfigQuery.data) {
        return {
          testnetChallengeStatus:
            accountConfigQuery.data.testnetChallengeStatus,
          nickName: accountConfigQuery.data.nickName ?? undefined,
          avatarUrl: accountConfigQuery.data.avatarUrl ?? undefined,
          inviteCode: accountConfigQuery.data.inviteCode
        }
      }
      return {}
    }, [accountConfigQuery.data])

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
          queryKey: ['accountConfiguration'],
          fetchStatus: 'idle'
        })
      }, 3000)
    }
    return () => {
      if (accountRefreshRef.current) clearInterval(accountRefreshRef.current)
    }
  }, [testnetChallengeStatus, queryClient])

  const triggerDepositModal = useCallback(() => {
    const symbolName = accountConfigQuery.data?.testnetChallengeDepositSymbol
    const symbolContract =
      accountConfigQuery.data?.testnetChallengeDepositContract
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
  }, [accountConfigQuery, evmConfig.state.chainId, symbols])

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
        queryClient.invalidateQueries({ queryKey: ['accountConfiguration'] })
      }
    } else if (!walletHasConnected) {
      setWalletHasConnected(true)
    }
  }, [wallets, walletHasConnected, queryClient])

  return (
    <>
      <div className="flex h-[85vh] flex-col">
        <div className="my-auto grow laptop:max-w-[1800px]">
          <div className="grid h-full w-screen min-w-[1250px] grid-cols-1 gap-2 px-4 text-lg text-white narrow:grid-cols-3">
            {testnetChallengeStatus === 'Enrolled' ? (
              <Leaderboard
                avatarUrl={avatarUrl}
                nickName={nickName}
                inviteCode={inviteCode}
                wallets={wallets}
                onChangeTab={onChangeTab}
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
                      accountConfigQuery.status === 'success' && (
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
              close={() => setShowTestnetChallengeDepositModal(false)}
              onClosed={() => {
                setTestnetChallengeDepositSymbol(undefined)
                queryClient.invalidateQueries({
                  queryKey: ['accountConfiguration']
                })
              }}
              initialAmount={'10000'}
              title={'Almost there!'}
              message={
                'You now have $10,000 of tUSDC in your wallet, click Submit to deposit it to funkybit.'
              }
            />
          )}
      </div>
    </>
  )
}
