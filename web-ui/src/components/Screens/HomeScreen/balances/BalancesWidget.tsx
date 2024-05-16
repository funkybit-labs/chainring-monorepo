import { Address } from 'viem'
import { apiClient } from 'apiClient'
import React, { useMemo, useState } from 'react'
import { Widget } from 'components/common/Widget'
import TradingSymbols from 'tradingSymbols'
import { classNames } from 'utils'
import { useQuery } from '@tanstack/react-query'
import Spinner from 'components/common/Spinner'
import { BalancesTable } from 'components/Screens/HomeScreen/balances/BalancesTable'
import { WithdrawalsTable } from 'components/Screens/HomeScreen/balances/WithdrawalsTable'
import { DepositsTable } from 'components/Screens/HomeScreen/balances/DepositsTable'
import { Button } from 'components/common/Button'
import { useWeb3Modal } from '@web3modal/wagmi/react'

export const withdrawalsQueryKey = ['withdrawals']
export const depositsQueryKey = ['deposits']

export default function BalancesWidget({
  walletAddress,
  exchangeContractAddress,
  symbols
}: {
  walletAddress?: Address
  exchangeContractAddress?: Address
  symbols: TradingSymbols
}) {
  const [selectedTab, setSelectedTab] = useState<
    'available' | 'deposits' | 'withdrawals'
  >('available')
  const { open: openWalletConnectModal } = useWeb3Modal()

  const depositsQuery = useQuery({
    queryKey: depositsQueryKey,
    queryFn: apiClient.listDeposits,
    enabled: walletAddress !== undefined
  })

  const pendingDepositsCount = useMemo(() => {
    return (depositsQuery.data?.deposits || []).filter(
      (d) => d.status == 'Pending'
    ).length
  }, [depositsQuery.data])

  const withdrawalsQuery = useQuery({
    queryKey: withdrawalsQueryKey,
    queryFn: apiClient.listWithdrawals,
    enabled: walletAddress !== undefined
  })

  const pendingWithdrawalsCount = useMemo(() => {
    return (withdrawalsQuery.data?.withdrawals || []).filter(
      (w) => w.status == 'Pending'
    ).length
  }, [withdrawalsQuery.data])

  return (
    <Widget
      id="balances"
      contents={
        <>
          <div className="flex w-full text-center font-medium">
            <div
              className={classNames(
                'cursor-pointer border-b-2 mr-4 w-full h-10 flex-col content-end pb-1',
                selectedTab == 'available'
                  ? 'border-b-primary4'
                  : 'border-b-darkBluishGray3'
              )}
              onClick={() => setSelectedTab('available')}
            >
              <div
                className={
                  selectedTab == 'available'
                    ? 'text-primary4'
                    : 'text-darkBluishGray3'
                }
              >
                Available
              </div>
            </div>
            <div
              className={classNames(
                'cursor-pointer border-b-2 mx-4 w-full h-10 flex-col content-end pb-1',
                selectedTab == 'deposits'
                  ? 'border-b-primary4'
                  : 'border-b-darkBluishGray3'
              )}
              onClick={() => setSelectedTab('deposits')}
            >
              <div
                className={
                  selectedTab == 'deposits'
                    ? 'text-primary4'
                    : 'text-darkBluishGray3'
                }
              >
                Deposits
              </div>
              {pendingDepositsCount > 0 && (
                <div
                  className={classNames(
                    'whitespace-nowrap text-xs',
                    selectedTab == 'deposits'
                      ? 'text-primary4'
                      : 'text-darkBluishGray3'
                  )}
                >
                  ({pendingDepositsCount} pending)
                </div>
              )}
            </div>
            <div
              className={classNames(
                'cursor-pointer border-b-2 ml-4 w-full h-10 flex-col content-end pb-1',
                selectedTab == 'withdrawals'
                  ? 'border-b-primary4'
                  : 'border-b-darkBluishGray3'
              )}
              onClick={() => setSelectedTab('withdrawals')}
            >
              <div
                className={
                  selectedTab == 'withdrawals'
                    ? 'text-primary4'
                    : 'text-darkBluishGray3'
                }
              >
                Withdrawals
              </div>
              {pendingWithdrawalsCount > 0 && (
                <div
                  className={classNames(
                    'whitespace-nowrap text-xs',
                    selectedTab == 'withdrawals'
                      ? 'text-primary4'
                      : 'text-darkBluishGray3'
                  )}
                >
                  ({pendingWithdrawalsCount} pending)
                </div>
              )}
            </div>
          </div>
          <div className="mt-8">
            {walletAddress !== undefined &&
            exchangeContractAddress !== undefined ? (
              (function () {
                if (
                  withdrawalsQuery.data === undefined ||
                  depositsQuery.data === undefined
                ) {
                  return <Spinner />
                } else {
                  switch (selectedTab) {
                    case 'available':
                      return (
                        <BalancesTable
                          walletAddress={walletAddress}
                          exchangeContractAddress={exchangeContractAddress}
                          symbols={symbols}
                        />
                      )
                    case 'withdrawals':
                      return (
                        <WithdrawalsTable
                          withdrawals={withdrawalsQuery.data.withdrawals}
                          symbols={symbols}
                        />
                      )
                    case 'deposits':
                      return (
                        <DepositsTable
                          deposits={depositsQuery.data.deposits}
                          symbols={symbols}
                        />
                      )
                  }
                }
              })()
            ) : (
              <div className="flex w-full flex-col place-items-center">
                <div className="mb-4 text-darkBluishGray2">
                  If you want to see your{' '}
                  {selectedTab == 'available'
                    ? 'available balances'
                    : selectedTab == 'deposits'
                      ? 'deposits'
                      : 'withdrawals'}
                  , connect your wallet.
                </div>
                <Button
                  primary={true}
                  caption={() => <>Connect Wallet</>}
                  style={'normal'}
                  disabled={false}
                  onClick={() => openWalletConnectModal({ view: 'Connect' })}
                />
              </div>
            )}
          </div>
        </>
      }
    />
  )
}
