import React, { ReactElement } from 'react'
import { classNames } from 'utils'

export type HeadSubStyle = 'normal' | 'smallSub' | 'largeSub' | 'equal'

export function HeadSub({
  head,
  sub,
  style
}: {
  head: string
  sub: string
  style?: HeadSubStyle
}) {
  return (
    <div className="flex flex-col">
      <span
        className={classNames(
          'whitespace-nowrap',
          style === 'largeSub' ? 'text-[16px]' : 'text-2xl'
        )}
      >
        {head}
      </span>
      <span
        className={classNames(
          'whitespace-nowrap',
          style === 'smallSub' && 'text-sm',
          style === 'largeSub' && 'text-xl font-normal text-white/70',
          style === 'equal' && 'text-2xl'
        )}
      >
        {sub}
      </span>
    </div>
  )
}

export type Rounded = 'top' | 'bottom' | 'both' | 'none'
export type InfoPanelStyle = 'normal' | 'large'

export function InfoPanel({
  icon,
  rounded,
  style,
  children
}: {
  icon: string
  rounded: Rounded
  style?: InfoPanelStyle
  children: ReactElement
}) {
  return (
    <div
      className={classNames(
        'mx-auto mt-2 w-full flex justify-stretch px-6 bg-darkBlue py-4',
        (rounded === 'top' || rounded === 'both') && 'rounded-t-3xl',
        (rounded === 'bottom' || rounded === 'both') && 'rounded-b-3xl'
      )}
    >
      <div className="flex w-full items-center justify-start gap-4 font-medium text-white">
        <img
          src={icon}
          className={classNames('h-auto', style === 'large' ? 'w-32' : 'w-16')}
          alt="icon"
        />
        {children}
      </div>
    </div>
  )
}
