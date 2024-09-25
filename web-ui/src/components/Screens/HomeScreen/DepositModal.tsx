import { Address, formatUnits } from 'viem'
import { BaseError as WagmiError, useConfig } from 'wagmi'
import { ERC20Abi, ExchangeAbi } from 'contracts'
import { useMemo, useState } from 'react'
import {
  call,
  getBalance,
  readContract,
  sendTransaction,
  waitForTransactionReceipt
} from 'wagmi/actions'
import { Modal, ModalAsyncContent } from 'components/common/Modal'
import AmountInput from 'components/common/AmountInput'
import SubmitButton from 'components/common/SubmitButton'
import { apiClient, evmAddress } from 'apiClient'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import useAmountInputState from 'hooks/useAmountInputState'
import { depositsQueryKey } from 'components/Screens/HomeScreen/balances/BalancesWidget'
import { encodeFunctionData, EncodeFunctionDataParameters } from 'viem'
import { isErrorFromAlias } from '@zodios/core'
import TradingSymbol from 'tradingSymbol'
import { ExpandableValue } from 'components/common/ExpandableValue'
import { minBigInt } from 'utils'
import { accountConfigQueryKey } from 'components/Screens/HomeScreen'
import Wallet from 'sats-connect'
import { request } from 'sats-connect'

export default function DepositModal({
  depositAddress,
  walletAddress,
  symbol,
  testnetChallengeDepositLimit,
  isOpen,
  close,
  onClosed,
  initialAmount,
  title,
  message
}: {
  depositAddress: string
  walletAddress: string
  symbol: TradingSymbol
  testnetChallengeDepositLimit?: bigint
  isOpen: boolean
  close: () => void
  onClosed: () => void
  initialAmount?: string
  title?: string
  message?: string
}) {
  const config = useConfig()

  const walletBalanceQuery = useQuery({
    queryKey: ['walletBalance', symbol.name],
    queryFn: async function () {
      if (symbol.networkType === 'Bitcoin') {
        return request('getBalance', undefined).then((result) => {
          if (result.status == 'success') {
            return BigInt(result.result.confirmed)
          } else {
            console.log(result.error)
            throw Error('Failed to obtain balance')
          }
        })
      } else {
        return symbol.contractAddress
          ? await readContract(config, {
              abi: ERC20Abi,
              address: evmAddress(symbol.contractAddress),
              functionName: 'balanceOf',
              args: [walletAddress as Address]
            })
          : getBalance(config, {
              address: walletAddress as Address
            }).then((res) => res.value)
      }
    }
  })

  const {
    inputValue: amountInputValue,
    setInputValue: setAmountInputValue,
    valueInFundamentalUnits: amount
  } = useAmountInputState({
    initialInputValue: initialAmount ?? '',
    decimals: symbol.decimals
  })

  const [submitPhase, setSubmitPhase] = useState<
    | 'checkingAllowanceAmount'
    | 'waitingForAllowanceApproval'
    | 'waitingForAllowanceReceipt'
    | 'waitingForDepositApproval'
    | 'submittingDeposit'
    | null
  >(null)

  const queryClient = useQueryClient()

  const mutation = useMutation({
    mutationFn: async () => {
      try {
        let depositHash: string
        if (symbol.networkType == 'Evm') {
          if (symbol.contractAddress) {
            setSubmitPhase('checkingAllowanceAmount')
            const allowance = await call(config, {
              to: evmAddress(symbol.contractAddress),
              chainId: symbol.chainId,
              data: encodeFunctionData({
                abi: ERC20Abi,
                args: [walletAddress as Address, depositAddress as Address],
                functionName: 'allowance'
              })
            })

            const allowanceAmount = allowance.data ? BigInt(allowance.data) : 0n

            if (allowanceAmount < amount) {
              setSubmitPhase('waitingForAllowanceApproval')
              const hash = await sendTransaction(config, {
                to: evmAddress(symbol.contractAddress),
                chainId: symbol.chainId,
                data: encodeFunctionData({
                  abi: ERC20Abi,
                  args: [depositAddress, amount],
                  functionName: 'approve'
                } as EncodeFunctionDataParameters)
              })
              setSubmitPhase('waitingForAllowanceReceipt')
              await waitForTransactionReceipt(config, { hash })
            }

            setSubmitPhase('waitingForDepositApproval')
            depositHash = await sendTransaction(config, {
              to: depositAddress as Address,
              chainId: symbol.chainId,
              data: encodeFunctionData({
                abi: ExchangeAbi,
                functionName: 'deposit',
                args: [evmAddress(symbol.contractAddress!), amount]
              })
            })
          } else {
            setSubmitPhase('waitingForDepositApproval')
            depositHash = await sendTransaction(config, {
              to: depositAddress as Address,
              value: amount
            })
          }
        } else {
          setSubmitPhase('waitingForDepositApproval')
          const response = await Wallet.request('sendTransfer', {
            recipients: [
              {
                address: depositAddress,
                amount: Number(amount)
              }
            ]
          })
          if (response.status === 'success') {
            depositHash = response.result.txid
          } else {
            console.log('sendTransfer failed with error', response.error)
            throw Error('sendTransfer failed')
          }
        }

        return await apiClient.createDeposit({
          symbol: symbol.name,
          amount: amount,
          txHash: depositHash
        })
      } catch (error) {
        setSubmitPhase(null)
        throw Error(
          isErrorFromAlias(apiClient.api, 'createDeposit', error)
            ? error.response.data.errors[0].displayMessage
            : (error as WagmiError).shortMessage || 'Something went wrong'
        )
      }
    },
    onError: () => {
      setTimeout(mutation.reset, 3000)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: depositsQueryKey })
      // this is needed so that deposit button status is updated
      // for Testnet challenge participant
      queryClient.invalidateQueries({ queryKey: accountConfigQueryKey })
      close()
    }
  })

  const canSubmit = useMemo(() => {
    if (submitPhase !== null) return false

    try {
      if (walletBalanceQuery.status == 'success') {
        const availableAmount =
          testnetChallengeDepositLimit !== undefined
            ? minBigInt(testnetChallengeDepositLimit, walletBalanceQuery.data)
            : walletBalanceQuery.data
        return amount > 0 && amount <= availableAmount
      } else {
        return false
      }
    } catch {
      return false
    }
  }, [submitPhase, walletBalanceQuery, amount, testnetChallengeDepositLimit])

  return (
    <Modal
      isOpen={isOpen}
      close={close}
      onClosed={onClosed}
      title={title ?? `Deposit ${symbol.displayName()}`}
    >
      <div className="max-h-56 overflow-y-auto">
        <ModalAsyncContent
          asyncData={walletBalanceQuery}
          success={(walletBalance) => {
            return (
              <div>
                {message && (
                  <p className="my-2 text-center text-sm text-white">
                    {message}
                  </p>
                )}
                {testnetChallengeDepositLimit !== undefined && (
                  <p className="mb-4 text-center text-sm text-white">
                    During the Testnet Challenge, deposits are limited to
                    previously withdrawn assets. Current deposit limit:{' '}
                    <ExpandableValue
                      value={formatUnits(
                        testnetChallengeDepositLimit,
                        symbol.decimals
                      )}
                    />
                  </p>
                )}
                <AmountInput
                  value={amountInputValue}
                  disabled={submitPhase !== null}
                  onChange={(e) => setAmountInputValue(e.target.value)}
                  className="!rounded-md !bg-darkBluishGray8 !text-center !text-white !placeholder-lightBackground !ring-darkBluishGray8"
                />
                <p className="mb-2 mt-4 text-center text-sm text-darkBluishGray2">
                  Wallet balance:{' '}
                  <ExpandableValue
                    value={formatUnits(walletBalance, symbol.decimals)}
                  />
                </p>

                <SubmitButton
                  disabled={!canSubmit}
                  onClick={mutation.mutate}
                  error={mutation.error?.message}
                  caption={() => {
                    switch (submitPhase) {
                      case 'checkingAllowanceAmount':
                        return 'Preparing deposit'
                      case 'waitingForAllowanceApproval':
                        return 'Waiting for allowance approval'
                      case 'waitingForAllowanceReceipt':
                        return 'Waiting for allowance transaction '
                      case 'waitingForDepositApproval':
                        return 'Waiting for deposit approval'
                      case 'submittingDeposit':
                        return 'Submitting Deposit'
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
