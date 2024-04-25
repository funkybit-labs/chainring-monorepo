import { apiClient, OrderSide, TradingSymbol } from 'apiClient'
import { classNames } from 'utils'
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
import { Address, formatUnits } from 'viem'
import { isErrorFromAlias } from '@zodios/core'
import { useConfig, useSignTypedData } from 'wagmi'
import { addressZero, getDomain } from 'utils/eip712'
import { Market } from 'markets'
import Decimal from 'decimal.js'
import { useWebsocketSubscription } from 'contexts/websocket'
import { OrderBook, orderBookTopic, Publishable } from 'websocketMessages'
import { getMarketPrice } from 'utils/pricesUtils'
import useAmountInputState from 'hooks/useAmountInputState'

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
    initialValue: 0n,
    decimals: quoteSymbol.decimals
  })

  const {
    inputValue: amountInputValue,
    setInputValue: setAmountInputValue,
    valueInFundamentalUnits: amount
  } = useAmountInputState({
    initialInputValue: '',
    initialValue: 0n,
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
      mutation.mutate({
        nonce: nonce,
        marketId: `${baseSymbol.name}/${quoteSymbol.name}`,
        type: 'market',
        side: side,
        amount: amount,
        signature: signature
      })
    } else {
      mutation.mutate({
        nonce: nonce,
        marketId: `${baseSymbol.name}/${quoteSymbol.name}`,
        type: 'limit',
        side: side,
        amount: amount,
        price: new Decimal(priceInputValue),
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
          <p className="py-3">
            <SubmitButton
              disabled={
                !((isMarketOrder || price > 0) && amount > 0n) ||
                mutation.isPending
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

function AmountWithSymbol({
  amount,
  symbol,
  approximate
}: {
  amount: bigint
  symbol: TradingSymbol
  approximate: boolean
}) {
  return (
    <div className={'inline-block whitespace-nowrap'}>
      {approximate && '~'}
      {formatUnits(amount, symbol.decimals)} {symbol.name}
    </div>
  )
}
