import { apiClient, FeeRates, OrderSide } from 'apiClient'
import { calculateFee, calculateNotional, classNames } from 'utils'
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
import SymbolIcon from 'components/common/SymbolIcon'
import { Button } from 'components/common/Button'
import { useWeb3Modal } from '@web3modal/wagmi/react'

export default function OrderTicketWidget({
  market,
  exchangeContractAddress,
  walletAddress,
  feeRates
}: {
  market: Market
  exchangeContractAddress?: Address
  walletAddress?: Address
  feeRates: FeeRates
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
    inputValue: baseAmountInputValue,
    setInputValue: setBaseAmountInputValue,
    valueInFundamentalUnits: baseAmount
  } = useAmountInputState({
    initialInputValue: '',
    decimals: baseSymbol.decimals
  })

  const {
    inputValue: quoteAmountInputValue,
    setInputValue: setQuoteAmountInputValue,
    valueInFundamentalUnits: quoteAmount
  } = useAmountInputState({
    initialInputValue: '',
    decimals: quoteSymbol.decimals
  })

  const [isLimitOrder, setIsLimitOrder] = useState(false)

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
  const { open: openWalletConnectModal } = useWeb3Modal()

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
    return getMarketPrice(side, baseAmount, market, orderBook)
  }, [side, baseAmount, orderBook, market])

  const { notional, fee } = useMemo(() => {
    if (isLimitOrder) {
      const notional = calculateNotional(price, baseAmount, baseSymbol)
      return {
        notional,
        fee: calculateFee(notional, feeRates.maker)
      }
    } else {
      const notional = calculateNotional(marketPrice, baseAmount, baseSymbol)
      return {
        notional,
        fee: calculateFee(notional, feeRates.taker)
      }
    }
  }, [price, marketPrice, baseAmount, isLimitOrder, baseSymbol, feeRates])

  function changeSide(newValue: OrderSide) {
    if (!mutation.isPending && side != newValue) {
      setSide(newValue)
      setPriceInputValue('')
      setQuoteAmountManuallyChanged(false)
      setBaseAmountManuallyChanged(true)
      setQuoteAmountInputValue('')
      setIsLimitOrder(false)
      mutation.reset()
    }
  }

  const [baseAmountManuallyChanged, setBaseAmountManuallyChanged] =
    useState(false)
  const [quoteAmountManuallyChanged, setQuoteAmountManuallyChanged] =
    useState(false)

  function handleBaseAmountChange(e: ChangeEvent<HTMLInputElement>) {
    setQuoteAmountManuallyChanged(false)
    setBaseAmountManuallyChanged(true)
    setBaseAmountInputValue(e.target.value)

    mutation.reset()
  }

  useEffect(() => {
    if (baseAmountManuallyChanged) {
      if (orderBook !== undefined) {
        const indicativePrice =
          isLimitOrder && price !== 0n
            ? price
            : getMarketPrice(side, baseAmount, market, orderBook)
        const notional =
          (baseAmount * indicativePrice) /
          BigInt(Math.pow(10, baseSymbol.decimals))
        setQuoteAmountInputValue(formatUnits(notional, quoteSymbol.decimals))
      }
    }
  }, [
    baseAmount,
    baseAmountManuallyChanged,
    setQuoteAmountInputValue,
    orderBook,
    market,
    baseSymbol,
    quoteSymbol,
    side,
    isLimitOrder,
    price
  ])

  useEffect(() => {
    if (quoteAmountManuallyChanged) {
      if (orderBook !== undefined) {
        const indicativePrice =
          isLimitOrder && price !== 0n
            ? price
            : getMarketPrice(
                side,
                (baseAmount ?? 1) || BigInt(1),
                market,
                orderBook
              )
        if (indicativePrice !== 0n) {
          const quantity =
            (quoteAmount * BigInt(Math.pow(10, baseSymbol.decimals))) /
            indicativePrice
          setBaseAmountInputValue(formatUnits(quantity, baseSymbol.decimals))
        }
      }
    }
  }, [
    quoteAmount,
    quoteAmountManuallyChanged,
    setBaseAmountInputValue,
    orderBook,
    market,
    baseAmount,
    baseSymbol,
    side,
    isLimitOrder,
    price
  ])

  function handleQuoteAmountChange(e: ChangeEvent<HTMLInputElement>) {
    setBaseAmountManuallyChanged(false)
    setQuoteAmountManuallyChanged(true)
    setQuoteAmountInputValue(e.target.value)
    mutation.reset()
  }

  function handlePriceChange(e: ChangeEvent<HTMLInputElement>) {
    setPriceInputValue(e.target.value)
    mutation.reset()
  }

  function handleMarketOrderFlagChange(e: ChangeEvent<HTMLInputElement>) {
    if (isLimitOrder && e.target.checked) {
      setPriceInputValue('')
    }
    setIsLimitOrder(e.target.checked)
    mutation.reset()
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
          domain: getDomain(exchangeContractAddress!, config.state.chainId),
          primaryType: 'Order',
          message: {
            sender: walletAddress!,
            baseToken: baseSymbol.contractAddress ?? addressZero,
            quoteToken: quoteSymbol.contractAddress ?? addressZero,
            amount: side == 'Buy' ? baseAmount : -baseAmount,
            price: isLimitOrder ? price : 0n,
            nonce: BigInt('0x' + nonce)
          }
        })

        let response
        if (isLimitOrder) {
          response = await apiClient.createOrder({
            nonce: nonce,
            marketId: `${baseSymbol.name}/${quoteSymbol.name}`,
            type: 'limit',
            side: side,
            amount: baseAmount,
            price: new Decimal(priceInputValue),
            signature: signature,
            verifyingChainId: config.state.chainId
          })
        } else {
          response = await apiClient.createOrder({
            nonce: nonce,
            marketId: `${baseSymbol.name}/${quoteSymbol.name}`,
            type: 'market',
            side: side,
            amount: baseAmount,
            signature: signature,
            verifyingChainId: config.state.chainId
          })
          setQuoteAmountManuallyChanged(false)
          setBaseAmountManuallyChanged(false)
          setBaseAmountInputValue('')
          setQuoteAmountInputValue('')
          setPriceInputValue('')
          return response
        }
      } catch (error) {
        throw Error(
          isErrorFromAlias(apiClient.api, 'createOrder', error)
            ? error.response.data.errors[0].displayMessage
            : (error as WagmiError).shortMessage || 'Something went wrong'
        )
      }
    },
    onSuccess: () => {
      setTimeout(mutation.reset, 3000)
    }
  })

  const canSubmit = useMemo(() => {
    if (mutation.isPending) return false
    if (baseAmount <= 0n) return false
    if (price <= 0n && isLimitOrder) return false

    if (side == 'Buy' && notional > (quoteLimit || 0n)) return false
    if (side == 'Sell' && baseAmount > (baseLimit || 0n)) return false

    return true
  }, [
    mutation.isPending,
    side,
    baseAmount,
    price,
    notional,
    isLimitOrder,
    quoteLimit,
    baseLimit
  ])

  const [buySymbol, sellSymbol] =
    side === 'Buy' ? [baseSymbol, quoteSymbol] : [quoteSymbol, baseSymbol]
  const [buyAmountInputValue, sellAmountInputValue, sellAmount, sellLimit] =
    useMemo(() => {
      return side === 'Buy'
        ? [baseAmountInputValue, quoteAmountInputValue, quoteAmount, quoteLimit]
        : [quoteAmountInputValue, baseAmountInputValue, baseAmount, baseLimit]
    }, [
      side,
      baseAmountInputValue,
      baseAmount,
      baseLimit,
      quoteAmountInputValue,
      quoteAmount,
      quoteLimit
    ])

  return (
    <Widget
      id="order-ticket"
      contents={
        <>
          <div className="flex w-full items-center justify-between">
            <div>
              <div className="flex items-center">
                Buy:
                <SymbolIcon
                  symbol={buySymbol}
                  className="ml-4 mr-2 inline-block size-5"
                />
                {buySymbol.name}
              </div>
              <input
                placeholder={'0.0'}
                value={buyAmountInputValue}
                disabled={mutation.isPending}
                onChange={
                  side === 'Buy'
                    ? handleBaseAmountChange
                    : handleQuoteAmountChange
                }
                className={classNames(
                  'w-full mt-2 text-center bg-darkBluishGray9 border-darkBluishGray6 rounded text-white disabled:bg-mutedGray'
                )}
              />
            </div>
            <div
              className="bg-switch hover:bg-switch-alt mx-4 size-12 cursor-pointer"
              onClick={() => changeSide(side === 'Buy' ? 'Sell' : 'Buy')}
            />
            <div>
              <div className="flex items-center">
                Sell:{' '}
                <SymbolIcon
                  symbol={sellSymbol}
                  className="ml-4 mr-2 inline-block size-5"
                />
                {sellSymbol.name}
              </div>
              <input
                placeholder={'0.0'}
                value={sellAmountInputValue}
                disabled={mutation.isPending}
                onChange={
                  side === 'Buy'
                    ? handleQuoteAmountChange
                    : handleBaseAmountChange
                }
                className={classNames(
                  'w-full mt-2 text-center bg-darkBluishGray9 rounded text-white disabled:bg-mutedGray',
                  sellLimit !== undefined && sellAmount > sellLimit
                    ? 'border-brightRed focus:ring-brightRed'
                    : 'border-darkBluishGray6'
                )}
              />
            </div>
          </div>
          <div className="mt-4 flex w-full items-center justify-end">
            <input
              name="isLimitOrder"
              type="checkbox"
              checked={isLimitOrder}
              disabled={mutation.isPending}
              onChange={handleMarketOrderFlagChange}
              className="size-5 rounded border-white
                         bg-darkBluishGray9
                         text-darkBluishGray9
                         checked:border-white checked:bg-darkBluishGray9
                         hover:border-white hover:bg-darkBluishGray9
                         checked:hover:border-white checked:hover:bg-darkBluishGray9"
            />
            <label htmlFor="isLimitOrder" className="whitespace-nowrap px-4">
              Limit Price
            </label>
            <input
              value={priceInputValue}
              disabled={!isLimitOrder || mutation.isPending}
              placeholder={
                isLimitOrder
                  ? '0.000'
                  : `~${formatUnits(marketPrice, quoteSymbol.decimals)}`
              }
              onChange={handlePriceChange}
              className="w-36 rounded border-darkBluishGray6 bg-darkBluishGray9 text-center text-white disabled:bg-darkBluishGray7"
            />
          </div>
          {sellLimit !== undefined && sellAmount > sellLimit && (
            <>
              <div className="mt-4 text-center text-sm text-olhcRed">
                {sellLimit === 0n ? (
                  <>
                    You don&apos;t have any {sellSymbol.name} available to sell.
                  </>
                ) : (
                  <>
                    You only have{' '}
                    <AmountWithSymbol
                      amount={sellLimit}
                      symbol={sellSymbol}
                      approximate={false}
                    />{' '}
                    available to sell.
                  </>
                )}
              </div>
            </>
          )}
          {notional > 0n &&
            (sellLimit === undefined || sellAmount <= sellLimit) && (
              <>
                <div className="mt-4 text-center text-sm text-darkBluishGray2">
                  <div className="inline-block">
                    {side == 'Buy' ? 'Buying' : 'Selling'}
                  </div>{' '}
                  <AmountWithSymbol
                    amount={baseAmount}
                    symbol={baseSymbol}
                    approximate={false}
                  />{' '}
                  at{' '}
                  {isLimitOrder ? (
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
                  ) : (
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
                  )}
                  <div>
                    FEE:{' '}
                    <AmountWithSymbol
                      amount={fee}
                      symbol={quoteSymbol}
                      approximate={true}
                    />
                  </div>
                </div>
              </>
            )}
          <div className="w-full">
            {walletAddress && exchangeContractAddress ? (
              <SubmitButton
                disabled={!canSubmit}
                onClick={mutation.mutate}
                error={mutation.error?.message}
                caption={() => {
                  if (mutation.isPending) {
                    return 'Submitting order...'
                  } else {
                    return side
                  }
                }}
              />
            ) : (
              <div className="mt-4">
                <Button
                  caption={() => <>Connect Wallet</>}
                  onClick={() => openWalletConnectModal({ view: 'Connect' })}
                  disabled={false}
                  primary={true}
                  style={'full'}
                />
              </div>
            )}
          </div>
          {mutation.isSuccess && (
            <div className="pt-3 text-center">
              <div className="text-green">Order created!</div>
            </div>
          )}
        </>
      }
    />
  )
}
