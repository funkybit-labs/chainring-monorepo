import { Address, formatUnits, zeroAddress } from 'viem'
import { BaseError as WagmiError, useConfig, useSignTypedData } from 'wagmi'
import { ExchangeAbi } from 'contracts'
import React, { useState } from 'react'
import { readContract } from 'wagmi/actions'
import { Modal, ModalAsyncContent } from 'components/common/Modal'
import AmountInput from 'components/common/AmountInput'
import SubmitButton from 'components/common/SubmitButton'
import { apiClient } from 'apiClient'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { addressZero, getDomain, getWithdrawMessage } from 'utils/eip712'
import useAmountInputState from 'hooks/useAmountInputState'
import { withdrawalsQueryKey } from 'components/Screens/HomeScreen/balances/BalancesWidget'
import { isErrorFromAlias } from '@zodios/core'
import TradingSymbol from 'tradingSymbol'

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
      return await readContract(config, {
        abi: ExchangeAbi,
        address: exchangeContractAddress,
        functionName: 'balances',
        args: [
          walletAddress,
          symbol.contractAddress ? symbol.contractAddress : zeroAddress
        ]
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

  const [withdrawAll, setWithdrawAll] = useState<boolean>(false)

  const queryClient = useQueryClient()

  const mutation = useMutation({
    mutationFn: async () => {
      try {
        const nonce = Date.now()
        setSubmitPhase('waitingForApproval')
        const signature = await signTypedDataAsync({
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
          message: getWithdrawMessage(
            walletAddress,
            symbol.contractAddress ? symbol.contractAddress : addressZero,
            withdrawAll && amount === availableBalanceQuery.data
              ? BigInt(0)
              : amount,
            BigInt(nonce)
          )
        })

        setSubmitPhase('submittingRequest')
        return await apiClient.createWithdrawal({
          symbol: symbol.name,
          amount:
            withdrawAll && amount === availableBalanceQuery.data
              ? BigInt(0)
              : amount,
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
    onError: () => {
      setTimeout(mutation.reset, 3000)
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
      title={`Withdraw ${symbol.displayName()}`}
    >
      <div className="max-h-52 overflow-y-auto">
        <ModalAsyncContent
          asyncData={availableBalanceQuery}
          success={(availableBalance) => {
            return (
              <div>
                <div className="relative">
                  <AmountInput
                    value={amountInputValue}
                    disabled={submitPhase !== null}
                    onChange={(e) => setAmountInputValue(e.target.value)}
                    className="!rounded-md !bg-darkBluishGray8 !text-center !text-white !placeholder-lightBackground !ring-darkBluishGray8"
                  />
                  <button
                    className="absolute right-3 top-3 rounded bg-darkBluishGray6 px-2 py-1 text-xs text-darkBluishGray2 hover:bg-blue5"
                    onClick={() => {
                      setAmountInputValue(
                        formatUnits(availableBalance, symbol.decimals)
                      )
                      setWithdrawAll(true)
                    }}
                  >
                    100%
                  </button>
                </div>

                <p className="mb-2 mt-4 text-center text-sm text-darkBluishGray2">
                  Wallet balance:{' '}
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
                  status={mutation.status}
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
