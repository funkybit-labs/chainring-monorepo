import { Address, formatUnits, parseUnits } from 'viem'
import { BaseError, useConfig, useWriteContract } from 'wagmi'
import { ERC20Abi, ExchangeAbi } from 'contracts'
import { useState } from 'react'
import {
  getBalance,
  readContract,
  sendTransaction,
  waitForTransactionReceipt
} from 'wagmi/actions'
import { Modal, ModalAsyncContent } from 'components/common/Modal'
import AmountInput from 'components/common/AmountInput'
import SubmitButton from 'components/common/SubmitButton'
import { TradingSymbol } from 'ApiClient'
import { useQuery } from '@tanstack/react-query'
import { cleanAndFormatNumberInput } from 'utils'

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

  const [amount, setAmount] = useState('')
  const [submitPhase, setSubmitPhase] = useState<
    | 'waitingForAllowanceApproval'
    | 'waitingForAllowanceReceipt'
    | 'waitingForDepositApproval'
    | 'waitingForDepositReceipt'
    | null
  >(null)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const { writeContractAsync } = useWriteContract()

  const canSubmit = (function () {
    if (submitPhase !== null) return false

    try {
      if (walletBalanceQuery.status == 'success') {
        const parsedAmount = parseUnits(amount, symbol.decimals)
        const availableAmount = walletBalanceQuery.data
        return parsedAmount > 0 && parsedAmount <= availableAmount
      } else {
        return false
      }
    } catch {
      return false
    }
  })()

  async function onSubmit() {
    const parsedAmount = parseUnits(amount, symbol.decimals)
    if (canSubmit) {
      try {
        if (symbol.contractAddress) {
          setSubmitPhase('waitingForAllowanceApproval')
          const approveHash = await writeContractAsync({
            abi: ERC20Abi,
            address: symbol.contractAddress,
            functionName: 'approve',
            args: [exchangeContractAddress, parsedAmount]
          })

          setSubmitPhase('waitingForAllowanceReceipt')
          await waitForTransactionReceipt(config, {
            hash: approveHash
          })

          setSubmitPhase('waitingForDepositApproval')
          const depositHash = await writeContractAsync({
            abi: ExchangeAbi,
            address: exchangeContractAddress,
            functionName: 'deposit',
            args: [symbol.contractAddress, parsedAmount]
          })

          setSubmitPhase('waitingForDepositReceipt')
          await waitForTransactionReceipt(config, {
            hash: depositHash
          })
        } else {
          setSubmitPhase('waitingForDepositApproval')
          const hash = await sendTransaction(config, {
            to: exchangeContractAddress,
            value: parsedAmount
          })

          setSubmitPhase('waitingForDepositReceipt')
          await waitForTransactionReceipt(config, { hash })
        }

        close()
      } catch (err) {
        setSubmitError(
          (err as BaseError).shortMessage || 'Something went wrong'
        )
        setSubmitPhase(null)
      }
    }
  }

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
                  value={amount}
                  symbol={symbol.name}
                  disabled={submitPhase !== null}
                  onChange={(e) =>
                    setAmount(cleanAndFormatNumberInput(e.target.value))
                  }
                />
                <p className="mt-1 text-center text-sm text-neutralGray">
                  Available balance:{' '}
                  {formatUnits(walletBalance, symbol.decimals)}
                </p>

                <SubmitButton
                  disabled={!canSubmit}
                  onClick={onSubmit}
                  error={submitError}
                  caption={() => {
                    switch (submitPhase) {
                      case 'waitingForAllowanceApproval':
                        return 'Waiting for allowance approval'
                      case 'waitingForAllowanceReceipt':
                        return 'Waiting for allowance receipt'
                      case 'waitingForDepositApproval':
                        return 'Waiting for deposit approval'
                      case 'waitingForDepositReceipt':
                        return 'Waiting for deposit receipt'
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
