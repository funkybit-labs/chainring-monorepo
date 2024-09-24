import { formatUnits } from 'viem'
import { BaseError as WagmiError } from 'wagmi'
import React, { useState } from 'react'
import { Modal, ModalAsyncContent } from 'components/common/Modal'
import AmountInput from 'components/common/AmountInput'
import SubmitButton from 'components/common/SubmitButton'
import { apiClient } from 'apiClient'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import useAmountInputState from 'hooks/useAmountInputState'
import { withdrawalsQueryKey } from 'components/Screens/HomeScreen/balances/BalancesWidget'
import { isErrorFromAlias } from '@zodios/core'
import TradingSymbol from 'tradingSymbol'
import { signWithdrawal } from 'utils/signingUtils'

export default function WithdrawalModal({
  exchangeContractAddress,
  walletAddress,
  symbol,
  isOpen,
  close,
  onClosed
}: {
  exchangeContractAddress: string
  walletAddress: string
  symbol: TradingSymbol
  isOpen: boolean
  close: () => void
  onClosed: () => void
}) {
  const availableBalanceQuery = useQuery({
    queryKey: ['availableBalance', symbol.name],
    queryFn: async function () {
      return apiClient
        .getBalances()
        .then(
          (response) =>
            response.balances.find((b) => b.symbol == symbol.name)?.available ||
            0n
        )
    }
  })

  const {
    inputValue: amountInputValue,
    setInputValue: setAmountInputValue,
    valueInFundamentalUnits: enteredAmount
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
        setSubmitPhase('waitingForApproval')

        const timestamp = new Date()
        const nonce = timestamp.getTime()
        const amount =
          withdrawAll && enteredAmount === availableBalanceQuery.data
            ? BigInt(0)
            : enteredAmount

        const signature = await signWithdrawal(
          symbol,
          walletAddress,
          exchangeContractAddress,
          timestamp,
          amount
        )

        if (signature === null) {
          setSubmitPhase(null)
          return { withdrawal: null }
        }

        setSubmitPhase('submittingRequest')
        return await apiClient.createWithdrawal({
          symbol: symbol.name,
          amount,
          nonce,
          signature
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
    onSuccess: (result) => {
      if (result.withdrawal) {
        queryClient.invalidateQueries({ queryKey: withdrawalsQueryKey })
        close()
      } else {
        mutation.reset()
      }
    }
  })

  const canSubmit = (function () {
    if (submitPhase !== null) return false

    try {
      if (availableBalanceQuery.status == 'success') {
        return enteredAmount > 0 && enteredAmount <= availableBalanceQuery.data
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
