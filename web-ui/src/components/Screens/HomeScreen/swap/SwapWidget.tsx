import Markets, { Market } from 'markets'
import React, { useMemo, useState } from 'react'
import TradingSymbol from 'tradingSymbol'
import { Balance, FeeRates, OrderSide } from 'apiClient'
import { Address, formatUnits } from 'viem'
import { SymbolSelector } from 'components/Screens/HomeScreen/SymbolSelector'
import AmountInput from 'components/common/AmountInput'
import { classNames } from 'utils'
import { useWeb3Modal } from '@web3modal/wagmi/react'
import SwapIcon from 'assets/Swap.svg'
import SubmitButton from 'components/common/SubmitButton'
import { Button } from 'components/common/Button'
import {
  SwapInternals,
  SwapRender
} from 'components/Screens/HomeScreen/swap/SwapInternals'
import { ExpandableValue } from 'components/common/ExpandableNumber'
import { bigintToScaledDecimal, scaledDecimalToBigint } from 'utils/pricesUtils'
import Decimal from 'decimal.js'

export function SwapWidget({
  markets,
  exchangeContractAddress,
  walletAddress,
  feeRates,
  onMarketChange,
  onSideChange
}: {
  markets: Markets
  exchangeContractAddress?: Address
  walletAddress?: Address
  feeRates: FeeRates
  onMarketChange: (m: Market) => void
  onSideChange: (s: OrderSide) => void
}) {
  const [animateSide, setAnimateSide] = useState(false)
  function onChangedSide(s: OrderSide) {
    onSideChange(s)
    setAnimateSide(true)
    setTimeout(() => {
      setAnimateSide(false)
    }, 1000)
  }
  return SwapInternals({
    markets,
    exchangeContractAddress,
    walletAddress,
    feeRates,
    onMarketChange,
    onSideChange: onChangedSide,
    isLimitOrder: false,
    Renderer: function (sr: SwapRender) {
      const { open: openWalletConnectModal } = useWeb3Modal()
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

      return (
        <>
          <div className="space-y-4 rounded bg-darkBluishGray9 p-8">
            <div className="rounded bg-darkBluishGray8 p-4">
              <div className="mb-2 flex flex-row justify-between">
                <span className="text-base text-darkBluishGray1">Sell</span>
                <div className="flex flex-row items-baseline space-x-2 text-sm">
                  {depositAmount(sr.topBalance, sr.topSymbol)}
                </div>
              </div>
              <div className="flex flex-row justify-between space-x-2">
                <AmountInput
                  className={classNames(
                    '!focus:ring-0 bg-darkBluishGray8 text-left text-xl !ring-0',
                    sr.sellAssetsNeeded > 0n && '!text-statusRed'
                  )}
                  value={sr.sellAmountInputValue}
                  disabled={false}
                  onChange={
                    sr.side === 'Buy'
                      ? sr.handleQuoteAmountChange
                      : sr.handleBaseAmountChange
                  }
                />
                <SymbolSelector
                  markets={markets}
                  selected={sr.topSymbol}
                  onChange={sr.handleTopSymbolChange}
                />
              </div>
              <div className="mt-2 space-x-2 text-center text-white">
                <span>Sell at</span>
                <span className="relative">
                  <input
                    value={sr.sellLimitPriceInputValue}
                    disabled={sr.mutation.isPending}
                    onChange={(e) => {
                      sr.handleMarketOrderFlagChange(e.target.value != '')
                      sr.handleSellLimitPriceChange(e.target.value)
                      if (e.target.value) setMarketPriceInverted(true)
                    }}
                    className="w-36 rounded-xl border-darkBluishGray8 bg-darkBluishGray9 text-center text-white disabled:bg-darkBluishGray6"
                  />
                  {sr.percentOffMarket !== undefined && (
                    <span
                      className={classNames(
                        'ml-2 text-xs absolute right-1 -top-2.5 text-darkBluishGray1'
                      )}
                    >
                      {sr.percentOffMarket > 0 && '+'}
                      {sr.percentOffMarket.toFixed(1)}%
                    </span>
                  )}
                </span>
                {[
                  ['Market', undefined],
                  ['+1%', 100],
                  ['+5%', 20]
                ].map(([label, incrementDivisor]) => (
                  <button
                    key={label}
                    className={classNames(
                      'rounded bg-darkBluishGray6 px-2 text-darkBluishGray2 ml-4 mt-2',
                      sr.isLimitOrder && 'hover:bg-blue5'
                    )}
                    disabled={sr.mutation.isPending}
                    onClick={() => {
                      sr.setPriceFromMarketPrice(
                        incrementDivisor
                          ? BigInt(incrementDivisor as number)
                          : undefined
                      )
                      setMarketPriceInverted(true)
                    }}
                  >
                    {label}
                  </button>
                ))}
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
                onClick={() => sr.handleChangeSide()}
              />
            </div>
            <div className="rounded bg-darkBluishGray8 p-4">
              <div className="mb-2 flex flex-row justify-between">
                <span className="text-base text-darkBluishGray1">Buy</span>
                <div className="flex flex-row space-x-2 align-middle text-sm">
                  {depositAmount(sr.bottomBalance, sr.bottomSymbol)}
                </div>
              </div>
              <div className="flex flex-row justify-between space-x-2">
                <AmountInput
                  className="!focus:ring-0 bg-darkBluishGray8 text-left text-xl !ring-0"
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
              <div className="mt-2 space-x-2 text-center text-white">
                <span>Buy at</span>
                <span className="relative">
                  <input
                    value={sr.buyLimitPriceInputValue}
                    disabled={sr.mutation.isPending}
                    onChange={(e) => {
                      sr.handleMarketOrderFlagChange(e.target.value != '')
                      sr.handleBuyLimitPriceChange(e.target.value)
                      if (e.target.value) setMarketPriceInverted(false)
                    }}
                    className="w-36 rounded-xl border-darkBluishGray8 bg-darkBluishGray9 text-center text-white disabled:bg-darkBluishGray6"
                  />
                  {sr.percentOffMarket !== undefined && (
                    <span
                      className={classNames(
                        'ml-2 text-xs absolute right-1 -top-2.5 text-darkBluishGray1'
                      )}
                    >
                      {sr.percentOffMarket < 0 && '+'}
                      {(-sr.percentOffMarket).toFixed(1)}%
                    </span>
                  )}
                </span>
                {[
                  ['Market', undefined],
                  ['-1%', 100],
                  ['-5%', 20]
                ].map(([label, incrementDivisor]) => (
                  <button
                    key={label}
                    className={classNames(
                      'rounded bg-darkBluishGray6 px-2 text-darkBluishGray2 ml-4 mt-2',
                      sr.isLimitOrder && 'hover:bg-blue5'
                    )}
                    disabled={sr.mutation.isPending}
                    onClick={() => {
                      sr.setPriceFromMarketPrice(
                        incrementDivisor
                          ? BigInt(incrementDivisor as number)
                          : undefined
                      )
                      setMarketPriceInverted(false)
                    }}
                  >
                    {label}
                  </button>
                ))}
              </div>
            </div>
            <div
              className="mt-1 cursor-pointer pl-4 text-darkBluishGray1 hover:text-statusOrange"
              onClick={() => setMarketPriceInverted(!marketPriceInverted)}
            >
              1 {marketPriceInverted ? sr.topSymbol.name : sr.bottomSymbol.name}{' '}
              ≈ <ExpandableValue value={marketPrice} />{' '}
              {marketPriceInverted ? sr.bottomSymbol.name : sr.topSymbol.name}
            </div>
            <div className="flex w-full flex-col">
              {walletAddress && exchangeContractAddress ? (
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
                        return 'Submitting order...'
                      } else if (sr.mutation.isSuccess) {
                        return '✓'
                      } else if (sr.sellAssetsNeeded > 0n) {
                        return 'Insufficient Balance'
                      } else {
                        return 'Swap'
                      }
                    }}
                    status={
                      sr.sellAssetsNeeded > 0n ? 'error' : sr.mutation.status
                    }
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
        </>
      )
    }
  })
}