import { Address, formatUnits, zeroAddress } from 'viem'
import { BaseError as WagmiError, useConfig, useSignTypedData } from 'wagmi'
import { ExchangeAbi } from 'contracts'
import { useState } from 'react'
import { readContract } from 'wagmi/actions'
import { Modal, ModalAsyncContent } from 'components/common/Modal'
import AmountInput from 'components/common/AmountInput'
import SubmitButton from 'components/common/SubmitButton'
import { apiClient, TradingSymbol } from 'apiClient'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  getDomain,
  getERC20WithdrawMessage,
  getNativeWithdrawMessage
} from 'utils/eip712'
import useAmountInputState from 'hooks/useAmountInputState'
import { withdrawalsQueryKey } from 'components/Screens/HomeScreen/balances/BalancesWidget'
import { isErrorFromAlias } from '@zodios/core'

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
            functionName: 'balances',
            args: [walletAddress, zeroAddress]
          })
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
    'waitingForApproval' | 'submittingRequest' | null
  >(null)

  const queryClient = useQueryClient()

  const mutation = useMutation({
    mutationFn: async () => {
      try {
        const nonce = Date.now()
        setSubmitPhase('waitingForApproval')
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
                amount,
                BigInt(nonce)
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
                amount,
                BigInt(nonce)
              )
            })

        setSubmitPhase('submittingRequest')
        return await apiClient.createWithdrawal({
          symbol: symbol.name,
          amount: amount,
          nonce: nonce,
          signature: signature
        })
      } catch (error) {
        setSubmitPhase(null)
        throw Error(
          isErrorFromAlias(apiClient.api, 'createWithdrawal', error)
            ? error.response.data.errors[0].displayMessage
            : (error as WagmiError).shortMessage || 'Something went wrong'
        )
      }
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: withdrawalsQueryKey })
      close()
    }
  })

  const canSubmit = (function () {
    if (submitPhase !== null) return false

    try {
      if (availableBalanceQuery.status == 'success') {
        return amount > 0 && amount <= availableBalanceQuery.data
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
      title={`Withdraw ${symbol.name}`}
    >
      <div className="h-52 overflow-y-auto">
        <ModalAsyncContent
          asyncData={availableBalanceQuery}
          success={(availableBalance) => {
            return (
              <div className="mt-8">
                <AmountInput
                  value={amountInputValue}
                  symbol={symbol.name}
                  disabled={submitPhase !== null}
                  onChange={(e) => setAmountInputValue(e.target.value)}
                />

                <p className="mt-1 text-center text-sm text-darkGray">
                  Available balance:{' '}
                  {formatUnits(availableBalance, symbol.decimals)}
                </p>

                <SubmitButton
                  disabled={!canSubmit}
                  onClick={mutation.mutate}
                  error={mutation.error?.message}
                  caption={() => {
                    switch (submitPhase) {
                      case 'waitingForApproval':
                        return 'Waiting for approval'
                      case 'submittingRequest':
                        return 'Submitting request'
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
