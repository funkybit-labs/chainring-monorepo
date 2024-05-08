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

export const withdrawalsQueryKey = ['withdrawals']
export const depositsQueryKey = ['deposits']

export default function BalancesWidget({
  walletAddress,
  exchangeContractAddress,
  symbols
}: {
  walletAddress: Address
  exchangeContractAddress: Address
  symbols: TradingSymbols
}) {
  const [selectedTab, setSelectedTab] = useState<
    'available' | 'deposits' | 'withdrawals'
  >('available')

  const depositsQuery = useQuery({
    queryKey: depositsQueryKey,
    queryFn: apiClient.listDeposits
  })

  const pendingDepositsCount = useMemo(() => {
    return (depositsQuery.data?.deposits || []).filter(
      (d) => d.status == 'Pending'
    ).length
  }, [depositsQuery.data])

  const withdrawalsQuery = useQuery({
    queryKey: withdrawalsQueryKey,
    queryFn: apiClient.listWithdrawals
  })

  const pendingWithdrawalsCount = useMemo(() => {
    return (withdrawalsQuery.data?.withdrawals || []).filter(
      (w) => w.status == 'Pending'
    ).length
  }, [withdrawalsQuery.data])

  return (
    <Widget
      title={'Balances'}
      contents={
        !!withdrawalsQuery.data && !!depositsQuery.data ? (
          <>
            <div className="flex w-full text-center font-medium">
              <div
                className={classNames(
                  'cursor-pointer border-b-2 w-full h-10 flex-col content-end pb-1',
                  selectedTab == 'available'
                    ? 'border-b-lightBackground'
                    : 'border-b-darkGray'
                )}
                onClick={() => setSelectedTab('available')}
              >
                <div>Available</div>
              </div>
              <div
                className={classNames(
                  'cursor-pointer border-b-2 w-full h-10 flex-col content-end pb-1',
                  selectedTab == 'deposits'
                    ? 'border-b-lightBackground'
                    : 'border-b-darkGray'
                )}
                onClick={() => setSelectedTab('deposits')}
              >
                <div>Deposits</div>
                {pendingDepositsCount > 0 && (
                  <div className="whitespace-nowrap text-xs">
                    ({pendingDepositsCount} pending)
                  </div>
                )}
              </div>
              <div
                className={classNames(
                  'cursor-pointer border-b-2 w-full h-10 flex-col content-end pb-1',
                  selectedTab == 'withdrawals'
                    ? 'border-b-lightBackground'
                    : 'border-b-darkGray'
                )}
                onClick={() => setSelectedTab('withdrawals')}
              >
                <div>Withdrawals</div>
                {pendingWithdrawalsCount > 0 && (
                  <div className="whitespace-nowrap text-xs">
                    ({pendingWithdrawalsCount} pending)
                  </div>
                )}
              </div>
            </div>
            <div className="mt-4">
              {(function () {
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
              })()}
            </div>
          </>
        ) : (
          <Spinner />
        )
      }
    />
  )
}