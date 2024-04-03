export function classNames(...classes: unknown[]): string {
  return classes.filter(Boolean).join(' ')
}

export function isNotNullable<T>(element: T | null | undefined): element is T {
  return typeof element !== 'undefined' && element !== null
}

export function addressDisplay(address: string): string {
  const without0x = address.startsWith('0x') ? address.slice(2) : address
  return (
    '0x' + without0x.slice(0, 4) + '...' + without0x.slice(without0x.length - 4)
  )
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
;(BigInt.prototype as any).toJSON = function () {
  return this.toString()
}

export function cleanAndFormatNumberInput(inputValue: string) {
  let cleanedValue = inputValue
    .replace(/[^\d.]/g, '') // Remove all non-numeric characters
    .replace(/^0+(\d)/, '$1') // Leading zeros
    .replace(/^\./, '0.')

  // multiple decimal points
  cleanedValue =
    cleanedValue.split('.')[0] +
    (cleanedValue.includes('.')
      ? '.' + cleanedValue.split('.')[1].slice(0, 18)
      : '')

  return cleanedValue
}
