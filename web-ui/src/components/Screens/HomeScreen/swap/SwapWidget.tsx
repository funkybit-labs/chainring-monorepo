import Markets, { Market } from 'markets'
import React, { useState } from 'react'
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

      function depositAmount(
        deposit: Balance | undefined,
        symbol: TradingSymbol
      ) {
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

      return (
        <>
          <div className="space-y-4 rounded-[20px] bg-darkBluishGray9 p-8">
            <div className="rounded-[20px] bg-darkBluishGray8 p-4">
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
              <div className="mt-2 text-center">
                <input
                  id="isLimitOrder"
                  name="isLimitOrder"
                  type="checkbox"
                  checked={sr.isLimitOrder}
                  disabled={sr.mutation.isPending}
                  onChange={sr.handleMarketOrderFlagChange}
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
                  value={sr.limitPriceInputValue}
                  disabled={!sr.isLimitOrder || sr.mutation.isPending}
                  onChange={sr.handlePriceChange}
                  autoFocus={sr.isLimitOrder}
                  className="w-36 rounded-xl border-darkBluishGray8 bg-darkBluishGray9 text-center text-white disabled:bg-darkBluishGray6"
                />
                <br />
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
                    disabled={!sr.isLimitOrder}
                    onClick={() =>
                      sr.setPriceFromMarketPrice(
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
            <div className="rounded-[20px] bg-darkBluishGray8 p-4">
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
