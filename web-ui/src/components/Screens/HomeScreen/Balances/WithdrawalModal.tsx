import { Address, formatUnits, parseUnits } from 'viem'
import { BaseError, useConfig, useSignTypedData } from 'wagmi'
import { ExchangeAbi } from 'contracts'
import { useEffect, useState } from 'react'
import { readContract } from 'wagmi/actions'
import { Modal, ModalAsyncContent } from 'components/common/Modal'
import AmountInput from 'components/common/AmountInput'
import SubmitButton from 'components/common/SubmitButton'
import { apiClient, TradingSymbol } from 'ApiClient'
import { useQuery, useMutation } from '@tanstack/react-query'
import {
  getDomain,
  getERC20WithdrawMessage,
  getNativeWithdrawMessage
} from 'utils/eip712'
import { cleanAndFormatNumberInput } from 'utils'

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
  const { signTypedDataAsync } = useSignTypedData()

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

  const [withdrawalId, setWithdrawalId] = useState<string | null>(null)
  const [amount, setAmount] = useState('')
  const [submitPhase, setSubmitPhase] = useState<
    | 'fetchingNonce'
    | 'waitingForTxSignature'
    | 'submittingRequest'
    | 'waitingForCompletion'
    | null
  >(null)
  const [submitError, setSubmitError] = useState<string | null>(null)

  const withdrawalQuery = useQuery({
    queryKey: ['withdrawal'],
    queryFn: () => apiClient.getWithdrawal({ params: { id: withdrawalId! } }),
    enabled: !!withdrawalId,
    refetchInterval: withdrawalId ? 1000 : undefined
  })

  const mutation = useMutation({
    mutationFn: apiClient.createWithdrawal
  })

  useEffect(() => {
    if (mutation.isPending) {
      return
    }
    if (mutation.isError) {
      setError(mutation.error.message)
    } else if (mutation.isSuccess) {
      setSubmitPhase('waitingForCompletion')
      setWithdrawalId(mutation.data.withdrawal.id)
    }
    mutation.reset()
  }, [mutation])

  useEffect(() => {
    if (
      !withdrawalId ||
      !withdrawalQuery.data ||
      withdrawalId != withdrawalQuery.data.withdrawal.id ||
      withdrawalQuery.data.withdrawal.status == 'Pending'
    ) {
      return
    }
    const status = withdrawalQuery.data.withdrawal.status
    if (status == 'Complete') {
      close()
    } else if (status == 'Failed') {
      setError(withdrawalQuery.data!.withdrawal.error)
    }
    setWithdrawalId(null)
  }, [withdrawalId, withdrawalQuery, close])

  function setError(error: string | null) {
    setSubmitError(error)
    setSubmitPhase(null)
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
    setSubmitError(null)
    const parsedAmount = parseUnits(amount, symbol.decimals)
    if (canSubmit) {
      try {
        setSubmitPhase('fetchingNonce')
        const nonce = await readContract(config, {
          abi: ExchangeAbi,
          address: exchangeContractAddress,
          functionName: 'nonces',
          args: [walletAddress]
        })

        setSubmitPhase('waitingForTxSignature')
        const signature = symbol.contractAddress
          ? await signTypedDataAsync({
              types: {
                EIP712Domain: [
                  { name: 'name', type: 'string' },
                  { name: 'version', type: 'string' },
                  { name: 'chainId', type: 'uint256' },
                  { name: 'verifyingContract', type: 'address' }
                ],
                Withdraw: [
                  { name: 'sender', type: 'address' },
                  { name: 'token', type: 'address' },
                  { name: 'amount', type: 'uint256' },
                  { name: 'nonce', type: 'uint64' }
                ]
              },
              domain: getDomain(exchangeContractAddress, config.state.chainId),
              primaryType: 'Withdraw',
              message: getERC20WithdrawMessage(
                walletAddress,
                symbol.contractAddress!,
                BigInt(parsedAmount),
                nonce
              )
            })
          : await signTypedDataAsync({
              types: {
                EIP712Domain: [
                  { name: 'name', type: 'string' },
                  { name: 'version', type: 'string' },
                  { name: 'chainId', type: 'uint256' },
                  { name: 'verifyingContract', type: 'address' }
                ],
                Withdraw: [
                  { name: 'sender', type: 'address' },
                  { name: 'amount', type: 'uint256' },
                  { name: 'nonce', type: 'uint64' }
                ]
              },
              domain: getDomain(exchangeContractAddress, config.state.chainId),
              primaryType: 'Withdraw',
              message: getNativeWithdrawMessage(
                walletAddress,
                BigInt(parsedAmount),
                nonce
              )
            })

        setSubmitPhase('submittingRequest')
        mutation.mutate({
          tx: {
            sender: walletAddress,
            token: symbol.contractAddress,
            amount: BigInt(parsedAmount),
            nonce: BigInt(nonce)
          },
          signature: signature
        })
      } catch (err) {
        setError((err as BaseError).shortMessage || 'Something went wrong')
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
              <div className="mt-8">
                <AmountInput
                  value={amount}
                  symbol={symbol.name}
                  disabled={submitPhase !== null}
                  onChange={(e) =>
                    setAmount(cleanAndFormatNumberInput(e.target.value))
                  }
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
                      case 'fetchingNonce':
                        return 'Fetching nonce'
                      case 'waitingForTxSignature':
                        return 'Waiting for Tx Signature'
                      case 'submittingRequest':
                        return 'Submitting request'
                      case 'waitingForCompletion':
                        return 'Waiting for completion'
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
