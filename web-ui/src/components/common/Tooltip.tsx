import { JSX } from 'react'
import { classNames } from 'utils'

type Props = {
  message: string | JSX.Element
  children: JSX.Element
  style: 'normal' | 'warning'
}

export function Tooltip({ message, children, style }: Props) {
  return (
    <div className="group relative">
      {children}
      <div
        className={classNames(
          'absolute -top-6 scale-0 transition-all group-hover:scale-100',
          'w-full flex justify-center'
        )}
      >
        <span
          className={classNames(
            'transition-all rounded-lg bg-darkBluishGray6 p-2 text-xs font-bold',
            style === 'warning' ? 'text-statusRed' : 'text-white'
          )}
        >
          {message}
        </span>
      </div>
    </div>
  )
}
