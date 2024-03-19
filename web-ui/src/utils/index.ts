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
