import { Address, formatUnits, parseUnits } from 'viem'
import { BaseError, useConfig, useWriteContract } from 'wagmi'
import { ExchangeAbi } from 'contracts'
import { useState } from 'react'
import { readContract, waitForTransactionReceipt } from 'wagmi/actions'
import { Modal, ModalAsyncContent } from 'components/common/Modal'
import AmountInput from 'components/common/AmountInput'
import SubmitButton from 'components/common/SubmitButton'
import { TradingSymbol } from 'ApiClient'
import { useQuery } from '@tanstack/react-query'

export default function WithdrawalModal({
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

  const availableBalanceQuery = useQuery({
    queryKey: ['availableBalance', symbol.name],
    queryFn: async function () {
      return symbol.contractAddress
        ? await readContract(config, {
            abi: ExchangeAbi,
            address: exchangeContractAddress,
            functionName: 'balances',
            args: [walletAddress, symbol.contractAddress]
          })
        : await readContract(config, {
            abi: ExchangeAbi,
            address: exchangeContractAddress,
            functionName: 'nativeBalances',
            args: [walletAddress]
          })
    }
  })

  const [amount, setAmount] = useState('')
  const [submitPhase, setSubmitPhase] = useState<
    'waitingForTxApproval' | 'waitingForTxReceipt' | null
  >(null)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const { writeContractAsync } = useWriteContract()

  function onAmountChange(e: React.ChangeEvent<HTMLInputElement>) {
    setAmount(e.target.value)
  }

  const canSubmit = (function () {
    if (submitPhase !== null) return false

    try {
      if (availableBalanceQuery.status == 'success') {
        const parsedAmount = parseUnits(amount, symbol.decimals)
        return parsedAmount > 0 && parsedAmount <= availableBalanceQuery.data
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
        setSubmitPhase('waitingForTxApproval')
        const hash = symbol.contractAddress
          ? await writeContractAsync({
              abi: ExchangeAbi,
              address: exchangeContractAddress,
              functionName: 'withdraw',
              args: [symbol.contractAddress, parsedAmount]
            })
          : await writeContractAsync({
              abi: ExchangeAbi,
              address: exchangeContractAddress,
              functionName: 'withdraw',
              args: [parsedAmount]
            })

        setSubmitPhase('waitingForTxReceipt')
        await waitForTransactionReceipt(config, { hash })

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
      title={`Withdraw ${symbol.name}`}
    >
      <div className="h-52 overflow-y-auto">
        <ModalAsyncContent
          asyncData={availableBalanceQuery}
          success={(availableBalance) => {
            return (
              <>
                <AmountInput
                  value={amount}
                  symbol={symbol.name}
                  disabled={submitPhase !== null}
                  onChange={onAmountChange}
                />

                <p className="mt-1 text-center text-sm text-darkGray">
                  Available balance:{' '}
                  {formatUnits(availableBalance, symbol.decimals)}
                </p>

                <SubmitButton
                  disabled={!canSubmit}
                  onClick={onSubmit}
                  error={submitError}
                  caption={() => {
                    switch (submitPhase) {
                      case 'waitingForTxApproval':
                        return 'Waiting for transaction approval'
                      case 'waitingForTxReceipt':
                        return 'Waiting for transaction receipt'
                      case null:
                        return 'Submit'
                    }
                  }}
                />
              </>
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
