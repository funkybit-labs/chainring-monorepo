import { AddressType, apiClient } from 'apiClient'
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import TradingSymbol from 'tradingSymbol'
import { useSwitchToEthChain } from 'utils/switchToEthChain'
import DepositModal from 'components/Screens/HomeScreen/DepositModal'
import { useConfig } from 'wagmi'
import TradingSymbols from 'tradingSymbols'
import { useWallet } from 'contexts/walletProvider'
import DiscoBall from 'components/Screens/HomeScreen/DiscoBall'

export function TestnetChallengeTab({
  symbols,
  exchangeContract
}: {
  symbols: TradingSymbols
  exchangeContract?: { name: string; address: `0x${string}` }
}) {
  const evmConfig = useConfig()
  const queryClient = useQueryClient()
  const wallet = useWallet()
  const accountConfigQuery = useQuery({
    queryKey: ['accountConfiguration'],
    queryFn: apiClient.getAccountConfiguration
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
      await apiClient.testnetChallengeEnroll(undefined)
    },
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: ['accountConfiguration'] })
  })

  const testnetChallengeStatus = useMemo(() => {
    return (
      accountConfigQuery.data && accountConfigQuery.data.testnetChallengeStatus
    )
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
        queryClient.invalidateQueries({ queryKey: ['accountConfiguration'] })
      }, 1000)
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

  const autoDepositTriggeredRef = useRef(false)

  useEffect(() => {
    switch (testnetChallengeStatus) {
      case 'PendingDeposit':
        if (!autoDepositTriggeredRef.current) triggerDepositModal()
        autoDepositTriggeredRef.current = true
        break
    }
  }, [testnetChallengeStatus, triggerDepositModal])

  return (
    <>
      <div className="my-auto laptop:max-w-[1800px]">
        <div className="grid grid-cols-1 gap-4 text-lg text-white laptop:grid-cols-3">
          <div className="col-span-1 laptop:col-span-3">
            {testnetChallengeStatus === 'Unenrolled' && (
              <>
                <div className="flex flex-col items-center">
                  <div>
                    Get your groove on with the funkybit Testnet Challenge!
                    <DiscoBall />
                  </div>
                  <div>
                    <button
                      className="rounded-xl bg-darkBluishGray8 px-4 py-2"
                      onClick={() => testnetChallengeEnrollMutation.mutate()}
                    >
                      Enroll
                    </button>
                  </div>
                </div>
              </>
            )}
            {testnetChallengeStatus === 'PendingAirdrop' && (
              <>
                Your airdrop to enter the funkybit Testnet Challenge is on its
                way!
              </>
            )}
            {testnetChallengeStatus === 'PendingDeposit' && (
              <>
                <div className="flex flex-col items-center gap-4">
                  <div>
                    Before you can trade in the Testnet Challenge, you must
                    first deposit 10,000 tUSDC.
                  </div>
                  <button
                    className="rounded-xl bg-darkBluishGray8 px-4 py-2"
                    onClick={() => triggerDepositModal()}
                  >
                    Deposit
                  </button>
                </div>
              </>
            )}
            {testnetChallengeStatus === 'PendingDepositConfirmation' && (
              <>Waiting for your 10,000 tUSDC deposit to be confirmed.</>
            )}
            {testnetChallengeStatus === 'Disqualified' && (
              <>You are not eligible for the funkybit Testnet Challenge.</>
            )}
            {testnetChallengeStatus === 'Enrolled' && (
              <>You are enrolled in the funkybit Testnet Challenge.</>
            )}
          </div>
        </div>
      </div>
      {testnetChallengeDepositSymbol &&
        testnetChallengeDepositContract &&
        exchangeContract &&
        exchangeContract?.address &&
        wallet.primaryAddress &&
        testnetChallengeDepositSymbol.chainId === evmConfig.state.chainId && (
          <DepositModal
            isOpen={showTestnetChallengeDepositModal}
            exchangeContractAddress={exchangeContract.address}
            walletAddress={wallet.primaryAddress}
            symbol={testnetChallengeDepositSymbol}
            close={() => setShowTestnetChallengeDepositModal(false)}
            onClosed={() => {
              setTestnetChallengeDepositSymbol(undefined)
            }}
            initialAmount={'10000'}
            title={'Airdrop Complete'}
            message={
              'You now have 10,000 tUSDC in your wallet, click Submit to deposit it to funkybit.'
            }
          />
        )}
    </>
  )
}
