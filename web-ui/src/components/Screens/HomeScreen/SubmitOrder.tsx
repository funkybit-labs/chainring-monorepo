import { apiClient, OrderSide, TradingSymbol } from 'ApiClient'
import { classNames, cleanAndFormatNumberInput } from 'utils'
import React, { useEffect, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Widget } from 'components/common/Widget'
import SubmitButton from 'components/common/SubmitButton'
import { parseUnits } from 'viem'
import { isErrorFromAlias } from '@zodios/core'

export default function SubmitOrder({
  baseSymbol,
  quoteSymbol
}: {
  baseSymbol: TradingSymbol
  quoteSymbol: TradingSymbol
}) {
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

  function submitOrder() {
    if (isMarketOrder) {
      mutation.mutate({
        nonce: crypto.randomUUID(),
        marketId: `${baseSymbol.name}/${quoteSymbol.name}`,
        type: 'market',
        side: side,
        amount: parseUnits(amount, 18)
      })
    } else {
      mutation.mutate({
        nonce: crypto.randomUUID(),
        marketId: `${baseSymbol.name}/${quoteSymbol.name}`,
        type: 'limit',
        side: side,
        amount: parseUnits(amount, 18),
        price: parseUnits(price, 18)
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
                            quoteSymbol.decimals
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
