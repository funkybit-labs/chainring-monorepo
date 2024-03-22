import { Address, formatUnits } from 'viem'
import { TradingSymbol } from 'ApiClient'
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
  symbols
}: {
  walletAddress: Address
  exchangeContractAddress: Address
  symbols: TradingSymbol[]
}) {
  return (
    <Widget
      title={'Balances'}
      contents={
        <BalancesTable
          walletAddress={walletAddress}
          exchangeContractAddress={exchangeContractAddress}
          symbols={symbols}
        />
      }
    />
  )
}

function BalancesTable({
  walletAddress,
  exchangeContractAddress,
  symbols
}: {
  walletAddress: Address
  exchangeContractAddress: Address
  symbols: TradingSymbol[]
}) {
  const queryClient = useQueryClient()
  const [depositSymbol, setDepositSymbol] = useState<TradingSymbol | null>(null)
  const [showDepositModal, setShowDepositModal] = useState<boolean>(false)

  const [withdrawSymbol, setWithdrawSymbol] = useState<TradingSymbol | null>(
    null
  )
  const [showWithdrawalModal, setShowWithdrawalModal] = useState<boolean>(false)

  const nativeSymbol = symbols.find((symbol) => symbol.contractAddress == null)
  const erc20Symbols = symbols.filter(
    (symbol) => symbol.contractAddress != null
  )

  const nativeBalanceQuery = useReadContract({
    abi: ExchangeAbi,
    address: exchangeContractAddress,
    functionName: 'nativeBalances',
    args: [walletAddress]
  })

  const erc20TokenBalancesQuery = useReadContracts({
    contracts: erc20Symbols.map((symbol) => {
      return {
        abi: ExchangeAbi,
        address: exchangeContractAddress,
        functionName: 'balances',
        args: [walletAddress, symbol.contractAddress]
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
    erc20TokenBalancesQuery.data.some((br) => br.status === 'failure') ||
    nativeSymbol == undefined
  ) {
    return 'Failed to get balances'
  }

  const balances = [
    { symbol: nativeSymbol, amount: nativeBalanceQuery.data }
  ].concat(
    erc20Symbols
      .map((symbol, i) => {
        const tokenBalanceResult = erc20TokenBalancesQuery.data[i]
        const balance = tokenBalanceResult.result
        if (
          tokenBalanceResult.status === 'success' &&
          typeof balance === 'bigint'
        ) {
          return { symbol, amount: balance }
        } else {
          return null
        }
      })
      .filter(isNotNullable)
  )

  function openDepositModal(symbol: TradingSymbol) {
    setDepositSymbol(symbol)
    setShowDepositModal(true)
  }

  function openWithdrawModal(symbol: TradingSymbol) {
    setWithdrawSymbol(symbol)
    setShowWithdrawalModal(true)
  }

  return (
    <>
      <table>
        <tbody>
          {balances.map((symbolBalance) => {
            return (
              <tr key={symbolBalance.symbol.name}>
                <td className="min-w-12 pr-2">{symbolBalance.symbol.name}</td>
                <td className="min-w-12 px-4 text-left">
                  {formatUnits(
                    symbolBalance.amount,
                    symbolBalance.symbol.decimals
                  )}
                </td>
                <td className="px-2 py-1">
                  <Button
                    caption={() => <>Deposit</>}
                    onClick={() => openDepositModal(symbolBalance.symbol)}
                    disabled={false}
                  />
                </td>
                <td className="py-1 pl-2">
                  <Button
                    caption={() => <>Withdraw</>}
                    onClick={() => openWithdrawModal(symbolBalance.symbol)}
                    disabled={symbolBalance.amount === 0n}
                  />
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>

      {depositSymbol && (
        <DepositModal
          isOpen={showDepositModal}
          exchangeContractAddress={exchangeContractAddress}
          walletAddress={walletAddress}
          symbol={depositSymbol}
          close={() => setShowDepositModal(false)}
          onClosed={() => setDepositSymbol(null)}
        />
      )}

      {withdrawSymbol && (
        <WithdrawalModal
          isOpen={showWithdrawalModal}
          exchangeContractAddress={exchangeContractAddress}
          walletAddress={walletAddress}
          symbol={withdrawSymbol}
          close={() => setShowWithdrawalModal(false)}
          onClosed={() => setWithdrawSymbol(null)}
        />
      )}
    </>
  )
}
