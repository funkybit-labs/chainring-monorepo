import Decimal from 'decimal.js'

export function CRView({
  amount,
  format = 'short'
}: {
  amount: Decimal
  format?: 'full' | 'short' | 'none'
}) {
  const userLocale = navigator.language || 'en-US'

  const formattedValue = new Intl.NumberFormat(userLocale, {
    maximumFractionDigits: 0,
    minimumFractionDigits: 0
  }).format(amount.toNumber())

  let suffix = ''
  if (format === 'short') {
    suffix = ' CR'
  } else if (format === 'full') {
    suffix = ' CR Points'
  }

  return `${formattedValue}${suffix}`
}
