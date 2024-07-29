import Markets, { Market } from 'markets'
import { ChangeEvent, useCallback, useEffect, useMemo, useState } from 'react'
import TradingSymbol from 'tradingSymbol'
import { apiClient, Balance, FeeRates, Order, OrderSide } from 'apiClient'
import { useWebsocketSubscription } from 'contexts/websocket'
import {
  balancesTopic,
  limitsForMarket,
  limitsTopic,
  OrderBook,
  orderBookTopic,
  myOrdersTopic,
  Publishable
} from 'websocketMessages'
import { Address, formatUnits } from 'viem'
import {
  calculateFee,
  calculateNotional,
  calculateNotionalMinusFee
} from 'utils'
import {
  bigintToScaledDecimal,
  getMarketPrice,
  scaledDecimalToBigint
} from 'utils/pricesUtils'
import { useMutation, UseMutationResult } from '@tanstack/react-query'
import { addressZero, generateOrderNonce, getDomain } from 'utils/eip712'
import Decimal from 'decimal.js'
import { isErrorFromAlias } from '@zodios/core'
import useAmountInputState from 'hooks/useAmountInputState'
import { useConfig, useSignTypedData, BaseError as WagmiError } from 'wagmi'

export type SwapRender = {
  topBalance: Balance | undefined
  topSymbol: TradingSymbol
  bottomBalance: Balance | undefined
  bottomSymbol: TradingSymbol
  mutation: UseMutationResult<
    { orderId: string; requestStatus: 'Rejected' | 'Accepted' },
    Error,
    void,
    unknown
  >
  buyAmountInputValue: string
  sellAmountInputValue: string
  side: OrderSide
  handleQuoteAmountChange: (e: ChangeEvent<HTMLInputElement>) => void
  handleBaseAmountChange: (e: ChangeEvent<HTMLInputElement>) => void
  handleBuyLimitPriceChange: (v: string) => void
  handleSellLimitPriceChange: (v: string) => void
  sellAssetsNeeded: bigint
  handleTopSymbolChange: (newSymbol: TradingSymbol) => void
  handleBottomSymbolChange: (newSymbol: TradingSymbol) => void
  handleChangeSide: () => void
  isLimitOrder: boolean
  handleMarketOrderFlagChange: (c: boolean) => void
  sellLimitPriceInputValue: string
  sellLimitPrice: Decimal
  buyLimitPriceInputValue: string
  buyLimitPrice: Decimal
  limitPrice: Decimal
  limitPriceTooLow: boolean
  limitPriceTooHigh: boolean
  setPriceFromMarketPrice: (incrementDivisor?: bigint) => void
  noPriceFound: boolean
  canSubmit: boolean
  getMarketPrice: (side: OrderSide, amount: bigint) => bigint | undefined
  quoteDecimals: number
  lastOrder: Order | undefined
  percentOffMarket: number | undefined
  handleMaxBaseAmount: () => void
  handleMaxQuoteAmount: () => void
  topLimit: bigint | undefined
  amountTooLow: boolean
}

export function SwapInternals({
  markets,
  exchangeContractAddress,
  walletAddress,
  feeRates,
  onMarketChange,
  onSideChange,
  isLimitOrder: initialIsLimitOrder,
  marketSessionStorageKey,
  sideSessionStorageKey,
  Renderer
}: {
  markets: Markets
  exchangeContractAddress?: Address
  walletAddress?: Address
  feeRates: FeeRates
  onMarketChange: (m: Market) => void
  onSideChange: (s: OrderSide) => void
  isLimitOrder: boolean
  marketSessionStorageKey: string
  sideSessionStorageKey: string
  Renderer: (r: SwapRender) => JSX.Element
}) {
  const [market, setMarket] = useState<Market>(markets.first()!)
  const [topSymbol, setTopSymbol] = useState<TradingSymbol>(
    markets.first()!.quoteSymbol
  )
  const [bottomSymbol, setBottomSymbol] = useState<TradingSymbol>(
    markets.first()!.baseSymbol
  )
  const [side, setSide] = useState<OrderSide>('Buy')
  const [balances, setBalances] = useState<Balance[]>(() => [])
  const [lastOrderId, setLastOrderId] = useState<string | undefined>()
  const [lastOrder, setLastOrder] = useState<Order>()
  const [percentage, setPercentage] = useState<number | null>(null)

  useEffect(() => {
    const selectedMarket = window.sessionStorage.getItem(
      marketSessionStorageKey
    )
    const selectedSide = window.sessionStorage.getItem(sideSessionStorageKey)
    if (selectedMarket && selectedSide) {
      const market = markets.findById(selectedMarket)
      if (market) {
        setMarket(market)
        if (selectedSide === 'Buy') {
          setSide('Buy')
          setTopSymbol(market.quoteSymbol)
          setBottomSymbol(market.baseSymbol)
        } else if (selectedSide === 'Sell') {
          setSide('Sell')
          setTopSymbol(market.baseSymbol)
          setBottomSymbol(market.quoteSymbol)
        }
      }
    }
  }, [markets, marketSessionStorageKey, sideSessionStorageKey])

  const [topBalance, bottomBalance] = useMemo(() => {
    const topBalance = balances.find(
      (balance) => balance.symbol === topSymbol.name
    )
    const bottomBalance = balances.find(
      (balance) => balance.symbol === bottomSymbol.name
    )
    return [topBalance, bottomBalance]
  }, [topSymbol, bottomSymbol, balances])

  const config = useConfig()
  const { signTypedDataAsync } = useSignTypedData()

  const [baseSymbol, quoteSymbol] = useMemo(() => {
    return [market.baseSymbol, market.quoteSymbol]
  }, [market])

  const [sellLimitPriceInputValue, setSellLimitPriceInputValue] = useState('')
  const [buyLimitPriceInputValue, setBuyLimitPriceInputValue] = useState('')

  const buyLimitPrice = useMemo(() => {
    if (side === 'Sell') {
      return inputValueToDecimal(buyLimitPriceInputValue).toSignificantDigits(6)
    } else {
      return Decimal.max(
        market.tickSize,
        inputValueToDecimal(buyLimitPriceInputValue)
          .divToInt(market.tickSize)
          .mul(market.tickSize)
      )
    }
  }, [buyLimitPriceInputValue, market.tickSize, side])

  const sellLimitPrice = useMemo(() => {
    if (side === 'Buy') {
      return inputValueToDecimal(sellLimitPriceInputValue).toSignificantDigits(
        6
      )
    } else {
      return Decimal.max(
        market.tickSize,
        inputValueToDecimal(sellLimitPriceInputValue)
          .divToInt(market.tickSize)
          .mul(market.tickSize)
      )
    }
  }, [sellLimitPriceInputValue, side, market.tickSize])

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

  const [isLimitOrder, setIsLimitOrder] = useState(initialIsLimitOrder)

  function inputValueToDecimal(v: string): Decimal {
    try {
      return new Decimal(v)
    } catch (e) {
      if (e instanceof Error && /DecimalError/.test(e.message)) {
        return new Decimal(0)
      }
      throw e
    }
  }

  const limitPrice = useMemo(() => {
    if (side === 'Sell') {
      return sellLimitPrice.divToInt(market.tickSize).mul(market.tickSize)
    } else {
      if (sellLimitPrice.isZero()) {
        return new Decimal(0)
      } else {
        const invertedPrice = new Decimal(1).div(sellLimitPrice)
        return invertedPrice.divToInt(market.tickSize).mul(market.tickSize)
      }
    }
  }, [sellLimitPrice, side, market])

  const [orderBook, setOrderBook] = useState<OrderBook | undefined>(undefined)
  const [secondMarketOrderBook, setSecondMarketOrderBook] = useState<
    OrderBook | undefined
  >(undefined)

  const [baseLimit, setBaseLimit] = useState<bigint | undefined>(undefined)
  const [quoteLimit, setQuoteLimit] = useState<bigint | undefined>(undefined)

  const topLimit = useMemo(() => {
    return side == 'Buy' ? quoteLimit : baseLimit
  }, [baseLimit, quoteLimit, side])

  useWebsocketSubscription({
    topics: useMemo(() => {
      if (market.isBackToBack()) {
        return [
          balancesTopic,
          myOrdersTopic,
          orderBookTopic(market.marketIds[0]),
          orderBookTopic(market.marketIds[1]),
          limitsTopic
        ]
      } else {
        return [
          balancesTopic,
          myOrdersTopic,
          orderBookTopic(market.id),
          limitsTopic
        ]
      }
    }, [market]),
    handler: useCallback(
      (message: Publishable) => {
        if (message.type === 'OrderBook') {
          if (market.isBackToBack()) {
            message.marketId == market.marketIds[0]
              ? setOrderBook(message)
              : setSecondMarketOrderBook(message)
          } else {
            setOrderBook(message)
          }
        } else if (message.type === 'Limits') {
          if (market.isBackToBack()) {
            const firstMarketLimits = limitsForMarket(
              message,
              market.marketIds[0]
            )
            if (firstMarketLimits) {
              setBaseLimit(firstMarketLimits.base)
            }

            const lastMarketLimits = limitsForMarket(
              message,
              market.marketIds[market.marketIds.length - 1]
            )
            if (lastMarketLimits) {
              setQuoteLimit(lastMarketLimits.quote)
            }
          } else {
            const marketLimits = limitsForMarket(message, market.id)
            if (marketLimits) {
              setBaseLimit(marketLimits.base)
              setQuoteLimit(marketLimits.quote)
            }
          }
        } else if (message.type === 'Balances') {
          setBalances(message.balances)
        } else if (message.type === 'MyOrderUpdated') {
          setLastOrder(message.order)
        }
      },
      [market]
    ),
    onUnsubscribe: useCallback(() => {
      setBalances([])
      setBaseLimit(undefined)
      setQuoteLimit(undefined)
      setSecondMarketOrderBook(undefined)
      setOrderBook(undefined)
    }, [])
  })

  const marketPrice = useMemo(() => {
    if (
      orderBook === undefined ||
      (market.isBackToBack() && secondMarketOrderBook === undefined)
    ) {
      return 0n
    }
    return getMarketPrice(
      side,
      baseAmount,
      market,
      orderBook,
      markets,
      secondMarketOrderBook
    )
  }, [side, baseAmount, orderBook, secondMarketOrderBook, market, markets])

  const limitPriceAsBigInt = useMemo(() => {
    return BigInt(
      limitPrice
        .mul(new Decimal(10).pow(market.quoteSymbol.decimals))
        .floor()
        .toFixed(0)
    )
  }, [limitPrice, market])

  const { notional, fee } = useMemo(() => {
    if (isLimitOrder) {
      const price = limitPrice ? limitPriceAsBigInt : marketPrice
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
  }, [
    limitPrice,
    marketPrice,
    baseAmount,
    isLimitOrder,
    baseSymbol,
    feeRates,
    limitPriceAsBigInt
  ])

  const [baseAmountManuallyChanged, setBaseAmountManuallyChanged] =
    useState(false)
  const [quoteAmountManuallyChanged, setQuoteAmountManuallyChanged] =
    useState(false)
  const [noPriceFound, setNoPriceFound] = useState(false)

  function handleBaseAmountChange(e: ChangeEvent<HTMLInputElement>) {
    setQuoteAmountManuallyChanged(false)
    setBaseAmountManuallyChanged(true)
    setBaseAmountInputValue(e.target.value)
    setPercentage(null)
    mutation.reset()
  }

  function handleMaxBaseAmount() {
    setQuoteAmountManuallyChanged(false)
    setBaseAmountManuallyChanged(true)
    setBaseAmountInputValue(
      formatUnits(baseLimit ?? BigInt(0), baseSymbol.decimals)
    )
    setPercentage(100)
    mutation.reset()
  }

  function handleMaxQuoteAmount() {
    setQuoteAmountManuallyChanged(true)
    setBaseAmountManuallyChanged(false)
    setQuoteAmountInputValue(
      formatUnits(
        calculateNotionalMinusFee(quoteLimit ?? BigInt(0), feeRates.taker),
        quoteSymbol.decimals
      )
    )
    setPercentage(100)
    mutation.reset()
  }

  useEffect(() => {
    if (baseAmountManuallyChanged) {
      if (
        orderBook !== undefined &&
        (!market.isBackToBack() || secondMarketOrderBook !== undefined)
      ) {
        const indicativePrice =
          isLimitOrder && !limitPrice.isZero()
            ? limitPriceAsBigInt
            : getMarketPrice(
                side,
                baseAmount,
                market,
                orderBook,
                markets,
                secondMarketOrderBook
              )
        if (indicativePrice === 0n) {
          setNoPriceFound(true)
        } else {
          setNoPriceFound(false)
          const notional =
            (baseAmount * indicativePrice) /
            BigInt(Math.pow(10, baseSymbol.decimals))
          setQuoteAmountInputValue(formatUnits(notional, quoteSymbol.decimals))
        }
      }
    }
  }, [
    baseAmount,
    baseAmountManuallyChanged,
    setQuoteAmountInputValue,
    orderBook,
    secondMarketOrderBook,
    market,
    markets,
    baseSymbol,
    quoteSymbol,
    side,
    isLimitOrder,
    limitPrice,
    limitPriceAsBigInt
  ])

  function getEquilibriumPrice(
    quoteAmount: bigint,
    side: OrderSide,
    market: Market,
    orderBook: OrderBook,
    markets: Markets,
    secondMarketOrderBook: OrderBook | undefined,
    baseDecimals: number
  ): bigint {
    let price = 0n
    let nextPrice = getMarketPrice(
      side,
      1n,
      market,
      orderBook,
      markets,
      secondMarketOrderBook
    )
    while (price !== nextPrice) {
      price = nextPrice
      const amount = (quoteAmount * BigInt(Math.pow(10, baseDecimals))) / price
      nextPrice = getMarketPrice(
        side,
        amount,
        market,
        orderBook,
        markets,
        secondMarketOrderBook
      )
    }
    return price
  }

  useEffect(() => {
    if (quoteAmountManuallyChanged) {
      if (
        orderBook !== undefined &&
        (!market.isBackToBack() || secondMarketOrderBook !== undefined)
      ) {
        const indicativePrice =
          isLimitOrder && !limitPrice.isZero()
            ? limitPriceAsBigInt
            : getEquilibriumPrice(
                quoteAmount,
                side,
                market,
                orderBook,
                markets,
                secondMarketOrderBook,
                baseSymbol.decimals
              )
        if (indicativePrice === 0n) {
          setNoPriceFound(true)
        } else {
          setNoPriceFound(false)
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
    secondMarketOrderBook,
    market,
    markets,
    baseSymbol,
    side,
    isLimitOrder,
    limitPrice,
    limitPriceAsBigInt
  ])

  function handleQuoteAmountChange(e: ChangeEvent<HTMLInputElement>) {
    setBaseAmountManuallyChanged(false)
    setQuoteAmountManuallyChanged(true)
    setQuoteAmountInputValue(e.target.value)
    setPercentage(null)
    mutation.reset()
  }

  const [sellLimitPriceManuallyChanged, setSellLimitPriceManuallyChanged] =
    useState(false)
  const [buyLimitPriceManuallyChanged, setBuyLimitPriceManuallyChanged] =
    useState(false)

  function handleSellLimitPriceChange(v: string) {
    setSellLimitPriceManuallyChanged(true)
    setBuyLimitPriceManuallyChanged(false)
    setSellLimitPriceInputValue(v)

    mutation.reset()
  }

  useEffect(() => {
    if (sellLimitPriceManuallyChanged) {
      if (sellLimitPriceInputValue) {
        if (sellLimitPrice.isZero()) {
          setBuyLimitPriceInputValue('')
        } else {
          setBuyLimitPriceInputValue(
            new Decimal(1).div(sellLimitPrice).toSignificantDigits(6).toString()
          )
        }
      } else {
        setBuyLimitPriceInputValue('')
      }
    }
  }, [
    sellLimitPriceInputValue,
    sellLimitPrice,
    sellLimitPriceManuallyChanged,
    setBuyLimitPriceInputValue,
    quoteSymbol
  ])

  useEffect(() => {
    if (buyLimitPriceManuallyChanged) {
      if (buyLimitPriceInputValue) {
        if (buyLimitPrice.isZero()) {
          setSellLimitPriceInputValue('')
        } else {
          setSellLimitPriceInputValue(
            new Decimal(1).div(buyLimitPrice).toSignificantDigits(6).toString()
          )
        }
      } else {
        setSellLimitPriceInputValue('')
      }
    }
  }, [
    buyLimitPriceManuallyChanged,
    buyLimitPriceInputValue,
    buyLimitPrice,
    setSellLimitPriceInputValue,
    quoteSymbol
  ])

  function handleBuyLimitPriceChange(v: string) {
    setSellLimitPriceManuallyChanged(false)
    setBuyLimitPriceManuallyChanged(true)
    setBuyLimitPriceInputValue(v)

    mutation.reset()
  }

  function clearAmountFields() {
    setQuoteAmountManuallyChanged(false)
    setBaseAmountManuallyChanged(false)
    setBaseAmountInputValue('')
    setQuoteAmountInputValue('')
    setBuyLimitPriceManuallyChanged(false)
    setSellLimitPriceManuallyChanged(false)
    setBuyLimitPriceInputValue('')
    setSellLimitPriceInputValue('')
  }

  function saveMarketAndSide(market: Market, side: OrderSide) {
    setSide(side)
    setMarket(market)
    window.sessionStorage.setItem(marketSessionStorageKey, market.id)
    window.sessionStorage.setItem(sideSessionStorageKey, side)
    onMarketChange(market)
    onSideChange(side)
  }

  function handleTopSymbolChange(newSymbol: TradingSymbol) {
    const newMarket = getMarketForSideAndSymbol(side, newSymbol, bottomSymbol)
    setTopSymbol(newSymbol)
    if (newMarket.quoteSymbol.name === newSymbol.name) {
      saveMarketAndSide(newMarket, 'Buy')
      setBottomSymbol(newMarket.baseSymbol)
    } else {
      saveMarketAndSide(newMarket, 'Sell')
      setBottomSymbol(newMarket.quoteSymbol)
    }
    clearAmountFields()
    mutation.reset()
  }

  function handleBottomSymbolChange(newSymbol: TradingSymbol) {
    const newMarket = getMarketForSideAndSymbol(
      side === 'Sell' ? 'Buy' : 'Sell',
      newSymbol,
      topSymbol
    )
    setBottomSymbol(newSymbol)
    if (newMarket.quoteSymbol.name === newSymbol.name) {
      saveMarketAndSide(newMarket, 'Sell')
      setTopSymbol(newMarket.baseSymbol)
    } else {
      setTopSymbol(newMarket.quoteSymbol)
      saveMarketAndSide(newMarket, 'Buy')
    }
    clearAmountFields()
    mutation.reset()
  }

  function handleChangeSide() {
    const newSide = side === 'Buy' ? 'Sell' : 'Buy'
    saveMarketAndSide(market, newSide)
    const tempSymbol = topSymbol
    setTopSymbol(bottomSymbol)
    setBottomSymbol(tempSymbol)
    setSellLimitPriceInputValue('')
    mutation.reset()
  }

  function handleMarketOrderFlagChange(c: boolean) {
    setSellLimitPriceInputValue('')
    setBuyLimitPriceInputValue('')
    setIsLimitOrder(c)
    mutation.reset()
  }

  function setPriceFromMarketPrice(incrementDivisor?: bigint) {
    if (side === 'Buy') {
      let rawPrice
      if (incrementDivisor) {
        rawPrice = parseFloat(
          formatUnits(
            marketPrice - marketPrice / incrementDivisor,
            quoteSymbol.decimals
          )
        )
      } else {
        rawPrice = parseFloat(formatUnits(marketPrice, quoteSymbol.decimals))
      }
      const invertedPrice = 1.0 / rawPrice
      handleSellLimitPriceChange(invertedPrice.toFixed(18))
    } else {
      let rawPrice
      if (incrementDivisor) {
        rawPrice = parseFloat(
          formatUnits(
            marketPrice + marketPrice / incrementDivisor,
            quoteSymbol.decimals
          )
        )
      } else {
        rawPrice = parseFloat(formatUnits(marketPrice, quoteSymbol.decimals))
      }
      handleSellLimitPriceChange(
        rawPrice.toFixed(market.tickSize.decimalPlaces())
      )
    }
  }

  const mutation = useMutation({
    mutationFn: async () => {
      setLastOrderId(undefined)
      try {
        const nonce = generateOrderNonce()
        const signature = percentage
          ? await signTypedDataAsync({
              types: {
                EIP712Domain: [
                  { name: 'name', type: 'string' },
                  { name: 'version', type: 'string' },
                  { name: 'chainId', type: 'uint256' },
                  { name: 'verifyingContract', type: 'address' }
                ],
                Order: [
                  { name: 'sender', type: 'address' },
                  { name: 'baseChainId', type: 'uint256' },
                  { name: 'baseToken', type: 'address' },
                  { name: 'quoteChainId', type: 'uint256' },
                  { name: 'quoteToken', type: 'address' },
                  { name: 'percentage', type: 'int256' },
                  { name: 'price', type: 'uint256' },
                  { name: 'nonce', type: 'int256' }
                ]
              },
              domain: getDomain(exchangeContractAddress!, config.state.chainId),
              primaryType: 'Order',
              message: {
                sender: walletAddress!,
                baseChainId: BigInt(baseSymbol.chainId),
                baseToken: baseSymbol.contractAddress ?? addressZero,
                quoteChainId: BigInt(quoteSymbol.chainId),
                quoteToken: quoteSymbol.contractAddress ?? addressZero,
                percentage: BigInt(percentage),
                price: isLimitOrder ? limitPriceAsBigInt : 0n,
                nonce: BigInt('0x' + nonce)
              }
            })
          : await signTypedDataAsync({
              types: {
                EIP712Domain: [
                  { name: 'name', type: 'string' },
                  { name: 'version', type: 'string' },
                  { name: 'chainId', type: 'uint256' },
                  { name: 'verifyingContract', type: 'address' }
                ],
                Order: [
                  { name: 'sender', type: 'address' },
                  { name: 'baseChainId', type: 'uint256' },
                  { name: 'baseToken', type: 'address' },
                  { name: 'quoteChainId', type: 'uint256' },
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
                baseChainId: BigInt(baseSymbol.chainId),
                baseToken: baseSymbol.contractAddress ?? addressZero,
                quoteChainId: BigInt(quoteSymbol.chainId),
                quoteToken: quoteSymbol.contractAddress ?? addressZero,
                amount: side == 'Buy' ? baseAmount : -baseAmount,
                price: isLimitOrder ? limitPriceAsBigInt : 0n,
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
            amount: {
              type: 'fixed',
              value: baseAmount
            },
            price: limitPrice,
            signature: signature,
            verifyingChainId: config.state.chainId
          })
        } else if (market.isBackToBack()) {
          response = await apiClient.createOrder({
            nonce: nonce,
            marketId: market.marketIds[0],
            secondMarketId: market.marketIds[1],
            type: 'backToBackMarket',
            side: side,
            amount: percentage
              ? {
                  type: 'percent',
                  value: percentage
                }
              : {
                  type: 'fixed',
                  value: baseAmount
                },
            signature: signature,
            verifyingChainId: config.state.chainId
          })
        } else {
          response = await apiClient.createOrder({
            nonce: nonce,
            marketId: `${baseSymbol.name}/${quoteSymbol.name}`,
            type: 'market',
            side: side,
            amount: percentage
              ? {
                  type: 'percent',
                  value: percentage
                }
              : {
                  type: 'fixed',
                  value: baseAmount
                },
            signature: signature,
            verifyingChainId: config.state.chainId
          })
        }
        setLastOrderId(response.orderId)
        clearAmountFields()
        return response
      } catch (error) {
        throw Error(
          isErrorFromAlias(apiClient.api, 'createOrder', error)
            ? error.response.data.errors[0].displayMessage
            : (error as WagmiError).shortMessage || 'Something went wrong'
        )
      }
    },
    onSuccess: () => {
      setTimeout(() => {
        setLastOrder(undefined)
        setLastOrderId(undefined)
        mutation.reset()
      }, 3000)
    }
  })

  const amountTooLow = useMemo(() => {
    return notional > BigInt(0) && fee < market.minFee
  }, [notional, fee, market.minFee])

  const canSubmit = useMemo(() => {
    if (mutation.isPending) return false
    if (baseAmount <= 0n) return false
    if (
      isLimitOrder &&
      (limitPrice.lt(market.minAllowedBidPrice) ||
        limitPrice.gt(market.maxAllowedOfferPrice))
    )
      return false

    if (side == 'Buy' && notional + fee > (quoteLimit || 0n)) return false
    if (side == 'Sell' && baseAmount > (baseLimit || 0n)) return false

    if (noPriceFound && !isLimitOrder) return false

    return !amountTooLow
  }, [
    mutation.isPending,
    side,
    baseAmount,
    limitPrice,
    notional,
    isLimitOrder,
    quoteLimit,
    baseLimit,
    noPriceFound,
    fee,
    market.minAllowedBidPrice,
    market.maxAllowedOfferPrice,
    amountTooLow
  ])

  const [buyAmountInputValue, sellAmountInputValue] = useMemo(() => {
    return side === 'Buy'
      ? [baseAmountInputValue, quoteAmountInputValue]
      : [quoteAmountInputValue, baseAmountInputValue]
  }, [side, baseAmountInputValue, quoteAmountInputValue])

  function getMarketForSideAndSymbol(
    side: OrderSide,
    newSymbol: TradingSymbol,
    otherSymbol: TradingSymbol
  ): Market {
    // look for a market where the new symbol is available on the desired swap direction and has the existing other symbol
    return (
      markets.find(
        (m) =>
          (side === 'Sell' &&
            m.quoteSymbol === newSymbol &&
            m.baseSymbol === otherSymbol) ||
          (side === 'Buy' &&
            m.baseSymbol === newSymbol &&
            m.quoteSymbol === otherSymbol)
      ) ??
      // failing that, where the new symbol is available on the other swap direction and has the existing buy symbol
      markets.find(
        (m) =>
          (side === 'Sell' &&
            m.baseSymbol === newSymbol &&
            m.quoteSymbol === otherSymbol) ||
          (side === 'Buy' &&
            m.quoteSymbol === newSymbol &&
            m.baseSymbol === otherSymbol)
      ) ??
      // failing that, one where the new symbol is available on the desired swap direction, even if the existing buy symbol is not
      markets.find(
        (m) =>
          (side === 'Sell' && m.quoteSymbol === newSymbol) ||
          (side === 'Buy' && m.baseSymbol === newSymbol)
      ) ??
      // failing that, one where the new symbol is available on the other swap direction (this one must succeed)
      markets.find(
        (m) =>
          (side === 'Sell' && m.baseSymbol === newSymbol) ||
          (side === 'Buy' && m.quoteSymbol === newSymbol)
      )!
    )
  }

  const sellAssetsNeeded = useMemo(() => {
    return topSymbol.name === quoteSymbol.name
      ? notional + fee - (quoteLimit ?? notional + fee)
      : baseAmount - (baseLimit ?? baseAmount)
  }, [topSymbol, quoteSymbol, notional, fee, quoteLimit, baseAmount, baseLimit])

  function getSimulatedPrice(
    side: OrderSide,
    amount: bigint
  ): bigint | undefined {
    if (orderBook && (!market.isBackToBack() || secondMarketOrderBook)) {
      const marketPrice = getMarketPrice(
        side,
        amount,
        market,
        orderBook,
        markets,
        secondMarketOrderBook
      )
      if (side === 'Sell' && marketPrice !== 0n) {
        return scaledDecimalToBigint(
          new Decimal(1).div(
            bigintToScaledDecimal(marketPrice, quoteSymbol.decimals)
          ),
          quoteSymbol.decimals
        )
      }
      return marketPrice
    }
    return
  }

  const percentOffMarket = useMemo(() => {
    if (orderBook) {
      const mktPrice = new Decimal(
        formatUnits(
          getMarketPrice(
            side,
            baseAmount,
            market,
            orderBook,
            markets,
            secondMarketOrderBook
          ),
          quoteSymbol.decimals
        )
      )
      if (mktPrice && !buyLimitPrice.isZero()) {
        return (() => {
          if (side === 'Buy') {
            return mktPrice.minus(buyLimitPrice)
          } else {
            const invertedPrice = new Decimal(1).div(buyLimitPrice)
            return invertedPrice.minus(mktPrice)
          }
        })()
          .div(mktPrice)
          .mul(100)
          .toDecimalPlaces(1)
          .toNumber()
      }
    }
    return
  }, [
    orderBook,
    side,
    baseAmount,
    market,
    markets,
    buyLimitPrice,
    quoteSymbol,
    secondMarketOrderBook
  ])

  const [limitPriceTooLow, limitPriceTooHigh] = useMemo(() => {
    const limitPriceTooLow =
      isLimitOrder &&
      limitPrice.gt(new Decimal(0)) &&
      limitPrice.lt(market.minAllowedBidPrice)
    const limitPriceTooHigh =
      isLimitOrder && limitPrice.gt(market.maxAllowedOfferPrice)

    return side === 'Sell'
      ? [limitPriceTooLow, limitPriceTooHigh]
      : [limitPriceTooHigh, limitPriceTooLow]
  }, [
    isLimitOrder,
    limitPrice,
    market.minAllowedBidPrice,
    market.maxAllowedOfferPrice,
    side
  ])

  return Renderer({
    topBalance,
    topSymbol,
    bottomBalance,
    bottomSymbol,
    mutation,
    buyAmountInputValue,
    sellAmountInputValue,
    side,
    handleQuoteAmountChange,
    handleBaseAmountChange,
    handleBuyLimitPriceChange,
    handleSellLimitPriceChange,
    sellAssetsNeeded,
    handleTopSymbolChange,
    handleBottomSymbolChange,
    handleChangeSide,
    isLimitOrder,
    handleMarketOrderFlagChange,
    sellLimitPriceInputValue,
    sellLimitPrice,
    buyLimitPriceInputValue,
    buyLimitPrice,
    limitPrice,
    limitPriceTooLow,
    limitPriceTooHigh,
    setPriceFromMarketPrice,
    noPriceFound,
    canSubmit,
    getMarketPrice: getSimulatedPrice,
    quoteDecimals: market.quoteDecimalPlaces,
    lastOrder: lastOrderId === lastOrder?.id ? lastOrder : undefined,
    percentOffMarket,
    handleMaxBaseAmount,
    handleMaxQuoteAmount,
    topLimit,
    amountTooLow
  })
}
