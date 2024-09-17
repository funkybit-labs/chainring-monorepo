import TradingSymbol from 'tradingSymbol'
import { ConnectedWallet } from 'contexts/walletProvider'
import { NetworkType } from 'apiClient'

export function classNames(...classes: unknown[]): string {
  return classes.filter(Boolean).join(' ')
}

export function isNotNullable<T>(element: T | null | undefined): element is T {
  return typeof element !== 'undefined' && element !== null
}

export function uniqueFilter<T>(value: T, index: number, self: T[]): boolean {
  return self.indexOf(value) === index
}

export function abbreviatedAddress(
  address: string,
  networkType: NetworkType
): string {
  switch (networkType) {
    case 'Evm': {
      const without0x = address.startsWith('0x') ? address.slice(2) : address

      return (
        '0x' +
        without0x.slice(0, 4) +
        '...' +
        without0x.slice(without0x.length - 4)
      )
    }
    case 'Bitcoin':
      return address.slice(0, 5) + '...' + address.slice(address.length - 5)
  }
}

export function abbreviatedWalletAddress(wallet: ConnectedWallet): string {
  return abbreviatedAddress(wallet.address, wallet.networkType)
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
;(BigInt.prototype as any).toJSON = function () {
  return this.toString()
}

export function cleanAndFormatNumberInput(
  inputValue: string,
  decimals: number
): string {
  const decimalSeparator = (1.2).toLocaleString()[1] || '.'

  let cleanedValue = inputValue
    .replace(/[^\d.]/g, '') // Remove all non-numeric characters
    .replace(/^0+(\d)/, '$1') // Leading zeros
    .replace(/^\./, `0${decimalSeparator}`)

  // multiple decimal points
  cleanedValue =
    cleanedValue.split(decimalSeparator)[0] +
    (cleanedValue.includes(decimalSeparator)
      ? '.' + cleanedValue.split(decimalSeparator)[1].slice(0, decimals)
      : '')

  return cleanedValue
}

export function calculateNotional(
  price: bigint,
  baseAmount: bigint,
  baseSymbol: TradingSymbol
): bigint {
  return (price * baseAmount) / BigInt(Math.pow(10, baseSymbol.decimals))
}

export const FEE_RATE_PIPS_MAX_VALUE = 1000000

export function calculateFee(notional: bigint, feeRate: bigint): bigint {
  return (notional * feeRate) / BigInt(FEE_RATE_PIPS_MAX_VALUE)
}

export function calculateNotionalMinusFee(
  notional: bigint,
  feeRate: bigint
): bigint {
  return (
    (notional * BigInt(FEE_RATE_PIPS_MAX_VALUE)) /
    (BigInt(FEE_RATE_PIPS_MAX_VALUE) + feeRate)
  )
}

export function base64urlEncode(input: Uint8Array): string {
  return Buffer.from(input)
    .toString('base64')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=/g, '')
}

export function minBigInt(a: bigint, b: bigint): bigint {
  if (a <= b) {
    return a
  } else {
    return b
  }
}
