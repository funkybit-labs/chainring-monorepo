import { Address, formatUnits } from 'viem'
import { ERC20Token } from 'ApiClient'
import { useBlockNumber, useReadContracts } from 'wagmi'
import { ExchangeAbi } from 'contracts'
import Spinner from 'components/common/Spinner'
import { classNames, isNotNullable } from 'utils'
import { useEffect, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import DepositModal from 'components/Screens/HomeScreen/Balances/DepositModal'
import WithdrawalModal from 'components/Screens/HomeScreen/Balances/WithdrawalModal'

export default function Balances({
  walletAddress,
  exchangeContractAddress,
  erc20TokenContracts
}: {
  walletAddress: Address
  exchangeContractAddress: Address
  erc20TokenContracts: ERC20Token[]
}) {
  return (
    <div>
      <div className="mb-2 text-center text-xl font-medium text-white">
        Balances
      </div>
      <div className="w-full rounded-lg bg-gray-500/50 p-8 text-white">
        <BalancesTable
          walletAddress={walletAddress}
          exchangeContractAddress={exchangeContractAddress}
          erc20TokenContracts={erc20TokenContracts}
        />
      </div>
    </div>
  )
}

function BalancesTable({
  walletAddress,
  exchangeContractAddress,
  erc20TokenContracts
}: {
  walletAddress: Address
  exchangeContractAddress: Address
  erc20TokenContracts: ERC20Token[]
}) {
  const queryClient = useQueryClient()
  const [depositToken, setDepositToken] = useState<ERC20Token | null>(null)
  const [showDepositModal, setShowDepositModal] = useState<boolean>(false)

  const [withdrawToken, setWithdrawToken] = useState<ERC20Token | null>(null)
  const [showWithdrawalModal, setShowWithdrawalModal] = useState<boolean>(false)

  const tokenBalancesQuery = useReadContracts({
    contracts: erc20TokenContracts.map((token) => {
      return {
        abi: ExchangeAbi,
        address: exchangeContractAddress,
        functionName: 'balances',
        args: [walletAddress, token.address]
      }
    })
  })

  // subscribe to block number updates
  const { data: blockNumber } = useBlockNumber({ watch: true })

  // refresh balances every time block number is updated
  useEffect(() => {
    queryClient.invalidateQueries({ queryKey: tokenBalancesQuery.queryKey })
  }, [blockNumber, queryClient, tokenBalancesQuery.queryKey])

  if (tokenBalancesQuery.isPending) {
    return (
      <div className="size-12">
        <Spinner />
      </div>
    )
  }

  if (
    tokenBalancesQuery.error ||
    tokenBalancesQuery.data.some((br) => br.status === 'failure')
  ) {
    return 'Failed to get balances'
  }

  const balances = erc20TokenContracts
    .map((token, i) => {
      const tokenBalanceResult = tokenBalancesQuery.data[i]
      const balance = tokenBalanceResult.result
      if (
        tokenBalanceResult.status === 'success' &&
        typeof balance === 'bigint'
      ) {
        return { token, amount: balance }
      } else {
        return null
      }
    })
    .filter(isNotNullable)

  function openDepositModal(token: ERC20Token) {
    setDepositToken(token)
    setShowDepositModal(true)
  }

  function openWithdrawModal(token: ERC20Token) {
    setWithdrawToken(token)
    setShowWithdrawalModal(true)
  }

  return (
    <>
      <table>
        <tbody>
          {balances.map((tokenBalance) => {
            return (
              <tr key={tokenBalance.token.address}>
                <td className="pr-2">{tokenBalance.token.symbol}</td>
                <td className="w-full min-w-48 pr-2 text-right">
                  {formatUnits(tokenBalance.amount, 18)}
                </td>
                <td className="py-1 pr-2">
                  <BalanceButton
                    caption="Deposit"
                    onClick={() => openDepositModal(tokenBalance.token)}
                    disabled={false}
                  />
                </td>
                <td className="py-1">
                  <BalanceButton
                    caption="Withdraw"
                    onClick={() => openWithdrawModal(tokenBalance.token)}
                    disabled={tokenBalance.amount === 0n}
                  />
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>

      {depositToken && (
        <DepositModal
          isOpen={showDepositModal}
          exchangeContractAddress={exchangeContractAddress}
          walletAddress={walletAddress}
          token={depositToken}
          close={() => setShowDepositModal(false)}
          onClosed={() => setDepositToken(null)}
        />
      )}

      {withdrawToken && (
        <WithdrawalModal
          isOpen={showWithdrawalModal}
          exchangeContractAddress={exchangeContractAddress}
          walletAddress={walletAddress}
          token={withdrawToken}
          close={() => setShowWithdrawalModal(false)}
          onClosed={() => setWithdrawToken(null)}
        />
      )}
    </>
  )
}

function BalanceButton({
  caption,
  onClick,
  disabled = false
}: {
  caption: string
  onClick: () => void
  disabled: boolean
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={classNames(
        'inline-block rounded-md border border-transparent px-2 py-1 text-xs text-center font-medium bg-gray-100 text-black focus:outline-none focus:ring-1 focus:ring-inset focus:ring-gray-700',
        disabled ? 'opacity-50' : 'hover:bg-gray-200'
      )}
    >
      {caption}
    </button>
  )
}
