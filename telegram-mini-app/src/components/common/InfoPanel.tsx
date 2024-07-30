import React from 'react'
import { classNames } from 'utils'

export function HeadSub({
  head,
  sub,
  smallSub
}: {
  head: string
  sub: string
  smallSub?: boolean
}) {
  return (
    <div className="flex flex-col">
      <span className="text-2xl">{head}</span>
      <span className={classNames(smallSub && 'text-sm')}>{sub}</span>
    </div>
  )
}

export type Rounded = 'top' | 'bottom' | 'both' | 'none'

export function InfoPanel({
  icon,
  info,
  rounded
}: {
  icon: string
  info: JSX.Element
  rounded: Rounded
}) {
  return (
    <div
      className={classNames(
        'mx-auto mt-2 w-full flex justify-stretch px-6 bg-darkBlue py-4',
        (rounded === 'top' || rounded === 'both') && 'rounded-t-3xl',
        (rounded === 'bottom' || rounded === 'both') && 'rounded-b-3xl'
      )}
    >
      <div className="flex items-center justify-center gap-4 font-content font-medium text-white">
        <img src={icon} className="h-auto w-16" alt="icon" />
        {info}
      </div>
    </div>
  )
}
