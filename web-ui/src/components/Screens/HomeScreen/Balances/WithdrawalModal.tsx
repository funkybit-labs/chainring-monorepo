import { Address, formatUnits, parseUnits } from 'viem'
import { BaseError, useConfig, useWriteContract } from 'wagmi'
import { ExchangeAbi } from 'contracts'
import { useEffect, useState } from 'react'
import { readContract, waitForTransactionReceipt } from 'wagmi/actions'
import { AsyncData, Modal, ModalAsyncContent } from 'components/common/Modal'
import AmountInput from 'components/common/AmountInput'
import SubmitButton from 'components/common/SubmitButton'
import { Token } from 'ApiClient'

export default function WithdrawalModal({
  exchangeContractAddress,
  walletAddress,
  token,
  isOpen,
  close,
  onClosed
}: {
  exchangeContractAddress: Address
  walletAddress: Address
  token: Token
  isOpen: boolean
  close: () => void
  onClosed: () => void
}) {
  const config = useConfig()
  const [availableBalance, setAvailableBalance] = useState<AsyncData<bigint>>({
    status: 'pending'
  })

  useEffect(() => {
    setAvailableBalance({ status: 'pending' })

    const balancePromise =
      'address' in token
        ? readContract(config, {
            abi: ExchangeAbi,
            address: exchangeContractAddress,
            functionName: 'balances',
            args: [walletAddress, token.address]
          })
        : readContract(config, {
            abi: ExchangeAbi,
            address: exchangeContractAddress,
            functionName: 'nativeBalances',
            args: [walletAddress]
          })

    balancePromise
      .then((amount) => {
        setAvailableBalance({ status: 'success', data: amount })
      })
      .catch(() => {
        setAvailableBalance({ status: 'error' })
      })
  }, [config, exchangeContractAddress, walletAddress, token])

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
      if ('data' in availableBalance) {
        const parsedAmount = parseUnits(amount, token.decimals)
        return parsedAmount > 0 && parsedAmount <= availableBalance.data
      } else {
        return false
      }
    } catch {
      return false
    }
  })()

  async function onSubmit() {
    const parsedAmount = parseUnits(amount, token.decimals)
    if (canSubmit) {
      try {
        setSubmitPhase('waitingForTxApproval')
        const hash =
          'address' in token
            ? await writeContractAsync({
                abi: ExchangeAbi,
                address: exchangeContractAddress,
                functionName: 'withdraw',
                args: [token.address, parsedAmount]
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
      title={`Withdraw ${token.symbol}`}
    >
      <div className="h-52 overflow-y-auto">
        <ModalAsyncContent
          asyncData={availableBalance}
          success={(data) => {
            return (
              <>
                <AmountInput
                  value={amount}
                  symbol={token.symbol}
                  disabled={submitPhase !== null}
                  onChange={onAmountChange}
                />

                <p className="mt-1 text-center text-sm text-darkGray">
                  Available balance: {formatUnits(data, token.decimals)}
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
