import { apiClient, OrderSide } from 'apiClient'
import { classNames } from 'utils'
import React, { ChangeEvent, useCallback, useMemo, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Widget } from 'components/common/Widget'
import SubmitButton from 'components/common/SubmitButton'
import { Address, formatUnits } from 'viem'
import { isErrorFromAlias } from '@zodios/core'
import { BaseError as WagmiError, useConfig, useSignTypedData } from 'wagmi'
import { Market } from 'markets'
import Decimal from 'decimal.js'
import { useWebsocketSubscription } from 'contexts/websocket'
import {
  limitsTopic,
  OrderBook,
  orderBookTopic,
  Publishable
} from 'websocketMessages'
import { getMarketPrice } from 'utils/pricesUtils'
import useAmountInputState from 'hooks/useAmountInputState'
import { addressZero, generateOrderNonce, getDomain } from 'utils/eip712'
import { AmountWithSymbol } from 'components/common/AmountWithSymbol'

export default function TradeWidget({
  market,
  exchangeContractAddress,
  walletAddress
}: {
  market: Market
  exchangeContractAddress: Address
  walletAddress: Address
}) {
  const config = useConfig()
  const { signTypedDataAsync } = useSignTypedData()

  const baseSymbol = market.baseSymbol
  const quoteSymbol = market.quoteSymbol

  const [side, setSide] = useState<OrderSide>('Buy')

  const {
    inputValue: priceInputValue,
    setInputValue: setPriceInputValue,
    valueInFundamentalUnits: price
  } = useAmountInputState({
    initialInputValue: '',
    decimals: quoteSymbol.decimals
  })

  const {
    inputValue: amountInputValue,
    setInputValue: setAmountInputValue,
    valueInFundamentalUnits: amount
  } = useAmountInputState({
    initialInputValue: '',
    decimals: baseSymbol.decimals
  })

  const [isMarketOrder, setIsMarketOrder] = useState(false)

  const [orderBook, setOrderBook] = useState<OrderBook | undefined>(undefined)
  useWebsocketSubscription({
    topics: useMemo(() => [orderBookTopic(market.id)], [market.id]),
    handler: useCallback((message: Publishable) => {
      if (message.type === 'OrderBook') {
        setOrderBook(message)
      }
    }, [])
  })

  const [baseLimit, setBaseLimit] = useState<bigint | undefined>(undefined)
  const [quoteLimit, setQuoteLimit] = useState<bigint | undefined>(undefined)

  useWebsocketSubscription({
    topics: useMemo(() => [limitsTopic(market.id)], [market.id]),
    handler: useCallback((message: Publishable) => {
      if (message.type === 'Limits') {
        setBaseLimit(message.base)
        setQuoteLimit(message.quote)
      }
    }, [])
  })

  const marketPrice = useMemo(() => {
    if (orderBook === undefined) return 0n
    return getMarketPrice(side, amount, market, orderBook)
  }, [side, amount, orderBook, market])

  const notional = useMemo(() => {
    if (isMarketOrder) {
      return (marketPrice * amount) / BigInt(Math.pow(10, baseSymbol.decimals))
    } else {
      return (price * amount) / BigInt(Math.pow(10, baseSymbol.decimals))
    }
  }, [price, marketPrice, amount, isMarketOrder, baseSymbol.decimals])

  function changeSide(newValue: OrderSide) {
    if (!mutation.isPending && side != newValue) {
      setSide(newValue)
      setPriceInputValue('')
      setAmountInputValue('')
      setIsMarketOrder(false)
      mutation.reset()
    }
  }

  function handleAmountChange(e: ChangeEvent<HTMLInputElement>) {
    setAmountInputValue(e.target.value)
  }

  function handlePriceChange(e: ChangeEvent<HTMLInputElement>) {
    setPriceInputValue(e.target.value)
  }

  function handleMarketOrderFlagChange(e: ChangeEvent<HTMLInputElement>) {
    if (!isMarketOrder && e.target.checked) {
      setPriceInputValue('')
    }
    setIsMarketOrder(e.target.checked)
  }

  const mutation = useMutation({
    mutationFn: async () => {
      try {
        const nonce = generateOrderNonce()
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
            amount: side == 'Buy' ? amount : -amount,
            price: isMarketOrder ? 0n : price,
            nonce: BigInt('0x' + nonce)
          }
        })

        if (isMarketOrder) {
          return await apiClient.createOrder({
            nonce: nonce,
            marketId: `${baseSymbol.name}/${quoteSymbol.name}`,
            type: 'market',
            side: side,
            amount: amount,
            signature: signature
          })
        } else {
          return await apiClient.createOrder({
            nonce: nonce,
            marketId: `${baseSymbol.name}/${quoteSymbol.name}`,
            type: 'limit',
            side: side,
            amount: amount,
            price: new Decimal(priceInputValue),
            signature: signature
          })
        }
      } catch (error) {
        throw Error(
          isErrorFromAlias(apiClient.api, 'createOrder', error)
            ? error.response.data.errors[0].displayMessage
            : (error as WagmiError).shortMessage || 'Something went wrong'
        )
      }
    },
    onSettled: () => {
      setTimeout(mutation.reset, 3000)
    }
  })

  const canSubmit = useMemo(() => {
    if (mutation.isPending) return false
    if (amount <= 0n) return false
    if (price <= 0n && !isMarketOrder) return false

    if (side == 'Buy' && notional > (quoteLimit || 0n)) return false
    if (side == 'Sell' && amount > (baseLimit || 0n)) return false

    return true
  }, [
    mutation.isPending,
    side,
    amount,
    price,
    notional,
    isMarketOrder,
    quoteLimit,
    baseLimit
  ])

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
              onClick={() => changeSide('Buy')}
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
              onClick={() => changeSide('Sell')}
            >
              Sell {baseSymbol.name}
            </div>
          </div>
          <table className="w-full">
            <tbody>
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
                      value={amountInputValue}
                      disabled={mutation.isPending}
                      onChange={handleAmountChange}
                      className="w-full bg-black text-white disabled:bg-mutedGray"
                    />
                    <span className="absolute right-2 top-2 text-white">
                      {baseSymbol.name}
                    </span>
                  </div>
                </td>
              </tr>
              <tr>
                <td className="relative pt-3">
                  <label className="block text-sm">Price</label>
                  <label className="absolute right-1 top-3 text-sm">
                    <input
                      type="checkbox"
                      checked={isMarketOrder}
                      disabled={mutation.isPending}
                      onChange={handleMarketOrderFlagChange}
                    />
                    <span className="pl-2">Market Order</span>
                  </label>
                </td>
              </tr>
              <tr>
                <td>
                  <div className="relative">
                    <input
                      value={priceInputValue}
                      disabled={isMarketOrder || mutation.isPending}
                      placeholder={
                        isMarketOrder
                          ? `~${formatUnits(marketPrice, quoteSymbol.decimals)}`
                          : '0.0'
                      }
                      onChange={handlePriceChange}
                      className="w-full bg-black text-white disabled:bg-mutedGray"
                    />
                    <span className="absolute right-2 top-2 text-white">
                      {quoteSymbol.name}
                    </span>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
          <div className="py-3 text-sm">
            Limit:{' '}
            {quoteLimit !== undefined && baseLimit !== undefined && (
              <AmountWithSymbol
                amount={side == 'Buy' ? quoteLimit : baseLimit}
                symbol={side == 'Buy' ? quoteSymbol : baseSymbol}
                approximate={false}
              />
            )}
          </div>
          <div className="pb-3">
            <SubmitButton
              disabled={!canSubmit}
              onClick={mutation.mutate}
              error={mutation.error?.message}
              caption={() => {
                if (mutation.isPending) {
                  return 'Submitting order...'
                } else {
                  return `${side} ${baseSymbol.name}`
                }
              }}
            />
          </div>
          {notional > 0n && (
            <>
              <div className="text-center text-sm text-white">
                <div className={'inline-block'}>
                  {side == 'Buy' ? 'Buying' : 'Selling'}
                </div>{' '}
                <AmountWithSymbol
                  amount={amount}
                  symbol={baseSymbol}
                  approximate={false}
                />{' '}
                at{' '}
                {isMarketOrder ? (
                  <>
                    <AmountWithSymbol
                      amount={notional}
                      symbol={quoteSymbol}
                      approximate={true}
                    />{' '}
                    for market price of{' '}
                    <AmountWithSymbol
                      amount={marketPrice}
                      symbol={quoteSymbol}
                      approximate={true}
                    />
                  </>
                ) : (
                  <>
                    <AmountWithSymbol
                      amount={notional}
                      symbol={quoteSymbol}
                      approximate={false}
                    />{' '}
                    for limit price of{' '}
                    <AmountWithSymbol
                      amount={price}
                      symbol={quoteSymbol}
                      approximate={false}
                    />
                  </>
                )}
              </div>
              <p className="pt-3 text-center text-sm text-white">
                Fee: 0.05 {quoteSymbol.name}
              </p>
            </>
          )}
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
