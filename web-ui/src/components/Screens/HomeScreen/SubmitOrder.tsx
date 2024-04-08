import { apiClient, OrderSide, TradingSymbol } from 'apiClient'
import { classNames, cleanAndFormatNumberInput } from 'utils'
import React, { useEffect, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Widget } from 'components/common/Widget'
import SubmitButton from 'components/common/SubmitButton'
import { Address, parseUnits } from 'viem'
import { isErrorFromAlias } from '@zodios/core'
import { useConfig, useSignTypedData } from 'wagmi'
import { addressZero, getDomain } from 'utils/eip712'
import { Market } from 'markets'
import Decimal from 'decimal.js'

export default function SubmitOrder({
  market,
  exchangeContractAddress,
  walletAddress,
  baseSymbol,
  quoteSymbol
}: {
  market: Market
  exchangeContractAddress: Address
  walletAddress: Address
  baseSymbol: TradingSymbol
  quoteSymbol: TradingSymbol
}) {
  const config = useConfig()
  const { signTypedDataAsync } = useSignTypedData()

  const [side, setSide] = useState<OrderSide>('Buy')
  const [price, setPrice] = useState('')
  const [amount, setAmount] = useState('')
  const [isMarketOrder, setIsMarketOrder] = useState(false)

  const mutation = useMutation({
    mutationFn: apiClient.createOrder
  })

  useEffect(() => {
    if (mutation.isError || mutation.isSuccess) {
      const timerId: NodeJS.Timeout = setTimeout(() => {
        mutation.reset()
      }, 3000)
      return () => clearTimeout(timerId)
    }
  }, [mutation])

  async function submitOrder() {
    const nonce = crypto.randomUUID().replaceAll('-', '')
    const bigIntAmount = parseUnits(amount, baseSymbol.decimals)
    const signature = await signTypedDataAsync({
      types: {
        EIP712Domain: [
          { name: 'name', type: 'string' },
          { name: 'version', type: 'string' },
          { name: 'chainId', type: 'uint256' },
          { name: 'verifyingContract', type: 'address' }
        ],
        Order: [
          { name: 'sender', type: 'address' },
          { name: 'baseToken', type: 'address' },
          { name: 'quoteToken', type: 'address' },
          { name: 'amount', type: 'int256' },
          { name: 'price', type: 'uint256' },
          { name: 'nonce', type: 'int256' }
        ]
      },
      domain: getDomain(exchangeContractAddress, config.state.chainId),
      primaryType: 'Order',
      message: {
        sender: walletAddress,
        baseToken: baseSymbol.contractAddress ?? addressZero,
        quoteToken: quoteSymbol.contractAddress ?? addressZero,
        amount: side == 'Buy' ? bigIntAmount : -bigIntAmount,
        price: isMarketOrder
          ? BigInt(0)
          : parseUnits(price, baseSymbol.decimals),
        nonce: BigInt('0x' + nonce)
      }
    })

    if (isMarketOrder) {
      mutation.mutate({
        nonce: nonce,
        marketId: `${baseSymbol.name}/${quoteSymbol.name}`,
        type: 'market',
        side: side,
        amount: parseUnits(amount, baseSymbol.decimals).valueOf(),
        signature: signature
      })
    } else {
      mutation.mutate({
        nonce: nonce,
        marketId: `${baseSymbol.name}/${quoteSymbol.name}`,
        type: 'limit',
        side: side,
        amount: parseUnits(amount, baseSymbol.decimals),
        price: new Decimal(price),
        signature: signature
      })
    }
  }

  return (
    <Widget
      title="Trade"
      contents={
        <>
          <div className="flex w-full text-center text-lg font-medium">
            <div
              className={classNames(
                'cursor-pointer border-b-2 w-full',
                side == 'Buy' ? 'border-b-lightBackground' : 'border-b-darkGray'
              )}
              onClick={() => !mutation.isPending && setSide('Buy')}
            >
              Buy {baseSymbol.name}
            </div>
            <div
              className={classNames(
                'cursor-pointer border-b-2 w-full',
                side == 'Sell'
                  ? 'border-b-lightBackground'
                  : 'border-b-darkGray'
              )}
              onClick={() => !mutation.isPending && setSide('Sell')}
            >
              Sell {baseSymbol.name}
            </div>
          </div>
          <table>
            <tbody>
              <tr>
                <td className="pt-3">
                  <label className="block text-sm">Price</label>
                </td>
              </tr>
              <tr>
                <td>
                  <div className="relative">
                    <input
                      value={price}
                      disabled={isMarketOrder || mutation.isPending}
                      placeholder={'0.0'}
                      onChange={(e) => {
                        setPrice(
                          cleanAndFormatNumberInput(
                            e.target.value,
                            market.quoteDecimalPlaces
                          )
                        )
                      }}
                      className="bg-black pr-12 text-white disabled:bg-mutedGray"
                    />
                    <span className="absolute right-2 top-2 text-white">
                      {quoteSymbol.name}
                    </span>
                  </div>
                </td>
                <td className="pl-6">
                  <label className="text-sm">
                    <input
                      type="checkbox"
                      checked={isMarketOrder}
                      disabled={mutation.isPending}
                      onChange={(e) => {
                        setIsMarketOrder(e.target.checked)
                      }}
                    />
                    <span className="pl-2">Market Order</span>
                  </label>
                </td>
              </tr>
              <tr>
                <td className="pt-3">
                  <label className="block text-sm">Amount</label>
                </td>
              </tr>
              <tr>
                <td>
                  <div className="relative">
                    <input
                      placeholder={'0.0'}
                      value={amount}
                      disabled={mutation.isPending}
                      onChange={(e) => {
                        setAmount(
                          cleanAndFormatNumberInput(
                            e.target.value,
                            baseSymbol.decimals
                          )
                        )
                      }}
                      className="bg-black pr-12 text-white disabled:bg-mutedGray"
                    />
                    <span className="absolute right-2 top-2 text-white">
                      {baseSymbol.name}
                    </span>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
          <p className="py-3">
            <SubmitButton
              disabled={
                !((isMarketOrder || price) && amount) || mutation.isPending
              }
              onClick={submitOrder}
              error={
                mutation.isError
                  ? isErrorFromAlias(
                      apiClient.api,
                      'updateOrder',
                      mutation.error
                    )
                    ? mutation.error.response.data.errors[0].displayMessage
                    : 'Something went wrong'
                  : ''
              }
              caption={() => {
                if (mutation.isPending) {
                  return 'Submitting order...'
                } else {
                  return `${side} ${baseSymbol.name}`
                }
              }}
            />
          </p>
          <p className="text-center text-white">
            {`${side == 'Buy' ? 'Buying' : 'Selling'} ${amount} ${
              baseSymbol.name
            } ${
              isMarketOrder
                ? '(market order) '
                : `for ${price} ${quoteSymbol.name}`
            }`}
          </p>
          <p className="text-center text-white">Fee: 0.05 {quoteSymbol.name}</p>
          <div className="pt-3 text-center">
            {mutation.isSuccess ? (
              <div className="text-green">Order created!</div>
            ) : null}
          </div>
        </>
      }
    />
  )
}
