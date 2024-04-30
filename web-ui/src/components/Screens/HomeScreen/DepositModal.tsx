import { Address, formatUnits } from 'viem'
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
import { TradingSymbol } from 'apiClient'
import { useQuery } from '@tanstack/react-query'
import useAmountInputState from 'hooks/useAmountInputState'

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
    | 'waitingForAllowanceReceipt'
    | 'waitingForDepositApproval'
    | 'waitingForDepositReceipt'
    | 'submittingDeposit'
    | null
  >(null)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const { writeContractAsync } = useWriteContract()

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

  async function onSubmit() {
    setSubmitError(null)
    if (canSubmit) {
      try {
        if (symbol.contractAddress) {
          setSubmitPhase('waitingForAllowanceApproval')
          const approveHash = await writeContractAsync({
            abi: ERC20Abi,
            address: symbol.contractAddress,
            functionName: 'approve',
            args: [exchangeContractAddress, amount]
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
            args: [symbol.contractAddress, amount]
          })

          setSubmitPhase('waitingForDepositReceipt')
          await waitForTransactionReceipt(config, {
            hash: depositHash
          })
        } else {
          setSubmitPhase('waitingForDepositApproval')
          const hash = await sendTransaction(config, {
            to: exchangeContractAddress,
            value: amount
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
