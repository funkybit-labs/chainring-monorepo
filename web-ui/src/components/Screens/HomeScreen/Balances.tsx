import { Address, formatUnits } from 'viem'
import { Token, ERC20Token, NativeToken } from 'ApiClient'
import { useBlockNumber, useReadContract, useReadContracts } from 'wagmi'
import { ExchangeAbi } from 'contracts'
import Spinner from 'components/common/Spinner'
import { isNotNullable } from 'utils'
import { useEffect, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import DepositModal from 'components/Screens/HomeScreen/Balances/DepositModal'
import WithdrawalModal from 'components/Screens/HomeScreen/Balances/WithdrawalModal'
import { Widget } from 'components/common/Widget'
import { Button } from 'components/common/Button'

export default function Balances({
  walletAddress,
  exchangeContractAddress,
  nativeToken,
  erc20Tokens
}: {
  walletAddress: Address
  exchangeContractAddress: Address
  nativeToken: NativeToken
  erc20Tokens: ERC20Token[]
}) {
  return (
    <Widget
      title={'Balances'}
      contents={
        <BalancesTable
          walletAddress={walletAddress}
          exchangeContractAddress={exchangeContractAddress}
          nativeToken={nativeToken}
          erc20Tokens={erc20Tokens}
        />
      }
    />
  )
}

function BalancesTable({
  walletAddress,
  exchangeContractAddress,
  nativeToken,
  erc20Tokens
}: {
  walletAddress: Address
  exchangeContractAddress: Address
  erc20Tokens: ERC20Token[]
  nativeToken: NativeToken
}) {
  const queryClient = useQueryClient()
  const [depositToken, setDepositToken] = useState<Token | null>(null)
  const [showDepositModal, setShowDepositModal] = useState<boolean>(false)

  const [withdrawToken, setWithdrawToken] = useState<Token | null>(null)
  const [showWithdrawalModal, setShowWithdrawalModal] = useState<boolean>(false)

  const nativeBalanceQuery = useReadContract({
    abi: ExchangeAbi,
    address: exchangeContractAddress,
    functionName: 'nativeBalances',
    args: [walletAddress]
  })

  const erc20TokenBalancesQuery = useReadContracts({
    contracts: erc20Tokens.map((token) => {
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
    queryClient.invalidateQueries({ queryKey: nativeBalanceQuery.queryKey })
    queryClient.invalidateQueries({
      queryKey: erc20TokenBalancesQuery.queryKey
    })
  }, [
    blockNumber,
    queryClient,
    nativeBalanceQuery.queryKey,
    erc20TokenBalancesQuery.queryKey
  ])

  if (nativeBalanceQuery.isPending || erc20TokenBalancesQuery.isPending) {
    return (
      <div className="size-12">
        <Spinner />
      </div>
    )
  }

  if (
    nativeBalanceQuery.error ||
    erc20TokenBalancesQuery.error ||
    erc20TokenBalancesQuery.data.some((br) => br.status === 'failure')
  ) {
    return 'Failed to get balances'
  }

  const balances = [
    { token: nativeToken, amount: nativeBalanceQuery.data }
  ].concat(
    erc20Tokens
      .map((token, i) => {
        const tokenBalanceResult = erc20TokenBalancesQuery.data[i]
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
  )

  function openDepositModal(token: Token) {
    setDepositToken(token)
    setShowDepositModal(true)
  }

  function openWithdrawModal(token: Token) {
    setWithdrawToken(token)
    setShowWithdrawalModal(true)
  }

  return (
    <>
      <table>
        <tbody>
          {balances.map((tokenBalance) => {
            return (
              <tr key={tokenBalance.token.symbol}>
                <td className="min-w-12 pr-2">{tokenBalance.token.symbol}</td>
                <td className="min-w-12 px-4 text-left">
                  {formatUnits(
                    tokenBalance.amount,
                    tokenBalance.token.decimals
                  )}
                </td>
                <td className="px-2 py-1">
                  <Button
                    caption={() => <>Deposit</>}
                    onClick={() => openDepositModal(tokenBalance.token)}
                    disabled={false}
                  />
                </td>
                <td className="py-1 pl-2">
                  <Button
                    caption={() => <>Withdraw</>}
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
