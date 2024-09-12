import Markets, { Market } from 'markets'
import React, { LegacyRef, useEffect, useMemo, useRef, useState } from 'react'
import TradingSymbol from 'tradingSymbol'
import { Balance, FeeRates, OrderSide } from 'apiClient'
import { formatUnits } from 'viem'
import { SymbolSelector } from 'components/Screens/HomeScreen/SymbolSelector'
import AmountInput from 'components/common/AmountInput'
import { classNames } from 'utils'

import { useConfig } from 'wagmi'
import SwapIcon from 'assets/Swap.svg'
import SubmitButton from 'components/common/SubmitButton'
import DepositModal from 'components/Screens/HomeScreen/DepositModal'
import { useMeasure } from 'react-use'
import {
  SwapInternals,
  SwapRender
} from 'components/Screens/HomeScreen/swap/SwapInternals'
import { bigintToScaledDecimal, scaledDecimalToBigint } from 'utils/pricesUtils'
import Decimal from 'decimal.js'
import { ExpandableValue } from 'components/common/ExpandableValue'
import { ConnectWallet } from 'components/Screens/HomeScreen/swap/ConnectWallet'
import { useSwitchToEthChain } from 'utils/switchToEthChain'
import Deposit from 'assets/Deposit.svg'
import MarketPrice from 'components/Screens/HomeScreen/swap/MarketPrice'
import { useWallets } from 'contexts/walletProvider'

export function SwapModal({
  markets,
  exchangeContractAddress,
  walletAddress,
  feeRates,
  onMarketChange,
  onSideChange
}: {
  markets: Markets
  exchangeContractAddress?: string
  walletAddress?: string
  feeRates: FeeRates
  onMarketChange: (m: Market) => void
  onSideChange: (s: OrderSide) => void
}) {
  const wallets = useWallets()

  const [animateSide, setAnimateSide] = useState(false)
  function onChangedSide(s: OrderSide) {
    onSideChange(s)
    setAnimateSide(true)
    setTimeout(() => {
      setAnimateSide(false)
    }, 1000)
  }
  const [depositSymbol, setDepositSymbol] = useState<TradingSymbol | null>(null)
  const [showDepositModal, setShowDepositModal] = useState<boolean>(false)
  const config = useConfig()
  const switchToEthChain = useSwitchToEthChain()

  function openDepositModal(symbol: TradingSymbol) {
    setDepositSymbol(symbol)
    setShowDepositModal(true)
    if (symbol.chainId != config.state.chainId) {
      switchToEthChain(symbol.chainId)
    }
  }

  return SwapInternals({
    markets,
    exchangeContractAddress,
    walletAddress,
    feeRates,
    onMarketChange,
    onSideChange: onChangedSide,
    isLimitOrder: false,
    marketSessionStorageKey: 'sm_market',
    sideSessionStorageKey: 'sm_side',
    Renderer: function (sr: SwapRender) {
      const sellAmountInputRef = useRef<HTMLInputElement>(null)
      const config = useConfig()
      const [marketPriceInverted, setMarketPriceInverted] = useState(false)

      const marketPrice = useMemo(() => {
        const quoteDecimals =
          sr.side === 'Buy' ? sr.topSymbol.decimals : sr.bottomSymbol.decimals
        const unitPriceInQuote = sr.getMarketPrice(
          sr.side,
          scaledDecimalToBigint(new Decimal(1), quoteDecimals)
        )
        if (unitPriceInQuote) {
          if (marketPriceInverted) {
            const invertedPrice = scaledDecimalToBigint(
              new Decimal(1).div(
                bigintToScaledDecimal(unitPriceInQuote, quoteDecimals)
              ),
              quoteDecimals
            )
            return formatUnits(invertedPrice, quoteDecimals)
          } else {
            return formatUnits(unitPriceInQuote, quoteDecimals)
          }
        } else {
          return 'N/A'
        }
      }, [sr, marketPriceInverted])

      function depositAmount(
        deposit: Balance | undefined,
        symbol: TradingSymbol
      ) {
        return deposit?.available ?? BigInt(0) > BigInt(0) ? (
          <>
            <span className="font-[400] text-darkBluishGray2">On deposit:</span>
            <span className="text-lightBluishGray2">
              {deposit && (
                <ExpandableValue
                  value={formatUnits(deposit.available, symbol.decimals)}
                />
              )}{' '}
              {symbol.displayName()}
            </span>
          </>
        ) : (
          <>
            <span className="font-[400] text-darkBluishGray2">
              You have not deposited any {symbol.displayName()}
            </span>
          </>
        )
      }

      return (
        <>
          <div className="max-w-[680px] space-y-4 rounded-[20px] bg-darkBluishGray9 p-8 narrow:w-[680px]">
            <div
              className={classNames(
                'rounded-[20px] bg-darkBluishGray8 p-4 transition-all',
                sr.sellAssetsNeeded > 0n
                  ? 'ring-2 ring-brightRed'
                  : 'hover:[&:not(:focus-within)]:ring-darkBluishGray4 hover:[&:not(:focus-within)]:ring-1 focus-within:ring-statusOrange focus-within:ring-1 focus-within:shadow-[0_0_15px_0_rgb(255,163,55,1)]'
              )}
            >
              <div className="mb-2 flex flex-row justify-between">
                <span className="text-base text-darkBluishGray1">
                  Sell
                  {(sr.topLimit ?? BigInt(0)) > BigInt(0) && (
                    <button
                      className="ml-2 rounded bg-darkBluishGray6 px-2 py-1 text-sm text-darkBluishGray2 hover:bg-blue5"
                      onClick={() => {
                        if (sr.side === 'Sell') {
                          sr.handleMaxBaseAmount()
                        } else {
                          sr.handleMaxQuoteAmount()
                        }
                      }}
                    >
                      Max
                    </button>
                  )}
                </span>
                <div className="flex flex-row items-baseline space-x-2 text-sm">
                  {depositAmount(
                    exchangeContractAddress === undefined
                      ? undefined
                      : sr.topBalance,
                    sr.topSymbol
                  )}
                  {walletAddress && exchangeContractAddress && (
                    <button
                      className="rounded bg-darkBluishGray6 px-2 py-1 text-darkBluishGray2 hover:bg-blue5"
                      onClick={() => openDepositModal(sr.topSymbol)}
                    >
                      <span className="hidden narrow:inline">Deposit</span>
                      <img
                        className="hidden max-narrow:inline"
                        src={Deposit}
                        alt={'Deposit'}
                      />
                    </button>
                  )}
                </div>
              </div>
              <div
                className="flex cursor-text flex-row justify-between pt-2"
                onClick={() => sellAmountInputRef.current?.focus()}
              >
                <SellAmountInput
                  value={sr.sellAmountInputValue}
                  disabled={false}
                  onChange={
                    sr.side === 'Buy'
                      ? sr.handleQuoteAmountChange
                      : sr.handleBaseAmountChange
                  }
                  sellAssetsNeeded={sr.sellAssetsNeeded}
                  onDeposit={() => {
                    openDepositModal(sr.topSymbol)
                  }}
                  inputRef={sellAmountInputRef}
                />
                <SymbolSelector
                  markets={markets}
                  selected={sr.topSymbol}
                  onChange={sr.handleTopSymbolChange}
                />
              </div>

              {sr.sellAssetsNeeded > 0n && (
                <div className="text-sm text-brightRed narrow:hidden">
                  Insufficient Balance
                </div>
              )}
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
                onClick={() => sr.handleChangeSide()}
              />
            </div>
            <div>
              <div
                className={classNames(
                  'rounded-[20px] bg-darkBluishGray8 p-4 transition-all',
                  'hover:[&:not(:focus-within)]:ring-darkBluishGray4 hover:[&:not(:focus-within)]:ring-1',
                  'focus-within:ring-statusOrange focus-within:ring-1',
                  'focus-within:shadow-[0_0_15px_0_rgb(255,163,55,1)]'
                )}
              >
                <div className="mb-2 flex flex-row justify-between">
                  <span className="text-base text-darkBluishGray1">Buy</span>
                  <div className="flex flex-row space-x-2 align-middle text-sm">
                    {depositAmount(
                      exchangeContractAddress === undefined
                        ? undefined
                        : sr.bottomBalance,
                      sr.bottomSymbol
                    )}
                  </div>
                </div>
                <div className="flex flex-row justify-between">
                  <AmountInput
                    className="!focus:ring-0 !bg-darkBluishGray8 text-left text-xl !ring-0"
                    value={sr.buyAmountInputValue}
                    disabled={false}
                    onChange={
                      sr.side === 'Buy'
                        ? sr.handleBaseAmountChange
                        : sr.handleQuoteAmountChange
                    }
                  />
                  <SymbolSelector
                    markets={markets}
                    selected={sr.bottomSymbol}
                    onChange={sr.handleBottomSymbolChange}
                  />
                </div>
              </div>
              <MarketPrice
                bottomSymbol={sr.bottomSymbol}
                topSymbol={sr.topSymbol}
                isInverted={marketPriceInverted}
                price={marketPrice}
                onClick={() => setMarketPriceInverted(!marketPriceInverted)}
              />
            </div>
            <div className="flex w-full flex-col">
              {walletAddress &&
              exchangeContractAddress &&
              wallets.isConnected(sr.topSymbol.networkType) &&
              wallets.isConnected(sr.bottomSymbol.networkType) ? (
                <>
                  {sr.noPriceFound && (
                    <span className="w-full text-center text-brightRed">
                      No price found.
                    </span>
                  )}
                  <SubmitButton
                    disabled={!sr.canSubmit}
                    onClick={sr.mutation.mutate}
                    error={sr.mutation.error?.message}
                    caption={() => {
                      if (sr.mutation.isPending) {
                        return 'Submitting swap...'
                      } else if (
                        ['Partial', 'Filled'].includes(
                          sr.lastOrder?.status ?? ''
                        )
                      ) {
                        const ns = sr.lastOrder?.timing?.sequencerTimeNs
                        if (ns) {
                          const us = new Decimal(
                            ns.toString()
                          ).dividedToIntegerBy(1000)
                          return '✓ Swapped in ' + us + 'µs'
                        } else {
                          return '✓ Swapped'
                        }
                      } else if (sr.mutation.isSuccess) {
                        return '✓ Submitted'
                      } else if (sr.amountTooLow) {
                        return 'Amount Too Low'
                      } else {
                        return 'Swap'
                      }
                    }}
                    status={sr.amountTooLow ? 'error' : sr.mutation.status}
                  />
                </>
              ) : (
                <ConnectWallet
                  onSwitchToChain={(chainId) => switchToEthChain(chainId)}
                />
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
              }}
            />
          )}
        </>
      )
    }
  })
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
      inputRef.current.style.width = Math.max(40, spanWidth + 24) + 'px'
    }
  }, [inputRef, spanWidth])
  return (
    <span className="flex flex-row justify-start align-middle">
      <span className="whitespace-nowrap">
        <span className="align-middle">
          <div className="absolute text-xl opacity-0" ref={divRef}>
            {value}
          </div>
          <input
            ref={inputRef as LegacyRef<HTMLInputElement>}
            className={classNames(
              'text-white text-xl text-left',
              'inline-block rounded-xl border-0',
              'bg-darkBluishGray8 py-3',
              'ring-1 ring-inset ring-darkBluishGray6 focus:ring-1 focus:ring-inset focus:ring-mutedGray',
              '[appearance:textfield] placeholder:text-darkBluishGray2',
              '[&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none',
              'text-left !ring-0 !focus:ring-0',
              sellAssetsNeeded > 0n &&
                '!text-brightRed max-w-20 narrow:max-w-44 overflow-auto overflow-ellipsis'
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
            <span className="hidden text-sm text-brightRed narrow:inline">
              Insufficient Balance
            </span>
            <button
              className="ml-2 rounded bg-darkBluishGray6 px-2 py-1 text-sm text-darkBluishGray2 hover:bg-blue5"
              onClick={onDeposit}
            >
              <span className="hidden narrow:inline">Deposit</span>
              <img
                className="hidden max-narrow:inline"
                src={Deposit}
                alt={'Deposit'}
              />
            </button>
          </>
        )}
      </span>
    </span>
  )
}
