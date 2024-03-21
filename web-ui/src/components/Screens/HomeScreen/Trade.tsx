import {crateOrder, CreateLimitOrder, CreateMarketOrder, CreateOrderRequest, OrderSide} from 'ApiClient'
import {classNames} from 'utils'
import {useEffect, useState} from 'react'
import {useMutation} from '@tanstack/react-query'
import {Widget} from '../../common/Widget'
import SubmitButton from '../../common/SubmitButton'

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
  const [tradeDescription, setTradeDescription] = useState('')

  useEffect(() => {
    let description = ''
    if (side == OrderSide.Buy) {
      description += 'Buying '
    } else {
      description += 'Selling '
    }
    description += amount + ' ' + baseSymbol
    if (isMarketOrder) {
      description += ' (market order)'
    } else {
      description += ' for ' + price + ' ' + quoteSymbol
    }

    setTradeDescription(description)
  }, [baseSymbol, quoteSymbol, side, price, amount, isMarketOrder])

  const submitTrade = () => {
    console.log('Submitting trade:', {
      activeTab: side,
      price,
      amount,
      isMarketOrder
    })
    if (isMarketOrder) {
      mutation.mutate({
        nonce: 'string',
        type: 'market',
        instrument: baseSymbol + quoteSymbol,
        side: side,
        amount: Number(amount)
      })
    } else {
      mutation.mutate({
        nonce: 'string',
        type: 'limit',
        instrument: baseSymbol + quoteSymbol,
        side: side,
        amount: Number(amount),
        price: Number(price),
        timeInForce: { type: 'GoodTillCancelled' }
      })
    }
  }

  const mutation = useMutation({
    mutationFn: (orderDetails: CreateOrderRequest) => crateOrder(orderDetails)
  })

  return (
    <Widget
      title="Trade"
      contents={
        mutation.isPending ? (
          <>Creating order...</>
        ) : (
          <>
            <div
              className={classNames(
                'flex text-center text-l font-medium w-full'
              )}
            >
              <div
                className={classNames(
                  'cursor-pointer w-full',
                  side == OrderSide.Buy
                    ? 'border-b-2 border-b-lightBackground'
                    : 'border-b-2 border-b-darkGray'
                )}
                onClick={() => setSide(OrderSide.Buy)}
              >
                Buy {baseSymbol}
              </div>
              <div
                className={classNames(
                  'cursor-pointer w-full',
                  side == OrderSide.Sell
                    ? 'border-b-2 border-b-lightBackground'
                    : 'border-b-2 border-b-darkGray'
                )}
                onClick={() => setSide(OrderSide.Sell)}
              >
                Sell {baseSymbol}
              </div>
            </div>
            <table>
              <tbody>
                <tr>
                  <td className="pt-3">
                    <label className={classNames('block text-sm')}>Price</label>
                  </td>
                </tr>
                <tr>
                  <td>
                    <div className={classNames('relative')}>
                      <input
                        value={price}
                        disabled={isMarketOrder}
                        placeholder={'0.0'}
                        onChange={(e) => {
                          if (/^\d{0,5}(\.\d{0,18})?$/.test(e.target.value)) {
                            setPrice(e.target.value)
                          }
                        }}
                        className={classNames(
                          'pr-12 bg-black text-white disabled:bg-mutedGray'
                        )}
                      />
                      <span
                        className={classNames(
                          'absolute right-2 top-2 text-white'
                        )}
                      >
                        {quoteSymbol}
                      </span>
                    </div>
                  </td>
                  <td className="pl-6">
                    <label className={classNames('text-sm')}>
                      <input
                        type="checkbox"
                        checked={isMarketOrder}
                        onChange={(e) => {
                          if (e.target.checked) {
                            setPrice('')
                          }
                          setIsMarketOrder(e.target.checked)
                        }}
                      />
                      <span className="pl-2">Market Order</span>
                    </label>
                  </td>
                </tr>
                <tr>
                  <td className="pt-3">
                    <label className={classNames('block text-sm')}>
                      Amount
                    </label>
                  </td>
                </tr>
                <tr>
                  <td>
                    <div className={classNames('relative')}>
                      <input
                        placeholder={'0.0'}
                        value={amount}
                        onChange={(e) => {
                          if (/^\d{0,5}(\.\d{0,18})?$/.test(e.target.value)) {
                            setAmount(e.target.value)
                          }
                        }}
                        className={classNames(
                          'pr-12 bg-black text-white disabled:bg-mutedGray'
                        )}
                      />
                      <span
                        className={classNames(
                          'absolute right-2 top-2 text-white'
                        )}
                      >
                        {baseSymbol}
                      </span>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>

            <p className="py-3">
              <SubmitButton
                disabled={!((isMarketOrder || price) && amount)}
                onClick={submitTrade}
                error={''}
                caption={() => {
                  if (side == OrderSide.Buy) {
                    return 'Buy ' + baseSymbol
                  } else {
                    return 'Sell ' + baseSymbol
                  }
                }}
              />
            </p>

            <p className="text-center text-white">{tradeDescription}</p>
            <p className="text-center text-white">Fee: 0.05 {quoteSymbol}</p>

            {mutation.isError ? (
              <div>An error occurred: {mutation.error.message}</div>
            ) : null}
            {mutation.isSuccess ? <div>Order created!</div> : null}
          </>
        )
      }
    />
  )
}
