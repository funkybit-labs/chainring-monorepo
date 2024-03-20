import { Address, formatUnits, parseUnits } from 'viem'
import { BaseError, useConfig, useWriteContract } from 'wagmi'
import { ERC20Abi, ExchangeAbi } from 'contracts'
import { useEffect, useState } from 'react'
import {
  getBalance,
  readContract,
  sendTransaction,
  waitForTransactionReceipt
} from 'wagmi/actions'
import { AsyncData, Modal, ModalAsyncContent } from 'components/common/Modal'
import AmountInput from 'components/common/AmountInput'
import SubmitButton from 'components/common/SubmitButton'
import { Token } from 'ApiClient'

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
  token: Token
  isOpen: boolean
  close: () => void
  onClosed: () => void
}) {
  const config = useConfig()
  const [walletBalance, setWalletBalance] = useState<AsyncData<bigint>>({
    status: 'pending'
  })

  useEffect(() => {
    setWalletBalance({ status: 'pending' })

    const balancePromise =
      'address' in token
        ? readContract(config, {
            abi: ERC20Abi,
            address: token.address,
            functionName: 'balanceOf',
            args: [walletAddress]
          })
        : getBalance(config, {
            address: walletAddress
          }).then((res) => res.value)

    balancePromise
      .then((amount) => {
        setWalletBalance({ status: 'success', data: amount })
      })
      .catch(() => {
        setWalletBalance({ status: 'error' })
      })
  }, [config, walletAddress, token])

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

  function onAmountChange(e: React.ChangeEvent<HTMLInputElement>) {
    setAmount(e.target.value)
  }

  const canSubmit = (function () {
    if (submitPhase !== null) return false

    try {
      if ('data' in walletBalance) {
        const parsedAmount = parseUnits(amount, token.decimals)
        const availableAmount = walletBalance.data
        return parsedAmount > 0 && parsedAmount <= availableAmount
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
        if ('address' in token) {
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
      title={`Deposit ${token.symbol}`}
    >
      <div className="h-52 overflow-y-auto">
        <ModalAsyncContent
          asyncData={walletBalance}
          success={(data) => {
            return (
              <>
                <AmountInput
                  value={amount}
                  symbol={token.symbol}
                  disabled={submitPhase !== null}
                  onChange={onAmountChange}
                />
                <p className="mt-1 text-center text-sm text-neutralGray">
                  Available balance: {formatUnits(data, token.decimals)}
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
