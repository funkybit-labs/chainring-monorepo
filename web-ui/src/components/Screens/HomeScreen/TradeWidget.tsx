import { apiClient, OrderSide } from 'apiClient'
import { classNames, cleanAndFormatNumberInput } from 'utils'
import React, {
  ChangeEvent,
  useCallback,
  useEffect,
  useMemo,
  useState
} from 'react'
import { useMutation } from '@tanstack/react-query'
import { Widget } from 'components/common/Widget'
import SubmitButton from 'components/common/SubmitButton'
import { Address, formatUnits, parseUnits } from 'viem'
import { isErrorFromAlias } from '@zodios/core'
import { useConfig, useSignTypedData } from 'wagmi'
import { addressZero, getDomain } from 'utils/eip712'
import { Market } from 'markets'
import Decimal from 'decimal.js'
import { useWebsocketSubscription } from 'contexts/websocket'
import { OrderBook, orderBookTopic, Publishable } from 'websocketMessages'
import { getMarketPrice } from 'utils/pricesUtils'

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
  const [price, setPrice] = useState('')
  const [amount, setAmount] = useState('')
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

  const marketPrice = useMemo(() => {
    if (orderBook === undefined) {
      return undefined
    }

    return getMarketPrice(
      side,
      parseUnits(amount, quoteSymbol.decimals),
      market,
      orderBook
    )
  }, [side, amount, orderBook, market, quoteSymbol.decimals])

  const formattedMarketPrice = useMemo(() => {
    if (marketPrice === undefined) {
      return cleanAndFormatNumberInput('0', quoteSymbol.decimals)
    } else {
      return `~${cleanAndFormatNumberInput(
        formatUnits(marketPrice, quoteSymbol.decimals),
        quoteSymbol.decimals
      )}`
    }
  }, [marketPrice, quoteSymbol.decimals])

  const formattedNotional = useMemo(() => {
    if (amount === '') {
      return undefined
    }

    const parsedAmount = parseUnits(amount, baseSymbol.decimals)

    if (isMarketOrder && marketPrice) {
      return `~${cleanAndFormatNumberInput(
        formatUnits(
          (marketPrice * parsedAmount) / parseUnits('1', baseSymbol.decimals),
          quoteSymbol.decimals
        ),
        quoteSymbol.decimals
      )}`
    } else if (price !== '') {
      const parsedPrice = parseUnits(price, baseSymbol.decimals)
      return cleanAndFormatNumberInput(
        formatUnits(
          (parsedPrice * parsedAmount) / parseUnits('1', baseSymbol.decimals),
          quoteSymbol.decimals
        ),
        quoteSymbol.decimals
      )
    } else {
      return undefined
    }
  }, [
    price,
    marketPrice,
    amount,
    isMarketOrder,
    baseSymbol.decimals,
    quoteSymbol.decimals
  ])

  function changeSide(newValue: OrderSide) {
    if (!mutation.isPending && side != newValue) {
      setSide(newValue)
      setPrice('')
      setAmount('')
      setIsMarketOrder(false)
    }
  }

  function handleAmountChange(e: ChangeEvent<HTMLInputElement>) {
    setAmount(cleanAndFormatNumberInput(e.target.value, baseSymbol.decimals))
  }

  function handleMarketOrderFlagChange(e: ChangeEvent<HTMLInputElement>) {
    if (!isMarketOrder && e.target.checked) {
      setPrice('')
    }
    setIsMarketOrder(e.target.checked)
  }

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
          : parseUnits(price, quoteSymbol.decimals),
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
                      value={amount}
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
                      value={price}
                      disabled={isMarketOrder || mutation.isPending}
                      placeholder={
                        isMarketOrder
                          ? formattedMarketPrice
                          : cleanAndFormatNumberInput('0', quoteSymbol.decimals)
                      }
                      onChange={(e) => {
                        setPrice(
                          cleanAndFormatNumberInput(
                            e.target.value,
                            market.quoteDecimalPlaces
                          )
                        )
                      }}
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
          <p className="text-white">
            {!!formattedNotional && (
              <>
                <div className={'inline-block'}>
                  {side == 'Buy' ? 'Buying' : 'Selling'}
                </div>{' '}
                <div className={'inline-block whitespace-nowrap'}>
                  {amount} {baseSymbol.name}
                </div>{' '}
                for{' '}
                <div className={'inline-block whitespace-nowrap'}>
                  {formattedNotional} {quoteSymbol.name}
                </div>{' '}
                at{' '}
                {isMarketOrder ? (
                  <>
                    market price of{' '}
                    <div className={'inline-block whitespace-nowrap'}>
                      {formattedMarketPrice} {quoteSymbol.name}
                    </div>
                  </>
                ) : (
                  <>
                    limit price of{' '}
                    <div className={'inline-block whitespace-nowrap'}>
                      {price} {quoteSymbol.name}
                    </div>
                  </>
                )}
              </>
            )}
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
