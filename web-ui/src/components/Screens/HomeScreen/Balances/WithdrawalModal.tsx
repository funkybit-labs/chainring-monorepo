import { Address, formatUnits, parseUnits } from 'viem'
import { ERC20Token } from 'ApiClient'
import { BaseError, useConfig, useReadContract, useWriteContract } from 'wagmi'
import { ExchangeAbi } from 'contracts'
import { useState } from 'react'
import { waitForTransactionReceipt } from 'wagmi/actions'
import { Dialog } from '@headlessui/react'
import { Modal, ModalAsyncContent } from 'components/common/Modal'
import AmountInput from 'components/common/AmountInput'
import SubmitButton from 'components/common/SubmitButton'

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
  token: ERC20Token
  isOpen: boolean
  close: () => void
  onClosed: () => void
}) {
  const availableBalanceQuery = useReadContract({
    abi: ExchangeAbi,
    address: exchangeContractAddress,
    functionName: 'balances',
    args: [walletAddress, token.address]
  })

  const [amount, setAmount] = useState('')
  const [submitPhase, setSubmitPhase] = useState<
    'waitingForTxApproval' | 'waitingForTxReceipt' | null
  >(null)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const { writeContractAsync } = useWriteContract()
  const config = useConfig()

  function onAmountChange(e: React.ChangeEvent<HTMLInputElement>) {
    setAmount(e.target.value)
  }

  const canSubmit = (function () {
    if (submitPhase !== null) return false

    try {
      if (availableBalanceQuery.data === undefined) {
        return false
      } else {
        const parsedAmount = parseUnits(amount, token.decimals)
        return parsedAmount > 0 && parsedAmount <= availableBalanceQuery.data
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
        const hash = await writeContractAsync({
          abi: ExchangeAbi,
          address: exchangeContractAddress,
          functionName: 'withdraw',
          args: [token.address, parsedAmount]
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
    <Modal isOpen={isOpen} close={close} onClosed={onClosed}>
      <Dialog.Title
        as="h3"
        className="text-gray-900 text-lg font-medium leading-6"
      >
        Withdraw {token.symbol}
      </Dialog.Title>

      <div className="h-52 overflow-y-auto">
        <ModalAsyncContent
          query={availableBalanceQuery}
          success={(data) => {
            return (
              <>
                <AmountInput
                  value={amount}
                  symbol={token.symbol}
                  disabled={submitPhase !== null}
                  onChange={onAmountChange}
                />

                <p className="text-gray-500 mt-1 text-center text-sm">
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
