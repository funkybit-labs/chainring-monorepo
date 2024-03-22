import { createOrder, CreateOrderRequest, OrderSide } from 'ApiClient'
import { classNames } from 'utils'
import React, { Fragment, useEffect, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Widget } from 'components/common/Widget'
import SubmitButton from 'components/common/SubmitButton'

export default function Trade({
  baseSymbol,
  quoteSymbol
}: {
  baseSymbol: string
  quoteSymbol: string
}) {
  const [side, setSide] = useState(OrderSide.Buy) // 'buy' or 'sell'
  const [price, setPrice] = useState('')
  const [amount, setAmount] = useState('')
  const [isMarketOrder, setIsMarketOrder] = useState(false)

  const submitTrade = () => {
    if (isMarketOrder) {
      mutation.mutate({
        nonce: crypto.randomUUID(),
        marketId: `${baseSymbol}/${quoteSymbol}`,
        type: 'market',
        side: side,
        amount: Number(amount)
      })
    } else {
      mutation.mutate({
        nonce: crypto.randomUUID(),
        marketId: `${baseSymbol}/${quoteSymbol}`,
        type: 'limit',
        side: side,
        amount: Number(amount),
        price: Number(price)
      })
    }
  }

  const mutation = useMutation({
    mutationFn: (orderDetails: CreateOrderRequest) => createOrder(orderDetails)
  })

  useEffect(() => {
    if (mutation.isError || mutation.isSuccess) {
      const timerId: NodeJS.Timeout = setTimeout(() => {
        mutation.reset()
      }, 3000)
      return () => clearTimeout(timerId)
    }
  }, [mutation])

  function cleanAndFormatNumber(inputValue: string) {
    let cleanedValue = inputValue
      .replace(/[^\d.]/g, '') // Remove all non-numeric characters
      .replace(/^0+(\d)/, '$1') // Leading zeros
      .replace(/^\./, '0.')

    // multiple decimal points
    cleanedValue =
      cleanedValue.split('.')[0] +
      (cleanedValue.includes('.')
        ? '.' + cleanedValue.split('.')[1].slice(0, 18)
        : '')

    return cleanedValue
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
                side == OrderSide.Buy
                  ? 'border-b-lightBackground'
                  : 'border-b-darkGray'
              )}
              onClick={() => !mutation.isPending && setSide(OrderSide.Buy)}
            >
              Buy {baseSymbol}
            </div>
            <div
              className={classNames(
                'cursor-pointer border-b-2 w-full',
                side == OrderSide.Sell
                  ? 'border-b-lightBackground'
                  : 'border-b-darkGray'
              )}
              onClick={() => !mutation.isPending && setSide(OrderSide.Sell)}
            >
              Sell {baseSymbol}
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
                        setPrice(cleanAndFormatNumber(e.target.value))
                      }}
                      className="bg-black pr-12 text-white disabled:bg-mutedGray"
                    />
                    <span className="absolute right-2 top-2 text-white">
                      {quoteSymbol}
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
                        setAmount(cleanAndFormatNumber(e.target.value))
                      }}
                      className="bg-black pr-12 text-white disabled:bg-mutedGray"
                    />
                    <span className="absolute right-2 top-2 text-white">
                      {baseSymbol}
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
              onClick={submitTrade}
              error={
                mutation.isError
                  ? `An error occurred: ${mutation.error.message}`
                  : ''
              }
              caption={() => {
                if (mutation.isPending) {
                  return 'Submitting order...'
                } else {
                  return `${side} ${baseSymbol}`
                }
              }}
            />
          </p>
          <p className="text-center text-white">
            {`${
              side == OrderSide.Buy ? 'Buying' : 'Selling'
            } ${amount} ${baseSymbol} ${
              isMarketOrder ? '(market order) ' : `for ${price} ${quoteSymbol}`
            }`}
          </p>
          <p className="text-center text-white">Fee: 0.05 {quoteSymbol}</p>
          <div className="pt-3 text-center">
            {mutation.isError ? (
              <div className="text-red">
                An error occurred: {mutation.error.message}
              </div>
            ) : null}
            {mutation.isSuccess ? (
              <div className="text-green">Order created!</div>
            ) : null}
          </div>
        </>
      }
    />
  )
}
