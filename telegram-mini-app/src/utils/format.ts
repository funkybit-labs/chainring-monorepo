import Decimal from 'decimal.js'

export function decimalAsInt(input: Decimal) {
  const userLocale = navigator.language || 'en-US'

  return new Intl.NumberFormat(userLocale, {
    maximumFractionDigits: 0,
    minimumFractionDigits: 0
  }).format(input.toNumber())
}
