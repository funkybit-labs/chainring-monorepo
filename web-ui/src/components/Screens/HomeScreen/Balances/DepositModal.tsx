import { Address, formatUnits, parseUnits } from 'viem'
import { ERC20Token } from 'ApiClient'
import { BaseError, useConfig, useReadContract, useWriteContract } from 'wagmi'
import { ERC20Abi, ExchangeAbi } from 'contracts'
import { useState } from 'react'
import { waitForTransactionReceipt } from 'wagmi/actions'
import { Dialog } from '@headlessui/react'
import { Modal, ModalAsyncContent } from 'components/common/Modal'
import AmountInput from 'components/common/AmountInput'
import SubmitButton from 'components/common/SubmitButton'

export default function DepositModal({
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
  const walletBalanceQuery = useReadContract({
    abi: ERC20Abi,
    address: token.address,
    functionName: 'balanceOf',
    args: [walletAddress]
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
  const config = useConfig()

  function onAmountChange(e: React.ChangeEvent<HTMLInputElement>) {
    setAmount(e.target.value)
  }

  const canSubmit = (function () {
    if (submitPhase !== null) return false

    try {
      if (walletBalanceQuery.data === undefined) {
        return false
      } else {
        const parsedAmount = parseUnits(amount, 18)
        return parsedAmount > 0 && parsedAmount <= walletBalanceQuery.data
      }
    } catch {
      return false
    }
  })()

  async function onSubmit() {
    const parsedAmount = parseUnits(amount, 18)
    if (canSubmit) {
      try {
        setSubmitPhase('waitingForAllowanceApproval')
        const approveHash = await writeContractAsync({
          abi: ERC20Abi,
          address: token.address,
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
          args: [token.address, parsedAmount]
        })

        setSubmitPhase('waitingForDepositReceipt')
        await waitForTransactionReceipt(config, {
          hash: depositHash
        })

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
        className="text-lg font-medium leading-6 text-gray-900"
      >
        Deposit {token.symbol}
      </Dialog.Title>

      <div className="h-52 overflow-y-auto">
        <ModalAsyncContent
          query={walletBalanceQuery}
          success={(data) => {
            return (
              <>
                <AmountInput
                  value={amount}
                  symbol={token.symbol}
                  onChange={onAmountChange}
                />
                <p className="mt-1 text-center text-sm text-gray-500">
                  Available balance: {formatUnits(data, 18)}
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
