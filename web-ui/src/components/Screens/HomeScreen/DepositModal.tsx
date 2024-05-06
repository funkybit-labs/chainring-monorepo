import { Address, formatUnits } from 'viem'
import { BaseError as WagmiError, useConfig } from 'wagmi'
import { ERC20Abi, ExchangeAbi } from 'contracts'
import { useState } from 'react'
import {
  getBalance,
  getTransactionCount,
  readContract,
  sendTransaction
} from 'wagmi/actions'
import { Modal, ModalAsyncContent } from 'components/common/Modal'
import AmountInput from 'components/common/AmountInput'
import SubmitButton from 'components/common/SubmitButton'
import { apiClient, TradingSymbol } from 'apiClient'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import useAmountInputState from 'hooks/useAmountInputState'
import { depositsQueryKey } from 'components/Screens/HomeScreen/balances/BalancesWidget'
import { encodeFunctionData, EncodeFunctionDataParameters } from 'viem'
import { isErrorFromAlias } from '@zodios/core'

export default function DepositModal({
  exchangeContractAddress,
  walletAddress,
  symbol,
  isOpen,
  close,
  onClosed
}: {
  exchangeContractAddress: Address
  walletAddress: Address
  symbol: TradingSymbol
  isOpen: boolean
  close: () => void
  onClosed: () => void
}) {
  const config = useConfig()

  const walletBalanceQuery = useQuery({
    queryKey: ['walletBalance', symbol.name],
    queryFn: async function () {
      return symbol.contractAddress
        ? await readContract(config, {
            abi: ERC20Abi,
            address: symbol.contractAddress,
            functionName: 'balanceOf',
            args: [walletAddress]
          })
        : getBalance(config, {
            address: walletAddress
          }).then((res) => res.value)
    }
  })

  const {
    inputValue: amountInputValue,
    setInputValue: setAmountInputValue,
    valueInFundamentalUnits: amount
  } = useAmountInputState({
    initialInputValue: '',
    decimals: symbol.decimals
  })

  const [submitPhase, setSubmitPhase] = useState<
    | 'waitingForAllowanceApproval'
    | 'waitingForDepositApproval'
    | 'submittingDeposit'
    | null
  >(null)

  const queryClient = useQueryClient()

  const mutation = useMutation({
    mutationFn: async () => {
      try {
        let depositHash: string

        if (symbol.contractAddress) {
          const transactionCount = await getTransactionCount(config, {
            address: walletAddress
          })

          setSubmitPhase('waitingForAllowanceApproval')
          await sendTransaction(config, {
            to: symbol.contractAddress,
            data: encodeFunctionData({
              abi: ERC20Abi,
              args: [exchangeContractAddress, amount],
              functionName: 'approve'
            } as EncodeFunctionDataParameters),
            nonce: transactionCount
          })

          setSubmitPhase('waitingForDepositApproval')
          depositHash = await sendTransaction(config, {
            to: exchangeContractAddress,
            data: encodeFunctionData({
              abi: ExchangeAbi,
              functionName: 'deposit',
              args: [symbol.contractAddress, amount]
            }),
            nonce: transactionCount + 1
          })
        } else {
          setSubmitPhase('waitingForDepositApproval')
          depositHash = await sendTransaction(config, {
            to: exchangeContractAddress,
            value: amount
          })
        }

        return await apiClient.createDeposit({
          symbol: symbol.name,
          amount: amount,
          txHash: depositHash
        })
      } catch (error) {
        setSubmitPhase(null)
        throw Error(
          isErrorFromAlias(apiClient.api, 'createDeposit', error)
            ? error.response.data.errors[0].displayMessage
            : (error as WagmiError).shortMessage || 'Something went wrong'
        )
      }
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: depositsQueryKey })
      close()
    }
  })

  const canSubmit = (function () {
    if (submitPhase !== null) return false

    try {
      if (walletBalanceQuery.status == 'success') {
        const availableAmount = walletBalanceQuery.data
        return amount > 0 && amount <= availableAmount
      } else {
        return false
      }
    } catch {
      return false
    }
  })()

  return (
    <Modal
      isOpen={isOpen}
      close={close}
      onClosed={onClosed}
      title={`Deposit ${symbol.name}`}
    >
      <div className="h-52 overflow-y-auto">
        <ModalAsyncContent
          asyncData={walletBalanceQuery}
          success={(walletBalance) => {
            return (
              <div className="mt-8">
                <AmountInput
                  value={amountInputValue}
                  symbol={symbol.name}
                  disabled={submitPhase !== null}
                  onChange={(e) => setAmountInputValue(e.target.value)}
                />
                <p className="mt-1 text-center text-sm text-neutralGray">
                  Available balance:{' '}
                  {formatUnits(walletBalance, symbol.decimals)}
                </p>

                <SubmitButton
                  disabled={!canSubmit}
                  onClick={mutation.mutate}
                  error={mutation.error?.message}
                  caption={() => {
                    switch (submitPhase) {
                      case 'waitingForAllowanceApproval':
                        return 'Waiting for allowance approval'
                      case 'waitingForDepositApproval':
                        return 'Waiting for deposit approval'
                      case 'submittingDeposit':
                        return 'Submitting Deposit'
                      case null:
                        return 'Submit'
                    }
                  }}
                />
              </div>
            )
          }}
          error={() => {
            return <>Failed to fetch balance</>
          }}
        />
      </div>
    </Modal>
  )
}
