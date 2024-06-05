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

type Tab = 'Available' | 'Deposits' | 'Withdrawals'

export default function BalancesWidget({
  walletAddress,
  exchangeContractAddress,
  symbols
}: {
  walletAddress?: Address
  exchangeContractAddress?: Address
  symbols: TradingSymbols
}) {
  const [selectedTab, setSelectedTab] = useState<Tab>('Available')
  const { open: openWalletConnectModal } = useWeb3Modal()

  const depositsQuery = useQuery({
    queryKey: depositsQueryKey,
    queryFn: apiClient.listDeposits,
    enabled: walletAddress !== undefined
  })

  const pendingDepositsCount = useMemo(() => {
    return walletAddress === undefined
      ? 0
      : (depositsQuery.data?.deposits || []).filter(
          (d) => d.status == 'Pending'
        ).length
  }, [depositsQuery.data, walletAddress])

  const withdrawalsQuery = useQuery({
    queryKey: withdrawalsQueryKey,
    queryFn: apiClient.listWithdrawals,
    enabled: walletAddress !== undefined
  })

  const pendingWithdrawalsCount = useMemo(() => {
    return walletAddress === undefined
      ? 0
      : (withdrawalsQuery.data?.withdrawals || []).filter(
          (w) => w.status == 'Pending'
        ).length
  }, [withdrawalsQuery.data, walletAddress])

  return (
    <Widget
      id="balances"
      contents={
        <>
          <div className="flex w-full space-x-4 text-center font-medium">
            {(['Available', 'Deposits', 'Withdrawals'] as Tab[]).map((t) => (
              <div
                key={t}
                className={classNames(
                  'cursor-pointer border-b-2 w-full h-12 flex-col content-end pb-2 transition-colors',
                  selectedTab === t
                    ? 'text-statusOrange'
                    : 'text-darkBluishGray3 hover:text-white'
                )}
                onClick={() => setSelectedTab(t)}
              >
                {t}
                {pendingDepositsCount > 0 && t === 'Deposits' && (
                  <div
                    className={classNames(
                      'whitespace-nowrap text-xs',
                      selectedTab === t
                        ? 'text-statusOrange'
                        : 'text-darkBluishGray3 hover:text-white'
                    )}
                  >
                    ({pendingDepositsCount} pending)
                  </div>
                )}
                {pendingWithdrawalsCount > 0 && t === 'Withdrawals' && (
                  <div
                    className={classNames(
                      'whitespace-nowrap text-xs',
                      selectedTab === t
                        ? 'text-statusOrange'
                        : 'text-darkBluishGray3 hover:text-white'
                    )}
                  >
                    ({pendingWithdrawalsCount} pending)
                  </div>
                )}
              </div>
            ))}
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
                    case 'Available':
                      return (
                        <BalancesTable
                          walletAddress={walletAddress}
                          exchangeContractAddress={exchangeContractAddress}
                          symbols={symbols}
                        />
                      )
                    case 'Withdrawals':
                      return (
                        <WithdrawalsTable
                          withdrawals={withdrawalsQuery.data.withdrawals}
                          symbols={symbols}
                        />
                      )
                    case 'Deposits':
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
                  {selectedTab === 'Available'
                    ? 'available balances'
                    : selectedTab === 'Deposits'
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
