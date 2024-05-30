import Markets, { Market } from 'markets'
import React, {
  ChangeEvent,
  LegacyRef,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState
} from 'react'
import TradingSymbol from 'tradingSymbol'
import { apiClient, Balance, FeeRates, OrderSide } from 'apiClient'
import { useWebsocketSubscription } from 'contexts/websocket'
import {
  balancesTopic,
  limitsTopic,
  OrderBook,
  orderBookTopic,
  Publishable
} from 'websocketMessages'
import { Address, formatUnits, parseUnits } from 'viem'
import { SymbolSelector } from 'components/Screens/HomeScreen/SymbolSelector'
import AmountInput from 'components/common/AmountInput'
import { calculateFee, calculateNotional, classNames } from 'utils'
import { useWeb3Modal } from '@web3modal/wagmi/react'
import { getMarketPrice } from 'utils/pricesUtils'
import { useMutation } from '@tanstack/react-query'
import { addressZero, generateOrderNonce, getDomain } from 'utils/eip712'
import Decimal from 'decimal.js'
import { isErrorFromAlias } from '@zodios/core'
import useAmountInputState from 'hooks/useAmountInputState'
import {
  useConfig,
  useSignTypedData,
  BaseError as WagmiError,
  useSwitchChain
} from 'wagmi'
import SwapIcon from 'assets/Swap.svg'
import SubmitButton from 'components/common/SubmitButton'
import { Button } from 'components/common/Button'
import { AmountWithSymbol } from 'components/common/AmountWithSymbol'
import { allChains } from 'wagmiConfig'
import DepositModal from 'components/Screens/HomeScreen/DepositModal'
import { useMeasure } from 'react-use'

export function Swap({
  markets,
  exchangeContractAddress,
  walletAddress,
  feeRates,
  onMarketChange
}: {
  markets: Markets
  exchangeContractAddress?: Address
  walletAddress?: Address
  feeRates: FeeRates
  onMarketChange: (m: Market) => void
}) {
  const [market, setMarket] = useState<Market>(markets.first()!)
  const [topSymbol, setTopSymbol] = useState<TradingSymbol>(
    markets.first()!.quoteSymbol
  )
  const [bottomSymbol, setBottomSymbol] = useState<TradingSymbol>(
    markets.first()!.baseSymbol
  )
  const [side, setSide] = useState<OrderSide>('Buy')
  const [animateSide, setAnimateSide] = useState(false)
  const [balances, setBalances] = useState<Balance[]>(() => [])

  useEffect(() => {
    const selectedMarket = window.sessionStorage.getItem('market')
    const selectedSide = window.sessionStorage.getItem('side')
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
  }, [markets])

  useWebsocketSubscription({
    topics: useMemo(() => [balancesTopic], []),
    handler: useCallback((message: Publishable) => {
      if (message.type === 'Balances') {
        setBalances(message.balances)
      }
    }, [])
  })

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

  const {
    inputValue: priceInputValue,
    setInputValue: setPriceInputValue,
    valueInFundamentalUnits: priceInput
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

  const limitPrice = useMemo(() => {
    const tickAsInt = parseUnits(
      market.tickSize.toString(),
      quoteSymbol.decimals
    )
    if (side === 'Buy') {
      return priceInput - (priceInput % tickAsInt)
    } else {
      const rawPrice = parseFloat(formatUnits(priceInput, baseSymbol.decimals))
      if (rawPrice) {
        const invertedPrice = parseUnits(
          (1.0 / rawPrice).toString(),
          quoteSymbol.decimals
        )
        return invertedPrice - (invertedPrice % tickAsInt)
      } else {
        return BigInt(0)
      }
    }
  }, [priceInput, side, market, quoteSymbol, baseSymbol])

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
      const notional = calculateNotional(
        limitPrice || marketPrice,
        baseAmount,
        baseSymbol
      )
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
  }, [limitPrice, marketPrice, baseAmount, isLimitOrder, baseSymbol, feeRates])

  const [baseAmountManuallyChanged, setBaseAmountManuallyChanged] =
    useState(false)
  const [quoteAmountManuallyChanged, setQuoteAmountManuallyChanged] =
    useState(false)
  const [noPriceFound, setNoPriceFound] = useState(false)

  function handleBaseAmountChange(e: ChangeEvent<HTMLInputElement>) {
    setQuoteAmountManuallyChanged(false)
    setBaseAmountManuallyChanged(true)
    setBaseAmountInputValue(e.target.value)

    mutation.reset()
  }

  useEffect(() => {
    if (baseAmountManuallyChanged) {
      setBaseAmountManuallyChanged(false)
      if (orderBook !== undefined) {
        const indicativePrice =
          isLimitOrder && limitPrice !== 0n
            ? limitPrice
            : getMarketPrice(side, baseAmount, market, orderBook)
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
    market,
    baseSymbol,
    quoteSymbol,
    side,
    isLimitOrder,
    limitPrice
  ])

  useEffect(() => {
    if (quoteAmountManuallyChanged) {
      setQuoteAmountManuallyChanged(false)
      if (orderBook !== undefined) {
        const indicativePrice =
          isLimitOrder && limitPrice !== 0n
            ? limitPrice
            : getMarketPrice(
                side,
                (baseAmount ?? 1) || BigInt(1),
                market,
                orderBook
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
    market,
    baseAmount,
    baseSymbol,
    side,
    isLimitOrder,
    limitPrice
  ])

  function handleQuoteAmountChange(e: ChangeEvent<HTMLInputElement>) {
    setBaseAmountManuallyChanged(false)
    setQuoteAmountManuallyChanged(true)
    setQuoteAmountInputValue(e.target.value)
    mutation.reset()
  }

  function clearAmountFields() {
    setQuoteAmountManuallyChanged(false)
    setBaseAmountManuallyChanged(false)
    setBaseAmountInputValue('')
    setQuoteAmountInputValue('')
    setPriceInputValue('')
  }

  function saveMarketAndSide(market: Market, side: OrderSide) {
    setSide(side)
    setMarket(market)
    window.sessionStorage.setItem('market', market.id)
    window.sessionStorage.setItem('side', side)
    onMarketChange(market)
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
    setAnimateSide(true)
    setTimeout(() => {
      setAnimateSide(false)
    }, 1000)
    saveMarketAndSide(market, side === 'Buy' ? 'Sell' : 'Buy')
    const tempSymbol = topSymbol
    setTopSymbol(bottomSymbol)
    setBottomSymbol(tempSymbol)
    setPriceInputValue('')
    mutation.reset()
  }

  function handlePriceChange(e: ChangeEvent<HTMLInputElement>) {
    setPriceInputValue(e.target.value)
    mutation.reset()
  }

  function handleMarketOrderFlagChange(e: ChangeEvent<HTMLInputElement>) {
    setPriceInputValue('')
    setIsLimitOrder(e.target.checked)
    mutation.reset()
  }

  function setPriceFromMarketPrice(incrementDivisor?: bigint) {
    if (side === 'Buy') {
      if (incrementDivisor) {
        const rawPrice = parseFloat(
          formatUnits(
            marketPrice + marketPrice / incrementDivisor,
            quoteSymbol.decimals
          )
        )
        setPriceInputValue(rawPrice.toFixed(market.tickSize.decimalPlaces()))
      } else {
        const rawPrice = parseFloat(
          formatUnits(marketPrice, quoteSymbol.decimals)
        )
        setPriceInputValue(rawPrice.toFixed(market.tickSize.decimalPlaces()))
      }
    } else {
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
      setPriceInputValue(invertedPrice.toFixed(6))
    }
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
            price: isLimitOrder ? limitPrice : 0n,
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
            price: new Decimal(formatUnits(limitPrice, quoteSymbol.decimals)),
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
        }
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
      setTimeout(mutation.reset, 3000)
    }
  })

  const canSubmit = useMemo(() => {
    if (mutation.isPending) return false
    if (baseAmount <= 0n) return false
    if (limitPrice <= 0n && isLimitOrder) return false

    if (side == 'Buy' && notional + fee > (quoteLimit || 0n)) return false
    if (side == 'Sell' && baseAmount > (baseLimit || 0n)) return false

    if (noPriceFound) return false

    return true
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
    fee
  ])

  const [buyAmountInputValue, sellAmountInputValue] = useMemo(() => {
    return side === 'Buy'
      ? [baseAmountInputValue, quoteAmountInputValue]
      : [quoteAmountInputValue, baseAmountInputValue]
  }, [side, baseAmountInputValue, quoteAmountInputValue])
  const [switchToChainId, setSwitchToChainId] = useState<number | null>(null)
  const { switchChain } = useSwitchChain()

  useEffect(() => {
    if (switchToChainId) {
      const chain = allChains.find((c) => c.id == switchToChainId)
      chain &&
        switchChain({
          addEthereumChainParameter: {
            chainName: chain.name,
            nativeCurrency: chain.nativeCurrency,
            rpcUrls: chain.rpcUrls.default.http,
            blockExplorerUrls: [chain.blockExplorers.default.url]
          },
          chainId: chain.id
        })
    }
    setSwitchToChainId(null)
  }, [switchToChainId, switchChain])

  const [depositSymbol, setDepositSymbol] = useState<TradingSymbol | null>(null)
  const [showDepositModal, setShowDepositModal] = useState<boolean>(false)
  const [depositRequestedAmount, setDepositRequestedAmount] =
    useState<bigint>(0n)

  function openDepositModal(symbol: TradingSymbol, sellAssetsNeeded?: bigint) {
    setDepositSymbol(symbol)
    if (sellAssetsNeeded) {
      setDepositRequestedAmount(sellAssetsNeeded)
    }
    setShowDepositModal(true)
    if (symbol.chainId != config.state.chainId) {
      setSwitchToChainId(symbol.chainId)
    }
  }

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

  function depositAmount(deposit: Balance | undefined, symbol: TradingSymbol) {
    return deposit?.available ?? BigInt(0) > BigInt(0) ? (
      <>
        <span className="font-[400] text-darkBluishGray2">On deposit:</span>
        <span className="text-lightBluishGray2">
          <span
            className={
              'inline-block max-w-[10ch] overflow-x-clip text-ellipsis text-lightBluishGray2 hover:max-w-full'
            }
          >
            {deposit && formatUnits(deposit.available, symbol.decimals)}
          </span>{' '}
          {symbol.name}
        </span>
      </>
    ) : (
      <>
        <span className="font-[400] text-darkBluishGray2">
          You have not deposited any {symbol.name}
        </span>
      </>
    )
  }

  const sellAssetsNeeded = useMemo(() => {
    return topSymbol.name === quoteSymbol.name
      ? notional + fee - (quoteLimit || 0n)
      : baseAmount - (baseLimit || 0n)
  }, [topSymbol, quoteSymbol, notional, fee, quoteLimit, baseAmount, baseLimit])

  const sellAmountInputRef = useRef<HTMLInputElement>(null)

  return (
    <>
      <div className="w-[680px] space-y-4 rounded-[20px] bg-swapModalBackground p-8">
        <div className="rounded-[20px] bg-swapRowBackground p-4">
          <div className="mb-2 flex flex-row justify-between">
            <span className="text-base text-darkBluishGray1">Sell</span>
            <div className="flex flex-row items-baseline space-x-2 text-sm">
              {depositAmount(topBalance, topSymbol)}
              <button
                className="rounded bg-swapDropdownBackground px-2 text-darkBluishGray2 hover:bg-swapHighlight"
                onClick={() => openDepositModal(topSymbol)}
              >
                Deposit
              </button>
            </div>
          </div>
          <div
            className="flex cursor-text flex-row justify-between"
            onClick={() => sellAmountInputRef.current?.focus()}
          >
            <SellAmountInput
              value={sellAmountInputValue}
              disabled={false}
              onChange={
                side === 'Buy'
                  ? handleQuoteAmountChange
                  : handleBaseAmountChange
              }
              sellAssetsNeeded={sellAssetsNeeded}
              onDeposit={() => {
                openDepositModal(topSymbol, sellAssetsNeeded)
              }}
              inputRef={sellAmountInputRef}
            />
            <SymbolSelector
              markets={markets}
              selected={topSymbol}
              onChange={handleTopSymbolChange}
            />
          </div>
        </div>
        <div
          className="relative flex w-full justify-center"
          style={{ marginTop: '-32px', top: '24px' }}
        >
          <img
            className={classNames(
              'cursor-pointer',
              animateSide && 'animate-swivel'
            )}
            src={SwapIcon}
            alt={'Swap'}
            onClick={() => handleChangeSide()}
          />
        </div>
        <div className="rounded-[20px] bg-swapRowBackground p-4">
          <div className="mb-2 flex flex-row justify-between">
            <span className="text-base text-darkBluishGray1">Buy</span>
            <div className="flex flex-row space-x-2 align-middle text-sm">
              {depositAmount(bottomBalance, bottomSymbol)}
            </div>
          </div>
          <div className="flex flex-row justify-between">
            <AmountInput
              className="!focus:ring-0 bg-swapRowBackground text-left text-xl !ring-0"
              value={buyAmountInputValue}
              disabled={false}
              onChange={
                side === 'Buy'
                  ? handleBaseAmountChange
                  : handleQuoteAmountChange
              }
            />
            <SymbolSelector
              markets={markets}
              selected={bottomSymbol}
              onChange={handleBottomSymbolChange}
            />
          </div>
        </div>
        <div className="text-center">
          <input
            id="isLimitOrder"
            name="isLimitOrder"
            type="checkbox"
            checked={isLimitOrder}
            disabled={mutation.isPending}
            onChange={handleMarketOrderFlagChange}
            className="!focus:border-0 size-5 rounded
                         !border-0
                         !bg-darkBluishGray6 text-darkBluishGray1
                         !outline-0
                         !ring-0"
          />
          <label
            htmlFor="isLimitOrder"
            className="whitespace-nowrap px-4 text-darkBluishGray1"
          >
            Limit Order
          </label>
          <input
            value={priceInputValue}
            disabled={!isLimitOrder || mutation.isPending}
            onChange={handlePriceChange}
            autoFocus={isLimitOrder}
            className="w-36 rounded-xl border-swapRowBackground bg-swapRowBackground text-center text-white disabled:bg-darkBluishGray7"
          />
          {[
            ['Market', undefined],
            ['+1%', 100],
            ['+5%', 20]
          ].map(([label, incrementDivisor]) => (
            <button
              key={label}
              className={classNames(
                'rounded bg-swapDropdownBackground px-2 text-darkBluishGray2 ml-4',
                isLimitOrder && 'hover:bg-swapHighlight'
              )}
              disabled={!isLimitOrder}
              onClick={() =>
                setPriceFromMarketPrice(
                  incrementDivisor
                    ? BigInt(incrementDivisor as number)
                    : undefined
                )
              }
            >
              {label}
            </button>
          ))}
        </div>
        <div className="text-center text-darkBluishGray2">
          FEE:{' '}
          <AmountWithSymbol
            amount={fee}
            symbol={quoteSymbol}
            approximate={false}
          />
        </div>
        <div className="flex w-full flex-col">
          {walletAddress && exchangeContractAddress ? (
            <>
              {noPriceFound && (
                <span className="w-full text-center text-brightRed">
                  No price found.
                </span>
              )}
              <SubmitButton
                disabled={!canSubmit}
                onClick={mutation.mutate}
                error={mutation.error?.message}
                caption={() => {
                  if (mutation.isPending) {
                    return 'Submitting order...'
                  } else if (mutation.isSuccess) {
                    return 'Swapped!'
                  } else {
                    return 'Swap'
                  }
                }}
                status={mutation.status}
              />
            </>
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
      </div>

      {depositSymbol && depositSymbol.chainId == config.state.chainId && (
        <DepositModal
          isOpen={showDepositModal}
          exchangeContractAddress={exchangeContractAddress!}
          walletAddress={walletAddress!}
          symbol={depositSymbol}
          close={() => setShowDepositModal(false)}
          onClosed={() => {
            setDepositSymbol(null)
            setDepositRequestedAmount(0n)
          }}
          initialValue={
            depositRequestedAmount
              ? formatUnits(depositRequestedAmount, depositSymbol.decimals)
              : undefined
          }
        />
      )}
    </>
  )
}

function SellAmountInput({
  value,
  disabled,
  onChange,
  sellAssetsNeeded,
  onDeposit,
  inputRef
}: {
  value: string
  disabled: boolean
  onChange: (e: React.ChangeEvent<HTMLInputElement>) => void
  sellAssetsNeeded: bigint
  onDeposit: () => void
  inputRef: React.RefObject<HTMLInputElement>
}) {
  const [divRef, { width: spanWidth }] = useMeasure<HTMLDivElement>()
  useEffect(() => {
    if (inputRef.current) {
      inputRef.current.style.width = spanWidth + 40 + 'px'
    }
  }, [inputRef, spanWidth])
  return (
    <span className="flex flex-row justify-start align-middle">
      <span>
        <span className="align-middle">
          <div className="absolute text-2xl opacity-0" ref={divRef}>
            {value}
          </div>
          <input
            ref={inputRef as LegacyRef<HTMLInputElement>}
            className={classNames(
              'text-white text-2xl text-left',
              'inline-block rounded-xl border-0',
              'bg-darkBluishGray9 py-3',
              'ring-1 ring-inset ring-darkBluishGray6 focus:ring-1 focus:ring-inset focus:ring-mutedGray',
              '[appearance:textfield] placeholder:text-darkBluishGray2',
              '[&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none',
              'text-left bg-swapRowBackground !ring-0 !focus:ring-0',
              sellAssetsNeeded > 0n &&
                '!text-brightRed max-w-52 overflow-auto overflow-ellipsis'
            )}
            disabled={disabled}
            placeholder="0"
            value={value}
            onChange={onChange}
            autoFocus={true}
          />
        </span>
        {sellAssetsNeeded > 0n && (
          <>
            <span className="text-sm text-brightRed">Insufficient Balance</span>
            <button
              className="ml-2 rounded bg-swapDropdownBackground px-2 text-sm text-darkBluishGray2 hover:bg-swapHighlight"
              onClick={onDeposit}
            >
              Deposit
            </button>
          </>
        )}
      </span>
    </span>
  )
}
